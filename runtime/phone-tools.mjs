import {randomUUID} from 'node:crypto';
import {PhoneSocketClient} from './phone-socket-client.mjs';
import {PhonePathMapping} from './phone-paths.mjs';
import {PhoneTurnGuard} from './phone-turn-guard.mjs';

export function phoneToolResult(result,mapping){
  if(!result||typeof result!=='object'||Array.isArray(result))throw new Error('Android phone tool returned an invalid result object');
  const {image,mimeType,...rawDetails}=result;
  const details=mapping.result(rawDetails);
  return {content:[{type:'text',text:JSON.stringify(details)},...(image?[{type:'image',data:image,mimeType:mimeType||'image/png'}]:[])],details};
}
/** One user/agent turn, sharing the embedded Pi's target-app and repeated-action guard. */
export class PhoneToolSession {
  constructor({client=new PhoneSocketClient(),mapping=new PhonePathMapping(),turnId=`omp-phone-${randomUUID()}`}={}){
    this.client=client;this.mapping=mapping;this.turnId=turnId;this.guard=new PhoneTurnGuard();this.halted=null;
  }
  halt(error,onHalt){this.halted=error;queueMicrotask(()=>onHalt?.(error));}
  async native(name,args,signal,onHalt){
    if(this.halted)throw this.halted;
    let guarded;
    try{guarded=this.guard.before(name,args);}catch(error){this.halt(error,onHalt);throw error;}
    let result;
    try{result=await this.client.call(name,guarded,{signal,turnId:this.turnId});}
    catch(error){if(error.code==='NATIVE_UNCONFIRMED')this.halt(error,onHalt);throw error;}
    if(!signal?.aborted){
      try{this.guard.observed(name,result,guarded);}catch(error){this.halt(error,onHalt);throw error;}
    }
    return result;
  }
  async call(name,args,{signal,cwd=process.cwd(),onHalt}={}){
    if(signal?.aborted)throw new Error('操作已停止');
    if(this.halted)throw this.halted;
    const input=this.mapping.arguments(name,args,cwd);
    let result=await this.native(name,input,signal,onHalt);
    const needsScreenshot=(name==='screen_read'&&result.readable===false)||(name==='ui_tap'&&!input.nodeId)||(name==='ui_input'&&!input.nodeId)||name==='ui_swipe'||name==='ui_system'&&input.action!=='list'||name==='split_screen'&&['toggle','launch'].includes(input.action||'')||name==='ui_back'&&this.guard.packageName;
    if(needsScreenshot&&!signal?.aborted){
      try{result={...result,...await this.native('screen_capture',{},signal,onHalt),mode:'visual'};}
      catch(error){result={...result,visualError:error.message,...(error.code?{visualErrorCode:error.code}:{})};}
    }
    return phoneToolResult(result,this.mapping);
  }
  async list({signal}={}){return this.mapping.readiness(await this.client.connect({signal}));}
  close(){this.client.close();}
}
