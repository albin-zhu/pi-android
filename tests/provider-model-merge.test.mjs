import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,mkdir,writeFile,rm} from 'node:fs/promises';
import os from 'node:os';import path from 'node:path';
import {ModelRuntime} from '@earendil-works/pi-coding-agent';
import {jsonLines} from '../runtime/protocol.mjs';
import {stopRuntimeChild} from './runtime-cleanup.mjs';

test('adding a built-in provider model does not replace its built-in models during configure', {timeout:15000},async()=>{
 const root=await mkdtemp(path.join(os.tmpdir(),'zfold-extra-model-'));
 const credentials=path.join(root,'.pi','agent');await mkdir(credentials,{recursive:true});
 const baseline=await ModelRuntime.create({authPath:path.join(credentials,'auth.json'),modelsPath:null,allowModelNetwork:false});
 const builtin=baseline.getModels('zai')[0];assert.ok(builtin);
 await writeFile(path.join(credentials,'models.json'),JSON.stringify({providers:{zai:{apiKey:'fixture-zai-key',baseUrl:'http://127.0.0.1:1/v4',models:[{id:'glm-extra-fixture',name:'Extra fixture',api:builtin.api,reasoning:false,input:['text'],contextWindow:4096,maxTokens:1024,cost:{input:0,output:0,cacheRead:0,cacheWrite:0}}]}}}));
 const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe'],env:{...process.env,ZFOLD_CREDENTIALS_DIR:credentials,PI_CODING_AGENT_DIR:path.join(root,'pi')}});
 const events=[];let timer;
 const result=new Promise((resolve,reject)=>{
  timer=setTimeout(()=>reject(Error('Configuration timed out')),7000);
  child.stdout.setEncoding('utf8');child.stdout.on('data',jsonLines(e=>{
   events.push(e);
   if(e.type==='ready')child.stdin.write(JSON.stringify({type:'configure',config:{provider:'zai',model:builtin.id}})+'\n');
   if(e.type==='configured')resolve(e);
   if(e.type==='error')reject(Error(e.message));
  },reject,8*1024*1024));
 });
 try{const configured=await result;assert.equal(configured.model,builtin.id);assert.ok(!JSON.stringify(events).includes('fixture-zai-key'));}
 finally{clearTimeout(timer);await stopRuntimeChild(child);await rm(root,{recursive:true,force:true,maxRetries:5,retryDelay:40});}
});
