import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const runtime=readFileSync(new URL('../runtime/agent.mjs',import.meta.url),'utf8');
const picker=readFileSync(new URL('../android/src/dev/zfold/assistant/ThinkingPicker.java',import.meta.url),'utf8');
const main=readFileSync(new URL('../android/src/dev/zfold/assistant/MainActivity.java',import.meta.url),'utf8');
const model=readFileSync(new URL('../android/src/dev/zfold/assistant/ModelPicker.java',import.meta.url),'utf8');
const state=readFileSync(new URL('../android/src/dev/zfold/assistant/PiRuntime.java',import.meta.url),'utf8');
const AsyncFunction=Object.getPrototypeOf(async function(){}).constructor;
const body=runtime.slice(runtime.indexOf("  if(command.type==='thinking_level'){"),runtime.indexOf("  if(command.type==='auth_manage')"));
async function run(command,{supported=true,available=['off','low','high']}={}){
 const events=[],calls=[];let catalog=0;
 await new AsyncFunction('command','session','config','emitModelsCatalog','emit',body)({type:'thinking_level',sessionId:'s',id:'r',level:'high',...command},{sessionId:'s',getAvailableThinkingLevels:()=>available,supportsThinking:()=>supported,setThinkingLevel:l=>calls.push(l)},{},()=>catalog++,e=>events.push(e));
 return {events,calls,catalog};
}
test('supported choice invokes SDK and emits receipt plus fresh state',async()=>{const r=await run({});assert.deepEqual(r.calls,['high']);assert.equal(r.catalog,1);assert.equal(r.events[0].ok,true);});
test('invalid level, stale session and unsupported model never change SDK',async()=>{
 for(const r of [await run({level:'max'}),await run({sessionId:'old'}),await run({},{supported:false,available:['off']}),await run({},{available:[]})]){assert.equal(r.calls.length,0);assert.equal(r.events[0].ok,false);}
});
test('control is immediately before send and no longer buried in model picker',()=>{
 assert.match(main,/toolbar.addView\(thinkingButton/);assert.ok(main.indexOf('toolbar.addView(thinkingButton')<main.indexOf('toolbar.addView(sendButton'));
 assert.match(main,/case "models_catalog":updateThinkingControl\(\)/);assert.doesNotMatch(main,/handler.post\(\(\)->ModelPicker.show/);
 assert.doesNotMatch(model,/thinking_level|思考强度/);
});
test('picker offers real levels, current selection, cancellation and session guard',()=>{
 assert.match(picker,/setSingleChoiceItems\(labels,checked/);assert.match(picker,/state.optBoolean\("supported"\)/);assert.match(picker,/"sessionId",sessionId/);assert.match(picker,/setNegativeButton\("取消"/);
});
test('catalog and native state store SDK-confirmed level and capabilities',()=>{
 assert.match(runtime,/thinking:session\?\{level:session.thinkingLevel,available:session.getAvailableThinkingLevels\(\),supported:session.supportsThinking\(\)\}/);
 assert.match(state,/thinkingState=event.optJSONObject\("thinking"\)/);
});
