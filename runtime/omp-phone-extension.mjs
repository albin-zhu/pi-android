import {createPhoneToolDefinitions} from './phone-tool-definitions.mjs';
import {PhoneToolSession} from './phone-tools.mjs';

const readOnly=new Set(['phone_capabilities','device_info','contact_search','screen_read','screen_windows','app_list']);
/** omp 18.10 injects typebox.Type and a five-argument tool execute API. No SDK import. */
export default function phoneExtension(api){
  const Type=api.typebox?.Type;
  if(!Type?.Object)throw new Error('Android phone extension requires omp 18.10 compatible api.typebox.Type');
  let phone;
  const close=()=>{phone?.close();phone=undefined;};
  // Connection and app-target guard belong to one user/agent run, not its individual LLM steps.
  api.on('agent_start',close);
  api.on('agent_end',close);
  api.on('session_switch',close);
  api.on('session_shutdown',close);
  for(const [name,label,description,parameters] of createPhoneToolDefinitions(Type)){
    api.registerTool({
      name,label,parameters,loadMode:'essential',approval:readOnly.has(name)?'read':'write',
      description:description+' Android 主机执行真实手机操作；默认除 device_info/phone_capabilities/app_list 外逐次确认，用户可在手机能力中显式开启独立客户端“始终允许”；系统权限仍需授权。busy 不排队，不可自动重试。'+(name==='open'?' 文件输入是 guest 路径，必须设置 ZFOLD_PHONE_PATH_MAP 的明确 bind 映射；URL 不需要文件映射。':name==='camera_capture'||name==='screen_capture'?' 返回的未映射文件路径仅属于 Android 主机，不代表 guest 可读取。':''),
      async execute(_id,args,signal,_onUpdate,ctx){
        phone??=new PhoneToolSession();
        return phone.call(name,args,{signal,cwd:ctx.cwd,onHalt:()=>ctx.abort()});
      }
    });
  }
}
