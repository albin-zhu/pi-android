import fs from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';

export function redact(value,secret=''){
  let text=String(value??'');if(secret)text=text.split(secret).join('[redacted]');
  return text.replace(/Bearer\s+\S+/gi,'Bearer [redacted]').replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g,'[token]').replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi,'[email]').slice(0,1800);
}
export async function probeImage(token,{request=fetch,outputDir,signal,kind='image'}={}){
  const operationId=randomUUID();let response;
  const payload=kind==='video'?{model:'grok-imagine-video-1.5',prompt:'A simple blue circle slowly moving on a plain white background, no text.',duration:6,aspect_ratio:'1:1',resolution:'480p'}:{model:'grok-imagine-image-2.0',prompt:'A simple blue circle on a plain white background, minimal flat icon, no text.',n:1,quality:'low',response_format:'b64_json'};
  try{response=await request(kind==='video'?'https://api.x.ai/v1/videos/generations':'https://api.x.ai/v1/images/generations',{method:'POST',redirect:'error',signal,headers:{'content-type':'application/json',authorization:'Bearer '+token},body:JSON.stringify(payload)});}
  catch{return {status:'unverified',operationId,reason:'request_failed_or_timed_out',resubmitted:false};}
  let raw='';try{
    const reader=response.body.getReader();const chunks=[];let size=0;while(true){const {value,done}=await reader.read();if(done)break;size+=value.length;if(size>32*1024*1024){await reader.cancel();throw new Error('large');}chunks.push(value);}raw=Buffer.concat(chunks).toString('utf8');
  }catch{return {status:'unverified',httpStatus:response.status,operationId,reason:'response_unavailable',resubmitted:false};}
  let body;try{body=JSON.parse(raw);}catch{body=null;}
  if(!response.ok){const error=body?.error;return {status:'rejected',httpStatus:response.status,operationId,code:redact(error?.code||body?.code||'',token),message:redact(typeof error==='string'?error:error?.message||body?.message||raw,token),resubmitted:false};}
  if(kind==='video')return {status:typeof body?.request_id==='string'?'submitted':'unverified',httpStatus:response.status,operationId,requestId:redact(body?.request_id||'',token),billing:'unknown',resubmitted:false};
  const data=body?.data?.[0]?.b64_json;
  if(typeof data!=='string')return {status:'unverified',httpStatus:response.status,operationId,reason:'unexpected_success_response',billing:'unknown'};
  const bytes=Buffer.from(data,'base64');let ext;if(bytes.subarray(0,8).equals(Buffer.from('89504e470d0a1a0a','hex')))ext='png';else if(bytes[0]===255&&bytes[1]===216&&bytes[2]===255)ext='jpg';
  if(!ext||!bytes.length||bytes.length>24*1024*1024)return {status:'unverified',httpStatus:response.status,operationId,reason:'invalid_image_payload',billing:'unknown'};
  fs.mkdirSync(outputDir,{recursive:true});const savedPath=path.join(outputDir,operationId+'.'+ext);fs.writeFileSync(savedPath,bytes,{flag:'wx'});
  return {status:'completed',httpStatus:response.status,operationId,savedPath,bytes:bytes.length,billing:'unknown'};
}
async function main(){
  const mode=process.argv[2];if(!['--status','--probe-image','--probe-video'].includes(mode))throw new Error('usage');
  delete process.env.XAI_API_KEY;delete process.env.GROK_DEPLOYMENT_KEY;
  const sdk='/data/user/0/dev.zfold.assistant/files/runtime/dev-ci-local/src/pi-android/node_modules/@earendil-works/pi-coding-agent/dist/index.js';
  const {ModelRuntime}=await import(pathToFileURL(sdk).href);
  const cache='/data/user/0/dev.zfold.assistant/files/runtime/experiments/grok-oauth-probe';fs.mkdirSync(cache,{recursive:true});
  const runtime=await ModelRuntime.create({authPath:'/data/user/0/dev.zfold.assistant/no_backup/credentials/auth.json',modelsPath:null,modelsStorePath:path.join(cache,'catalog.json'),allowModelNetwork:false,refreshOnCreate:false});
  const info=(await runtime.listCredentials()).find(c=>c.providerId==='xai');
  if(info?.type!=='oauth'){console.log(JSON.stringify({status:'blocked',reason:'xai_oauth_required'}));return;}
  if(mode==='--status'){console.log(JSON.stringify({status:'oauth_stored',mediaPermission:'unverified',billing:'unknown'}));return;}
  const signal=AbortSignal.timeout(60000);const auth=await runtime.getAuth('xai',{env:{},signal});const token=auth?.auth?.apiKey;
  if(!token){console.log(JSON.stringify({status:'blocked',reason:'oauth_unavailable'}));return;}
  console.log(JSON.stringify(await probeImage(token,{outputDir:'/storage/emulated/0/pi/workspace/generated_images',signal,kind:mode==='--probe-video'?'video':'image'})));
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href)main().catch(()=>{console.log(JSON.stringify({status:'blocked',reason:'oauth_or_local_runtime_error',resubmitted:false}));process.exitCode=1;});
