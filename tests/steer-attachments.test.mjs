import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const source=readFileSync(new URL('../runtime/agent.mjs',import.meta.url),'utf8');
const body=source.slice(source.indexOf("  if(command.type==='steer') {"),source.indexOf("  if(command.type==='stop')"));
const AsyncFunction=Object.getPrototypeOf(async function(){}).constructor;
async function run(command,{vision=true,disposition='queued',busy=true}={}){
 const events=[],calls=[];
 const session={sessionId:'s',steer:async(...args)=>{calls.push(args);return disposition;}};
 await new AsyncFunction('command','session','busy','stopRequested','changingSession','activeModel','attachmentPrompt','cameraPromptSuffix','emit',body)({type:'steer',id:'r',sessionId:'s',...command},session,busy,false,false,{input:vision?['text','image']:['text']},files=>files?.length?'\nFILE:'+files[0].excerpt:'',frame=>frame?'\nCAMERA':'',e=>events.push(e));
 return {events,calls};
}
test('steer submits text, images, file excerpts and frozen camera metadata together',async()=>{
 const {events,calls}=await run({text:'look',image:'png',images:[{data:'jpg',mime:'image/jpeg',source:'camera',frameId:'f'}],files:[{excerpt:'document'}],cameraFrame:{}});
 assert.equal(events[0].accepted,true);assert.equal(calls[0][0],'look\nFILE:document\nCAMERA');assert.equal(calls[0][1].length,2);assert.equal(calls[0][1][1]._zfoldCameraFrame,'f');
});
test('attachment-only image and document messages can steer',async()=>{
 for(const command of [{images:[{data:'jpg'}]},{files:[{excerpt:'data'}]}])assert.equal((await run(command)).events[0].accepted,true);
});
test('nonvision rejects image before SDK submission',async()=>{const r=await run({image:'png'},{vision:false});assert.equal(r.events[0].accepted,false);assert.equal(r.calls.length,0);});
test('ended task, empty message and handled input are not reported inserted',async()=>{
 for(const r of [await run({text:'x'},{busy:false}),await run({}),await run({text:'x'},{disposition:'handled'})])assert.equal(r.events[0].accepted,false);
});
test('queue removal is receipt-gated and normal drain waits for insertion',()=>{
 const java=readFileSync(new URL('../android/src/dev/zfold/assistant/PromptDispatcher.java',import.meta.url),'utf8');
 assert.match(java,/if\(accepted&&id!=null\)/);assert.match(java,/!queue.isEmpty\(\)&&insertingQueue.isEmpty\(\)/);assert.match(java,/new JSONObject\(item.toString\(\)\)/);
});
