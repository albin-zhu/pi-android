import {test} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {ModelRuntime} from '@earendil-works/pi-coding-agent';
import {createSubagentRunner} from '../runtime/subagent-session.mjs';
const rolesDirectory=new URL('../runtime/vendor/pi-subagent-v1.1.0/agents/',import.meta.url).pathname;
async function fixture(fn){
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'orange-subagent-'));const bodies=[],usage=[];
 const server=http.createServer(async(req,res)=>{let body='';for await(const c of req)body+=c;const b=JSON.parse(body);bodies.push(b);const task=b.messages.at(-1).content;const text=typeof task==='string'?task:JSON.stringify(task);
  if(text.includes('WAIT_FOREVER')){res.writeHead(200,{'content-type':'text/event-stream'});res.write(': keepalive\n\n');return;}
  res.writeHead(200,{'content-type':'text/event-stream'});res.end('data: '+JSON.stringify({id:'child',choices:[{index:0,delta:{role:'assistant',content:'answer '+text},finish_reason:null}]})+'\n\ndata: '+JSON.stringify({id:'child',choices:[{index:0,delta:{},finish_reason:'stop'}],usage:{prompt_tokens:5,completion_tokens:3,total_tokens:8}})+'\n\ndata: [DONE]\n\n');
 });await new Promise(r=>server.listen(0,'127.0.0.1',r));
 try{
  const runtime=await ModelRuntime.create({authPath:path.join(root,'auth.json'),modelsPath:null,modelsStorePath:path.join(root,'catalog.json'),allowModelNetwork:false,refreshOnCreate:false});
  runtime.registerProvider('fixture',{baseUrl:`http://127.0.0.1:${server.address().port}/v1`,api:'openai-completions',apiKey:'fixture-only',models:[{id:'child-fixture',name:'child-fixture',reasoning:false,input:['text'],contextWindow:32768,maxTokens:1024,cost:{input:0,output:0,cacheRead:0,cacheWrite:0}}]});
  const parent={model:runtime.getModel('fixture','child-fixture'),modelRuntime:runtime,thinkingLevel:'off',sessionId:'parent-fixture'};
  const run=createSubagentRunner({directory:path.join(root,'children'),rolesDirectory,makeTools:()=>[],onUsage:v=>usage.push(v)});
  await fn({run,root,parent,bodies,usage});
 }finally{server.closeAllConnections();await new Promise(r=>server.close(r));await fs.rm(root,{recursive:true,force:true});}
}
test('real SDK child runs with isolated prompt, model and persisted result',{timeout:20000},async()=>fixture(async({run,root,parent,bodies,usage})=>{
 const progress=[];const answer=await run({agent:'scout',task:'ONLY_CHILD',cwd:root,parent,onProgress:p=>progress.push(p)});
 assert.equal(answer.failed,false);assert.match(answer.text,/ONLY_CHILD/);assert.equal(answer.parentSessionId,'parent-fixture');assert.equal(bodies.length,1);assert.equal(bodies[0].model,'child-fixture');assert.equal(bodies[0].messages.filter(m=>m.role==='user').length,1);assert.equal((bodies[0].tools||[]).length,0);assert.ok(progress.length>0);assert.ok(usage.length>0);assert.ok(answer.sessionId);assert.equal((await fs.readdir(path.join(root,'children'))).length,1);
}));
test('real SDK parallel children have distinct histories and session IDs',{timeout:20000},async()=>fixture(async({run,root,parent,bodies})=>{
 const results=await Promise.all(['ALPHA','BETA'].map(task=>run({agent:'planner',task,cwd:root,parent})));assert.notEqual(results[0].sessionId,results[1].sessionId);assert.match(results[0].text,/ALPHA/);assert.doesNotMatch(results[0].text,/BETA/);assert.equal(bodies.length,2);
}));
test('real SDK streaming request is aborted by parent signal',{timeout:20000},async()=>fixture(async({run,root,parent,bodies})=>{
 const c=new AbortController();const pending=run({agent:'reviewer',task:'WAIT_FOREVER',cwd:root,parent,signal:c.signal});
 const deadline=Date.now()+5000;while(!bodies.length&&Date.now()<deadline)await new Promise(r=>setTimeout(r,10));assert.equal(bodies.length,1);c.abort();const answer=await pending;assert.equal(answer.failed,true);assert.equal(bodies.length,1);
}));
