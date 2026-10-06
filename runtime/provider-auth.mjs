import {randomUUID} from 'node:crypto';

function webURL(value){try{const url=new URL(value);return ['http:','https:'].includes(url.protocol)&&!url.username&&!url.password?url.href:null;}catch{return null;}}
export function authFailure(error){
  const message=String(error?.message||error||'');
  if(/state mismatch/i.test(message))return '回调与本次登录不匹配，请重新登录';
  if(/missing authorization code/i.test(message))return '回调中没有授权码，请粘贴登录后返回的完整地址';
  if(/device code login is not enabled/i.test(message))return '账号暂未启用设备码登录，请在 ChatGPT 安全设置中启用，或改用浏览器登录';
  if(/fetch failed|network|ECONN|ENOTFOUND|timeout/i.test(message))return '连接登录服务失败，请检查手机网络后重试';
  const status=message.match(/(?:status\s*|failed\s*\()(\d{3})/i)?.[1];
  return status?`登录服务返回 HTTP ${status}，请检查登录方式和账号权限后重试`:'登录未完成，请重试或切换登录方式';
}

/** Login state belongs to the runtime, not an Android dialog or Activity. */
export class ProviderAuthManager {
  constructor({getRuntime,emit,onSuccess=()=>{},timeoutMs=15*60_000}){this.getRuntime=getRuntime;this.emit=emit;this.onSuccess=onSuccess;this.timeoutMs=timeoutMs;this.flows=new Map();this.prompts=new Map();}
  snapshot(flow){return {requestId:flow.id,provider:flow.provider,authType:flow.authType,state:flow.state,active:flow.active,expiresAt:flow.expiresAt,
    ...(flow.prompt?{promptId:flow.prompt.id,prompt:flow.prompt.value}:{}),...(flow.browser?{browser:flow.browser}:{}),...(flow.device?{device:flow.device}:{}),
    ...(flow.message?{message:flow.message}:{}),...(flow.error?{error:flow.error}:{}),...(flow.credentialType?{credentialType:flow.credentialType}:{})};}
  snapshots(){for(const [id,flow] of this.flows)if(!flow.active&&Date.now()-flow.finishedAt>120000)this.flows.delete(id);return [...this.flows.values()].map(flow=>this.snapshot(flow));}
  publish(flow){this.emit({type:'auth_state',...this.snapshot(flow)});}
  rejectCommand(command,message){this.emit({type:'auth_state',state:'command_error',requestId:command.requestId,provider:command.provider,error:message});}
  async start({requestId=randomUUID(),provider,authType='oauth'}){
    if(typeof requestId!=='string'||requestId.length>120||!['oauth','api_key'].includes(authType)){this.rejectCommand({requestId,provider},'登录请求无效');return;}
    if(this.flows.get(requestId)?.active&&this.flows.get(requestId).provider!==provider){this.rejectCommand({requestId,provider},'登录请求编号已被占用');return;}
    const existing=[...this.flows.values()].find(flow=>flow.active&&flow.provider===provider);
    if(existing){this.publish(existing);return;}
    const flow={id:requestId,provider,authType,active:true,state:'started',controller:new AbortController(),expiresAt:Date.now()+this.timeoutMs};
    this.flows.set(requestId,flow);this.publish(flow);
    const timer=setTimeout(()=>{flow.timedOut=true;flow.controller.abort();},this.timeoutMs);timer.unref?.();
    try{
      const runtime=await this.getRuntime(),definition=runtime.getProvider(provider);
      if(typeof definition?.auth?.[authType==='oauth'?'oauth':'apiKey']?.login!=='function'){flow.error='这个供应商不支持所选登录方式';throw new Error();}
      if(flow.controller.signal.aborted)throw new Error();
      const credential=await runtime.login(provider,authType,{
        signal:flow.controller.signal,
        prompt:value=>this.prompt(flow,value),
        notify:event=>this.notify(flow,event),
      });
      if(flow.controller.signal.aborted)throw new Error();
      flow.state='success';flow.credentialType=credential?.type||authType;flow.message='登录成功';
      this.onSuccess();
    }catch(error){flow.state=flow.failure?'error':flow.controller.signal.aborted?(flow.timedOut?'expired':'cancelled'):'error';flow.error=flow.failure|| (flow.timedOut?'登录已超时，请重新开始':flow.state==='cancelled'?'登录已取消':flow.error||authFailure(error));}
    finally{
      clearTimeout(timer);flow.active=false;flow.finishedAt=Date.now();this.clearPrompts(flow);delete flow.browser;delete flow.device;delete flow.prompt;this.publish(flow);
    }
  }
  prompt(flow,value){
    if(!flow.active||flow.controller.signal.aborted||value.signal?.aborted)return Promise.reject(new Error('Login cancelled'));
    const signal=AbortSignal.any([flow.controller.signal,value.signal].filter(Boolean));
    const id=`${flow.id}:prompt:${randomUUID()}`;
    const safe={type:value.type,message:value.message,placeholder:value.placeholder,options:value.options};
    return new Promise((resolve,reject)=>{
      const entry={id,flow,value:safe,signal,resolve,reject};
      entry.abort=()=>{this.removePrompt(entry);reject(new Error('Login cancelled'));};
      this.prompts.set(id,entry);flow.prompt=entry;flow.state='prompt';signal.addEventListener('abort',entry.abort,{once:true});this.publish(flow);
    });
  }
  removePrompt(entry){this.prompts.delete(entry.id);entry.signal.removeEventListener('abort',entry.abort);if(entry.flow.prompt===entry)delete entry.flow.prompt;}
  clearPrompts(flow){for(const entry of [...this.prompts.values()])if(entry.flow===flow){this.removePrompt(entry);entry.reject(new Error('Login finished'));}}
  answer({promptId,value,requestId}){
    const entry=this.prompts.get(promptId);
    if(!entry||!entry.flow.active){this.rejectCommand({requestId},'登录提示已失效，请继续当前登录或重新开始');return;}
    if(requestId&&entry.flow.id!==requestId){this.rejectCommand({requestId},'登录提示与当前请求不匹配');return;}
    value=String(value??'').trim();
    if(!value||value.length>8192){this.rejectCommand({requestId:entry.flow.id},'请输入有效的登录信息');return;}
    if(entry.value.type==='select'&&!entry.value.options?.some(option=>option.id===value)){this.rejectCommand({requestId:entry.flow.id},'请选择有效的登录方式');return;}
    this.removePrompt(entry);entry.flow.state='waiting';entry.flow.message='正在完成登录…';this.publish(entry.flow);entry.resolve(value);
  }
  notify(flow,event){
    if(!flow.active||flow.controller.signal.aborted)return;
    if(event.type==='auth_url'){
      const url=webURL(event.url);if(!url){flow.failure='供应商返回了无效的登录地址';flow.controller.abort();return;}
      flow.browser={url,instructions:event.instructions||''};
    }else if(event.type==='device_code'){
      const verificationUri=webURL(event.verificationUri);if(!verificationUri){flow.failure='供应商返回了无效的设备码页面';flow.controller.abort();return;}
      flow.device={verificationUri,userCode:event.userCode,expiresInSeconds:event.expiresInSeconds};
    }else if(event.type==='progress')flow.message=event.message||'登录中…';
    flow.state=flow.prompt?'prompt':'waiting';this.publish(flow);
  }
  cancel(id){const flow=this.flows.get(id);if(flow?.active){flow.controller.abort();this.clearPrompts(flow);flow.state='cancelling';flow.message='正在取消登录…';this.publish(flow);}}
}
