import {test} from 'node:test';
import assert from 'node:assert/strict';
import {NativeToolBridge} from '../runtime/native-tools.mjs';

test('native heartbeat permits a 90-second job without imposing a 30-second execution limit',async t=>{
  t.mock.timers.enable({apis:['setTimeout']});const sent=[];
  const bridge=new NativeToolBridge(message=>sent.push(message));
  const result=bridge.call('runtime_exec',{timeoutMs:120000});const {id}=sent[0];
  for(let i=0;i<9;i++){t.mock.timers.tick(10000);bridge.receive({type:'tool_heartbeat',id});}
  assert.equal(sent.length,1);
  bridge.receive({type:'tool_result',id,result:{exitCode:7}});
  assert.deepEqual(await result,{exitCode:7});assert.equal(bridge.pending.size,0);
});

test('cancel reaches the execution side and waits for its terminal receipt',async t=>{
  t.mock.timers.enable({apis:['setTimeout']});const sent=[];
  const bridge=new NativeToolBridge(message=>sent.push(message));const controller=new AbortController();
  const result=bridge.call('runtime_exec',{}, {signal:controller.signal}).catch(error=>error);
  controller.abort();const id=sent[0].id;
  assert.deepEqual(sent[1],{type:'tool_cancel',id,reason:'cancelled'});
  assert.equal(bridge.pending.size,1);
  bridge.receive({type:'tool_result',id,error:'操作已停止'});
  assert.match((await result).message,/已停止/);assert.equal(bridge.pending.size,0);
  bridge.receive({type:'tool_result',id,result:{late:true}});
});

test('missing liveness requests cancellation and reports unconfirmed execution, not success',async t=>{
  t.mock.timers.enable({apis:['setTimeout']});const sent=[];
  const bridge=new NativeToolBridge(message=>sent.push(message));
  const result=bridge.call('pkg',{action:'install'}).catch(error=>error);
  t.mock.timers.tick(30000);assert.equal(sent[1].reason,'unresponsive');
  bridge.receive({type:'tool_heartbeat',id:sent[0].id});
  t.mock.timers.tick(5000);assert.match((await result).message,/尚未.*确认/);
  assert.equal(sent.at(-1).type,'tool_uncertain');
  assert.equal(bridge.pending.size,0);
});

test('completion racing with cancellation preserves the actual action result',async t=>{
  t.mock.timers.enable({apis:['setTimeout']});const sent=[];
  const bridge=new NativeToolBridge(message=>sent.push(message));const controller=new AbortController();
  const result=bridge.call('ui_tap',{}, {signal:controller.signal});controller.abort();
  bridge.receive({type:'tool_result',id:sent[0].id,result:{performed:true}});
  assert.deepEqual(await result,{performed:true});
  await assert.rejects(bridge.call('runtime_exec',{}, {signal:controller.signal}),/已停止/);
  assert.equal(sent.length,2);
});
