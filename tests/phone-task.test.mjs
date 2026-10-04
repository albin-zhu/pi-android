import {test} from 'node:test';
import assert from 'node:assert/strict';
import {actionCatalog,runPhoneTask,verifyOutcome} from '../runtime/phone-task.mjs';
const pkg='test.phone';
const node=(extra={})=>({nodeId:'1:0',path:'0.1',resourceId:'test.phone:id/switch',package:pkg,text:'蓝牙',description:'',class:'Switch',enabled:true,password:false,checkable:true,checked:false,actions:[16],bounds:[0,0,200,80],...extra});
const screen=(nodes=[node()],generation=1)=>({generation,packageName:pkg,width:400,height:900,nodes});
const task={goal:'Enable Bluetooth',allowedPackages:[pkg],expected:[{kind:'checked',packageName:pkg,resourceId:'test.phone:id/switch',value:true}],maxSteps:5};
const decision=action=>({action,choice:'test',confidence:0.95,margin:0.9,model:'fixture',latencyMs:1});
const choose=({screen:state})=>decision(state.nodes[0].checked?{kind:'done',description:'Done'}:Object.values(actionCatalog(state,task,[])).find(a=>a.kind==='tap'));

test('catalog only exposes advertised enabled controls in allowed apps, without passwords',()=>{
  const value=actionCatalog(screen([node(),node({nodeId:'disabled',enabled:false}),node({nodeId:'password',password:true}),node({nodeId:'foreign',package:'other.app'})]),task,[]);
  assert.equal(Object.values(value).filter(a=>a.kind==='tap').length,1);
  assert.ok(!Object.values(value).some(a=>a.kind==='input'));
  assert.equal(Object.values(actionCatalog({...screen(),packageName:'other.app'},task,[])).filter(a=>a.target).length,0);
});
test('task verifies the checked state after a real accepted action, not model DONE alone',async()=>{
  let checked=false;const calls=[],progress=[];
  const result=await runPhoneTask({task,decide:choose,onProgress:r=>progress.push(r.status),android:async(name,args)=>{
    calls.push(name);if(name==='app_list')return {apps:[]};if(name==='screen_read')return screen([node({checked})]);
    assert.equal(args.action,'tap');checked=true;return {performed:true};
  }});
  assert.equal(result.status,'verified');assert.equal(result.verified,true);assert.equal(calls.filter(n=>n==='ui_act').length,1);assert.ok(progress.includes('running'));
  assert.equal(verifyOutcome(screen(),task.expected).verified,false);
  const done=await runPhoneTask({task,decide:async()=>decision({kind:'done'}),android:async name=>name==='app_list'?{apps:[]}:screen()});
  assert.equal(done.status,'unverified');
});
test('low confidence never mutates and stale snapshots re-observe before a new choice',async()=>{
  const calls=[];
  const result=await runPhoneTask({task,decide:async state=>({...choose(state),confidence:0.4}),android:async name=>{calls.push(name);return name==='app_list'?{apps:[]}:screen();}});
  assert.equal(result.reason,'low_confidence');assert.ok(!calls.includes('ui_act'));
  let attempts=0,reads=0;
  const stale=await runPhoneTask({task,decide:choose,android:async name=>{
    if(name==='app_list')return {apps:[]};if(name==='screen_read'){reads++;return screen();}attempts++;return {performed:false,code:'stale_snapshot'};
  }});
  assert.equal(stale.reason,'unstable_screen');assert.equal(attempts,3);assert.ok(reads>=3);
});
test('an unknown mutation result is not retried, and an unverified input stops',async()=>{
  let mutations=0;
  const unknown=await runPhoneTask({task,decide:choose,android:async name=>{if(name==='app_list')return {apps:[]};if(name==='screen_read')return screen();mutations++;throw new Error('timeout');}});
  assert.equal(unknown.reason,'action_result_unknown');assert.equal(mutations,1);assert.equal(unknown.steps[0].status,'unknown');
  const field=node({editable:true,actions:[2097152],text:'old'});
  const input=await runPhoneTask({task:{...task,textValues:['new']},decide:async()=>decision({kind:'input',target:field,text:'new'}),android:async name=>name==='app_list'?{apps:[]}:name==='screen_read'?screen([field]):{performed:true}});
  assert.equal(input.reason,'input_not_verified');
});
test('stop during Jev decision never dispatches a native action',async()=>{
  const controller=new AbortController();let mutated=false;
  const result=await runPhoneTask({task,signal:controller.signal,decide:async state=>{controller.abort();return choose(state);},android:async name=>{if(name==='app_list')return {apps:[]};if(name==='screen_read')return screen();mutated=true;}});
  assert.equal(result.status,'cancelled');assert.equal(mutated,false);
});

test('already satisfied explicit conditions finish without another model call or toggle',async()=>{
  let decisions=0,mutations=0;
  const result=await runPhoneTask({task,decide:async state=>{decisions++;return choose(state);},android:async name=>{if(name==='app_list')return {apps:[]};if(name==='screen_read')return screen([node({checked:true})]);mutations++;}});
  assert.equal(result.verified,true);assert.equal(decisions,0);assert.equal(mutations,0);
});

test('direct text input has no competing focus tap, and satisfied switches cannot be undone',()=>{
  const state=screen([node({checked:true}),node({nodeId:'1:1',path:'0.2',resourceId:'test.phone:id/input',text:'',description:'备注',editable:true,checkable:false,actions:[16,2097152]})]);
  const actions=Object.values(actionCatalog(state,{...task,textValues:['你好，Jev']},[]));
  assert.equal(actions.filter(a=>a.kind==='tap').length,0);
  assert.equal(actions.filter(a=>a.kind==='input').length,1);
});

test('empty trees hand off without wasting a Jev request',async()=>{
  let decisions=0;
  const result=await runPhoneTask({task,decide:async()=>{decisions++;throw new Error('must not ask');},android:async name=>name==='app_list'?{apps:[]}:screen([])});
  assert.equal(result.reason,'empty_accessibility_tree');assert.equal(decisions,0);
});
