import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,rm,readFile} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {jsonLines} from '../runtime/protocol.mjs';
import {stopRuntimeChild} from './runtime-cleanup.mjs';

test('bundled DeepSeek and Zhipu providers add, authenticate and expose official models', {timeout:20000}, async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'zfold-builtin-auth-'));
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe']});
  const events=[];let stderr='';
  child.stderr.on('data',part=>stderr+=part);
  child.stdout.setEncoding('utf8');
  child.stdout.on('data',jsonLines(event=>events.push(event),error=>events.push({type:'parse_error',message:error.message}),8*1024*1024));
  const send=value=>child.stdin.write(JSON.stringify(value)+'\n');
  async function until(predicate){
    const deadline=Date.now()+6000;
    while(Date.now()<deadline){const match=events.find(predicate);if(match)return match;if(child.exitCode!==null)throw new Error(stderr);await new Promise(resolve=>setTimeout(resolve,20));}
    throw new Error('Timed out waiting for provider runtime event: '+stderr);
  }
  try{
    const initial=await until(event=>event.type==='models_catalog');
    for(const id of ['deepseek','zai','zai-coding-cn']){
      assert.ok(initial.availableProviders.find(provider=>provider.id===id)?.apiKeyLogin,id+' must support key login');
      send({type:'auth_manage',action:'add',provider:id});
      const added=await until(event=>event.type==='auth_state'&&event.added===id);
      assert.ok(added.providers.some(provider=>provider.id===id));
      send({type:'auth_manage',action:'login',provider:id,authType:'api_key',requestId:id});
      const prompt=await until(event=>event.requestId===id&&event.state==='prompt');
      assert.equal(prompt.prompt.type,'secret');
      send({type:'auth_manage',action:'prompt',requestId:id,promptId:prompt.promptId,value:'fixture-'+id+'-key'});
      await until(event=>event.requestId===id&&event.state==='success');
      const catalog=await until(event=>event.type==='models_catalog'&&event.providers.some(provider=>provider.id===id&&provider.configured));
      assert.ok(catalog.providers.find(provider=>provider.id===id).models.length>0,id+' official models must be available');
    }
    const stored=JSON.parse(await readFile(path.join(root,'.pi','agent','auth.json'),'utf8'));
    for(const id of ['deepseek','zai','zai-coding-cn']){
      assert.ok(JSON.stringify(stored).includes('fixture-'+id+'-key'));
      assert.ok(!JSON.stringify(events).includes('fixture-'+id+'-key'),'key must not be emitted');
    }
  }finally{await stopRuntimeChild(child);await rm(root,{recursive:true,force:true,maxRetries:5,retryDelay:40});}
});
