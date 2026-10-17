import net from 'node:net';
import path from 'node:path';
import {NativeToolBridge} from './native-tools.mjs';
import {encodePhoneFrame,PhoneFrameDecoder,PhoneProtocolError} from './phone-frames.mjs';
import {phoneToolNames} from './phone-tool-definitions.mjs';

const allowedTools=new Set(phoneToolNames);
function bridgeError(code,message,cause){const error=new Error(message,cause?{cause}:undefined);error.code=code;return error;}
function stopped(){return bridgeError('PHONE_CANCELLED','操作已停止；未发送 Android 手机工具请求');}
function waitForReady(promise,signal){
  if(!signal)return promise;
  if(signal.aborted)return Promise.reject(stopped());
  return new Promise((resolve,reject)=>{
    const abort=()=>{signal.removeEventListener('abort',abort);reject(stopped());};
    signal.addEventListener('abort',abort,{once:true});
    promise.then(value=>{signal.removeEventListener('abort',abort);resolve(value);},error=>{signal.removeEventListener('abort',abort);reject(error);});
  });
}
function validateReady(message){
  if(message.type!=='bridge_ready'||message.protocol!==1)throw new PhoneProtocolError('first frame must be bridge_ready with protocol 1');
  if(!Array.isArray(message.tools)||message.tools.some(name=>!allowedTools.has(name))||new Set(message.tools).size!==message.tools.length)throw new PhoneProtocolError('invalid or unsupported advertised tool names');
  if(typeof message.workspaceRoot!=='string'||!path.posix.isAbsolute(message.workspaceRoot)||typeof message.workspace!=='string'||!path.posix.isAbsolute(message.workspace))throw new PhoneProtocolError('readiness must include absolute Android workspaceRoot and workspace paths');
}
function validateReceipt(message){
  if(!['tool_result','tool_heartbeat'].includes(message.type)||typeof message.id!=='string'||!message.id)throw new PhoneProtocolError('expected tool_result or tool_heartbeat with an id');
  if(message.type==='tool_result'){
    const hasError=Object.hasOwn(message,'error'),hasResult=Object.hasOwn(message,'result');
    if(hasError===hasResult||hasError&&(typeof message.error!=='string'||!message.error))throw new PhoneProtocolError('tool_result must carry exactly one result or nonempty string error');
  }
}
/** Same-app Android AF_UNIX client. No reconnect timers and no request replay. */
export class PhoneSocketClient {
  constructor({endpoint=process.env.ZFOLD_PHONE_SOCKET,readyTimeoutMs=10000,leaseMs=30000,cancelMs=5000,createConnection=net.createConnection}={}){
    this.endpoint=endpoint;this.readyTimeoutMs=readyTimeoutMs;this.createConnection=createConnection;
    this.connection=null;this.connecting=null;this.ready=null;this.closed=false;
    this.bridge=new NativeToolBridge(message=>this.send(message),{leaseMs,cancelMs});
  }
  connect({signal}={}){
    if(signal?.aborted)return Promise.reject(stopped());
    if(this.closed)return Promise.reject(bridgeError('PHONE_CLOSED','Android phone bridge client is closed'));
    if(this.ready)return Promise.resolve(this.ready);
    if(this.connecting)return waitForReady(this.connecting,signal);
    if(typeof this.endpoint!=='string'||!this.endpoint||this.endpoint.includes('\0')||Buffer.byteLength(this.endpoint)>107)return Promise.reject(bridgeError('PHONE_ENDPOINT_MISSING','Set ZFOLD_PHONE_SOCKET to the bare Android abstract socket name supplied by the assistant app (not a filesystem path)'));
    const connection={socket:null,decoder:new PhoneFrameDecoder(),ready:false,done:false,timer:null,resolve:null,reject:null};
    const promise=new Promise((resolve,reject)=>{connection.resolve=resolve;connection.reject=reject;});
    this.connection=connection;this.connecting=promise;
    connection.timer=setTimeout(()=>this.fail(connection,bridgeError('PHONE_READY_TIMEOUT',`Android phone bridge @${this.endpoint} did not send protocol readiness within ${this.readyTimeoutMs}ms; ensure the assistant app is running`)),this.readyTimeoutMs);
    try{
      // Peer UID is verified by Android, outside PRoot. Guest getuid() is not authoritative.
      const socket=this.createConnection({path:'\0'+this.endpoint});connection.socket=socket;
      socket.on('data',chunk=>{
        if(connection.done)return;
        try{connection.decoder.push(chunk,message=>{
          if(!connection.ready){
            validateReady(message);connection.ready=true;this.ready=message;this.connecting=null;
            clearTimeout(connection.timer);connection.resolve(message);
          }else{validateReceipt(message);this.bridge.receive(message);}
        });}catch(error){this.fail(connection,error);}
      });
      socket.on('error',cause=>this.fail(connection,bridgeError('PHONE_CONNECTION',`Cannot use Android phone bridge @${this.endpoint}: ${cause.code||cause.message}; ensure the assistant app is running and this process uses its real Android UID`,cause)));
      socket.on('end',()=>{
        if(connection.done)return;
        let error=bridgeError('PHONE_DISCONNECTED',`Android phone bridge @${this.endpoint} closed the connection`);
        try{connection.decoder.finish();}catch(cause){error=cause;}
        this.fail(connection,error);
      });
      socket.on('close',()=>this.fail(connection,bridgeError('PHONE_DISCONNECTED',`Android phone bridge @${this.endpoint} closed the connection`)));
    }catch(cause){this.fail(connection,bridgeError('PHONE_CONNECTION',`Cannot connect to Android abstract phone socket @${this.endpoint}: ${cause.message}`,cause));}
    return waitForReady(promise,signal);
  }
  fail(connection,error){
    if(connection.done)return;
    connection.done=true;clearTimeout(connection.timer);
    if(this.connection===connection){this.connection=null;this.ready=null;this.connecting=null;this.bridge.disconnect(error);}
    if(!connection.ready)connection.reject(error);
    connection.socket?.destroy();
  }
  send(message){
    // NativeToolBridge's uncertainty notification is local, not a wire request type.
    if(message.type==='tool_uncertain')return;
    if(!['tool_request','tool_cancel'].includes(message.type))throw new PhoneProtocolError('unsupported outgoing message');
    const connection=this.connection;
    if(!connection?.ready||connection.done||!connection.socket?.writable)throw bridgeError(message.type==='tool_cancel'?'NATIVE_UNCONFIRMED':'PHONE_DISCONNECTED',message.type==='tool_cancel'?'Android cancellation could not be delivered; execution is uncertain, check the phone before retrying':'Android phone bridge disconnected before dispatch');
    const frame=encodePhoneFrame(message);
    try{connection.socket.write(frame,error=>{if(error)this.fail(connection,error);});}
    catch(cause){this.fail(connection,cause);throw bridgeError('NATIVE_UNCONFIRMED','Android phone request write failed; execution is uncertain, check the phone before retrying',cause);}
  }
  async call(name,args={},options={}){
    if(!allowedTools.has(name))throw bridgeError('PHONE_TOOL_NOT_ALLOWED',`Android tool is not exposed by this transport: ${name}`);
    if(!args||typeof args!=='object'||Array.isArray(args))throw bridgeError('PHONE_ARGUMENTS','Android phone tool arguments must be a JSON object');
    const ready=await this.connect({signal:options.signal});
    if(!ready.tools.includes(name))throw bridgeError('PHONE_TOOL_UNAVAILABLE',`Android bridge did not advertise ${name}`);
    return this.bridge.call(name,args,options);
  }
  close(){
    if(this.closed)return;
    this.closed=true;
    // Send cancellation, but never claim a successful stop without a terminal receipt.
    for(const entry of this.bridge.pending.values())this.bridge.cancel(entry,'shutdown');
    if(this.connection)this.fail(this.connection,bridgeError('PHONE_CLOSED','Android phone bridge client closed'));
  }
}
