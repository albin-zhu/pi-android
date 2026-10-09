import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import {jsonLines} from '../runtime/protocol.mjs';
import {stopRuntimeChild} from './runtime-cleanup.mjs';
for(const cancel of [false,true])test('bundled Android host subagent '+(cancel?'stop propagation':'result/progress/usage routing'),{timeout:30000},async()=>{
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'subagent-bridge-'));const runtime=path.join(root,'runtime');
 await fs.cp(new URL('../android/assets/runtime/',import.meta.url),runtime,{recursive:true,filter:s=>!s.includes('subagent-sessions')});
 let childRequests=0,parentRequests=0;const tools=[],receipts=[];let stderr='',child;
 const server=http.createServer(async(req,res)=>{
  let data='';for await(const c of req)data+=c;const body=JSON.parse(data);const isParent=(body.tools||[]).some(t=>t.function?.name==='subagent');
  res.writeHead(200,{'content-type':'text/event-stream'});
  const delta=d=>res.write('data: '+JSON.stringify({id:'bridge',choices:[{index:0,delta:d,finish_reason:null}]})+'\n\n');
  if(!isParent){
   childRequests++;assert.deepEqual((body.tools||[]).map(t=>t.function.name).sort(),['find','grep','ls','read']);
   assert.equal(body.messages.filter(m=>m.role==='user').length,1);
   if(cancel){res.write(': wait\n\n');child.stdin.write(JSON.stringify({type:'stop'})+'\n');return;}
   delta({role:'assistant',content:'CHILD_RESULT_ONLY'});
  }else{
   parentRequests++;
   if(parentRequests===1){delta({role:'assistant',tool_calls:[{index:0,id:'delegate',type:'function',function:{name:'subagent',arguments:JSON.stringify({agent:'scout',task:'summarize only assigned data'})}}]});res.end('data: '+JSON.stringify({id:'bridge',choices:[{index:0,delta:{},finish_reason:'tool_calls'}]})+'\n\ndata: [DONE]\n\n');return;}
   assert.match(JSON.stringify(body.messages.filter(m=>m.role==='tool')),/CHILD_RESULT_ONLY/);delta({role:'assistant',content:'PARENT_DONE'});
  }
  res.end('data: '+JSON.stringify({id:'bridge',choices:[{index:0,delta:{},finish_reason:'stop'}],usage:{prompt_tokens:5,completion_tokens:3,total_tokens:8}})+'\n\ndata: [DONE]\n\n');
 });await new Promise(r=>server.listen(0,'127.0.0.1',r));
 await fs.mkdir(path.join(root,'data'));await fs.mkdir(path.join(root,'home'));
 child=spawn(process.execPath,[path.join(runtime,'agent.cjs'),'0','1',path.join(root,'data')],{stdio:['pipe','pipe','pipe'],env:{...process.env,HOME:path.join(root,'home'),PI_CODING_AGENT_DIR:path.join(root,'pi'),ZFOLD_CREDENTIALS_DIR:path.join(root,'credentials')}});
 const send=v=>child.stdin.write(JSON.stringify(v)+'\n');child.stderr.on('data',b=>stderr+=b);
 try{
  await new Promise((resolve,reject)=>{
   child.on('exit',code=>reject(new Error('exit '+code+': '+stderr)));child.stdout.setEncoding('utf8');child.stdout.on('data',jsonLines(e=>{
    if(e.type==='ready')send({type:'configure',config:{provider:'fixture',baseUrl:`http://127.0.0.1:${server.address().port}/v1`,api:'openai-completions',apiKey:'fixture',model:'bridge-fixture'}});
    if(e.type==='configured')send({type:'prompt',id:'parent-turn',text:'delegate'});
    if(e.type==='tool_request'){if(e.name==='runtime_environment')send({type:'tool_result',id:e.id,result:{HOME:path.join(root,'home'),TMPDIR:root,PATH:process.env.PATH}});else reject(new Error('Unexpected native tool: '+e.name));}
    if(e.type==='tool_execution'&&e.name==='subagent')tools.push(e);
    if(e.type==='usage_record')receipts.push(e);
    if(e.type==='turn_end')resolve();if(e.type==='error')reject(new Error(e.message));
   },reject));
  });
  assert.equal(childRequests,1);assert.ok(tools.length>=2);assert.ok(tools.some(t=>t.status==='running'));
  if(cancel){assert.equal(parentRequests,1);assert.equal(tools.at(-1).status,'cancelled');}
  else{assert.equal(parentRequests,2);assert.equal(tools.at(-1).status,'success');assert.match(tools.at(-1).output,/CHILD_RESULT_ONLY/);assert.ok(receipts.some(r=>r.id.startsWith('subagent:')));}
 }finally{await stopRuntimeChild(child);server.closeAllConnections();await new Promise(r=>server.close(r));await fs.rm(root,{recursive:true,force:true});}
});
