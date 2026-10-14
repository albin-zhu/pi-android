/** Transport liveness is independent of a tool's own execution deadline. */
export class NativeToolBridge {
  constructor(send,{leaseMs=30000,cancelMs=5000}={}) {
    this.send=send;this.leaseMs=leaseMs;this.cancelMs=cancelMs;this.pending=new Map();this.sequence=0;
  }
  call(name,args,{signal,turnId=null}={}) {
    if(signal?.aborted)return Promise.reject(new Error('操作已停止'));
    const id=`native-${++this.sequence}`;
    return new Promise((resolve,reject)=>{
      const entry={name,id,turnId,resolve,reject,signal,timer:null,cancelling:false,reason:null};
      entry.abort=()=>this.cancel(entry,'cancelled');
      this.pending.set(id,entry);
      signal?.addEventListener('abort',entry.abort,{once:true});
      this.arm(entry);
      try {this.send({type:'tool_request',id,name,args,turnId});}
      catch(error){this.finish(entry,null,error);}
    });
  }
  arm(entry) {
    clearTimeout(entry.timer);
    entry.timer=setTimeout(()=>{
      if(entry.cancelling)this.unconfirmed(entry,'停止尚未得到执行端确认；请核对实际执行状态');
      else this.cancel(entry,'unresponsive');
    },entry.cancelling?this.cancelMs:this.leaseMs);
  }
  cancel(entry,reason) {
    if(!this.pending.has(entry.id)||entry.cancelling)return;
    entry.cancelling=true;entry.reason=reason;this.arm(entry);
    try{this.send({type:'tool_cancel',id:entry.id,reason});}
    catch(error){this.finish(entry,null,error);}
  }
  receive(message) {
    if(!['tool_heartbeat','tool_result'].includes(message.type))return false;
    const entry=this.pending.get(message.id);
    if(!entry)return true;
    if(message.type==='tool_heartbeat'){
      if(!entry.cancelling)this.arm(entry);
      return true;
    }
    // A completed action can beat cancellation. Preserve its real receipt.
    if(message.error&&message.error.includes('停止尚未确认'))this.unconfirmed(entry,message.error);
    else this.finish(entry,message.result,message.error?new Error(message.error):null);
    return true;
  }
  disconnect(cause){
    for(const entry of this.pending.values())this.unconfirmed(entry,
      `Android phone bridge disconnected before a terminal receipt for ${entry.name}; execution is uncertain, check the phone before retrying (${cause?.message||'connection closed'})`,cause);
  }
  unconfirmed(entry,message,cause){
    const error=new Error(message,cause?{cause}:undefined);error.code='NATIVE_UNCONFIRMED';
    error.toolId=entry.id;error.toolName=entry.name;
    try{this.send({type:'tool_uncertain',id:entry.id,turnId:entry.turnId,name:entry.name,message});}catch{}
    finally{this.finish(entry,null,error);}
  }
  finish(entry,value,error) {
    if(!this.pending.delete(entry.id))return;
    clearTimeout(entry.timer);entry.signal?.removeEventListener('abort',entry.abort);
    if(error)entry.reject(error);else entry.resolve(value);
  }
}
