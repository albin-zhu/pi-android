import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Type} from 'typebox';
import {createPtyTool} from '../runtime/pty.mjs';

test('Agent PTY entry resolves the default shell and forwards cancellation',async()=>{
  const requests=[];
  const tool=createPtyTool({Type,compatExec:true,defaultCwd:()=>'/projects/current',resolveRuntimeCommand:name=>`/runtime/pkg/usr/bin/${name}`,
    android:async(...args)=>{requests.push(args);return {id:4};}});
  const controller=new AbortController();
  const result=await tool.execute('open',{},controller.signal);
  assert.deepEqual(result.details,{id:4});
  assert.deepEqual(JSON.parse(result.content[0].text),{id:4},'Pi receives a proper tool result');
  assert.deepEqual(requests[0],['pty',{action:'open',argv:['/runtime/pkg/usr/bin/bash','--noprofile','--norc'],cwd:'/projects/current'},controller.signal]);
  const argv=['bash','-c','echo ok'];
  await tool.execute('explicit',{action:'open',argv},controller.signal);
  assert.equal(argv[0],'bash','Caller arguments remain usable for retries');
  await tool.execute('close',{action:'close',id:4},controller.signal);
  assert.equal(requests[2][2],controller.signal);
});

test('unsupported PTY backend rejects before native dispatch',async()=>{
  const tool=createPtyTool({Type,compatExec:false,resolveRuntimeCommand:()=>assert.fail('resolve'),android:()=>assert.fail('dispatch')});
  await assert.rejects(tool.execute('open',{}),/compat28/);
});
