import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import {spawn} from 'node:child_process';
import {jsonLines} from '../runtime/protocol.mjs';

function boot(root){
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe'],env:{...process.env,PI_CODING_AGENT_DIR:path.join(root,'pi')}});
  const events=[],waiters=[];let stderr='';child.stderr.on('data',data=>stderr+=data);
  child.stdout.setEncoding('utf8');child.stdout.on('data',jsonLines(event=>{const i=waiters.findIndex(w=>w.type===event.type&&w.match(event));if(i>=0){const w=waiters.splice(i,1)[0];clearTimeout(w.timer);w.resolve(event);}else events.push(event);},()=>{}));
  child.on('exit',()=>{for(const w of waiters.splice(0)){clearTimeout(w.timer);w.reject(new Error('Runtime exited: '+stderr));}});
  return {events,send:command=>child.stdin.write(JSON.stringify(command)+'\n'),next(type,match=()=>true){const i=events.findIndex(e=>e.type===type&&match(e));if(i>=0)return Promise.resolve(events.splice(i,1)[0]);return new Promise((resolve,reject)=>{const w={type,match,resolve,reject};w.timer=setTimeout(()=>{waiters.splice(waiters.indexOf(w),1);reject(new Error('Timed out waiting for '+type+': '+JSON.stringify(events.filter(e=>e.type==='error'||e.type==='session_error'))));},10000);waiters.push(w);});},async close(){if(child.exitCode!==null||child.signalCode!==null)return;const exited=new Promise(resolve=>child.once('exit',resolve));child.kill('SIGKILL');await exited;}};
}
async function setup(t){
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'chengcheng-sessions-'));let release;
  const server=http.createServer(async(req,res)=>{
    let body='';for await(const part of req)body+=part;
    const send=()=>{res.writeHead(200,{'content-type':'text/event-stream'});res.end('data: '+JSON.stringify({id:'fixture',object:'chat.completion.chunk',model:'session-test',choices:[{index:0,delta:{role:'assistant',content:'保存的回答'},finish_reason:'stop'}]})+'\n\ndata: [DONE]\n\n');};
    if(JSON.stringify(JSON.parse(body).messages).includes('hold-test'))release=send;else send();
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const config={provider:'custom',baseUrl:`http://127.0.0.1:${server.address().port}/v1`,model:'session-test',api:'openai-completions',apiKey:'fixture-only'};
  const unblock=()=>{const pending=release;release=undefined;pending?.();};
  const instances=[];t.after(async()=>{unblock();for(const r of instances)await r.close();server.closeAllConnections();server.close();fs.rmSync(root,{recursive:true,force:true});});
  return {root,config,release:unblock,async start(){const r=boot(root);instances.push(r);await r.next('ready');r.send({type:'configure',config});const event=await r.next('configured');return {r,id:event.sessionId};}};
}
async function mutate(r,id,action,extra={}){const requestId=action+'-'+Math.random();r.send({type:'session_manage',requestId,id,action,...extra});return r.next('session_managed',e=>e.requestId===requestId);}
async function list(r){r.events.splice(0,r.events.length,...r.events.filter(e=>e.type!=='sessions'));r.send({type:'sessions'});return (await r.next('sessions')).items;}

test('native protocol manages empty and saved sessions across restart without losing messages', {timeout:30000},async t=>{
  const fixture=await setup(t);let {r,id}=await fixture.start();
  await mutate(r,id,'rename',{name:'空白计划'});assert.equal((await list(r)).find(e=>e.id===id).title,'空白计划');
  await r.close();({r}=await fixture.start());assert.equal((await list(r)).find(e=>e.id===id).title,'空白计划');
  r.send({type:'prompt',id:'saved',text:'必须保留的提问'});await r.next('turn_end');
  await mutate(r,id,'archive');const changed=await r.next('session_changed');assert.notEqual(changed.sessionId,id);
  assert.equal((await list(r)).find(e=>e.id===id).archived,true);
  await r.close();({r}=await fixture.start());assert.ok((await list(r)).find(e=>e.current).id!==id);
  await mutate(r,id,'restore');r.send({type:'open_session',id});await r.next('session_changed');
  r.send({type:'history'});let history;do{history=await r.next('history');}while(history.sessionId!==id);
  assert.ok(history.items.some(e=>e.text==='必须保留的提问'));assert.ok(history.items.some(e=>e.text==='保存的回答'));
  await mutate(r,id,'delete',{confirm:true});await r.next('session_changed');assert.equal((await list(r)).find(e=>e.id===id).deleted,true);
  await mutate(r,id,'restore');assert.equal((await list(r)).find(e=>e.id===id).deleted,false);
  await mutate(r,id,'delete',{confirm:true});fs.writeFileSync(path.join(fixture.root,'workspace','keep.txt'),'保留工作区');
  await mutate(r,id,'purge',{confirm:true});assert.ok(!(await list(r)).some(e=>e.id===id));
  // A stale pre-crash active pointer must not bring a purged conversation back.
  fs.writeFileSync(path.join(fixture.root,'active-session.json'),JSON.stringify({id}));await r.close();({r}=await fixture.start());
  assert.ok(!(await list(r)).some(e=>e.id===id));assert.equal(fs.readFileSync(path.join(fixture.root,'workspace','keep.txt'),'utf8'),'保留工作区');
});

test('management errors are scoped and busy/concurrent/invalid requests do not corrupt sessions', {timeout:30000},async t=>{
  const fixture=await setup(t),{r,id}=await fixture.start();
  for(const command of [{action:'rename',name:''},{action:'delete'},{action:'purge',confirm:true},{action:'rename',id:'../outside',name:'bad'}]){
    const requestId=Math.random().toString();r.send({type:'session_manage',id,requestId,...command});await r.next('session_error',e=>e.requestId===requestId);
  }
  r.send({type:'session_manage',id,action:'rename',name:'保留名称',requestId:'first'});
  r.send({type:'session_manage',id,action:'delete',confirm:true,requestId:'racing'});
  await r.next('session_error',e=>e.requestId==='racing');await r.next('session_managed',e=>e.requestId==='first');
  assert.equal((await list(r)).find(e=>e.id===id).title,'保留名称');
  r.send({type:'prompt',id:'held',text:'hold-test'});await r.next('turn_start');
  r.send({type:'session_manage',id,action:'archive',requestId:'busy'});const error=await r.next('session_error',e=>e.requestId==='busy');assert.match(error.message,/任务执行中/);
  r.send({type:'history'});assert.equal((await r.next('history')).busy,true);assert.equal(r.events.some(e=>e.type==='error'),false,'Management must not emit turn-ending errors');
  assert.equal((await list(r)).find(e=>e.id===id).archived,false);
  for(let i=0;i<100;i++){fixture.release();if(r.events.some(e=>e.type==='turn_end'))break;await new Promise(resolve=>setTimeout(resolve,20));}
  await r.next('turn_end');await mutate(r,id,'archive');assert.equal((await list(r)).find(e=>e.id===id).archived,true);
});
