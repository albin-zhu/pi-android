import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import {spawn} from 'node:child_process';
import {SessionManager} from '@earendil-works/pi-coding-agent';
import {jsonLines} from '../runtime/protocol.mjs';
import {stopRuntimeChild} from './runtime-cleanup.mjs';

function complete(res,{text='',finish='stop',output=8}={}){
  res.writeHead(200,{'content-type':'text/event-stream'});
  const chunk=(delta,finish_reason,usage)=>'data: '+JSON.stringify({id:'interruption-fixture',object:'chat.completion.chunk',created:1,model:'interruption-test',choices:[{index:0,delta,finish_reason}],...(usage?{usage}:{})})+'\n\n';
  res.end(chunk({role:'assistant',...(text?{content:text}:{})},null)+chunk({},finish,{prompt_tokens:8,completion_tokens:output,total_tokens:8+output})+'data: [DONE]\n\n');
}
function boot(root){
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe'],env:{...process.env,PI_CODING_AGENT_DIR:path.join(root,'pi'),ZFOLD_CREDENTIALS_DIR:path.join(root,'.pi','agent')}});
  const events=[],seen=[],waiters=[];let stderr='';
  child.stderr.on('data',data=>stderr+=data);
  const fail=error=>{for(const waiter of waiters.splice(0)){clearTimeout(waiter.timer);waiter.reject(error);}};
  child.stdout.setEncoding('utf8');child.stdout.on('data',jsonLines(event=>{
    seen.push(event);
    if(event.type==='tool_request'&&event.name==='runtime_environment'){
      child.stdin.write(JSON.stringify({type:'tool_result',id:event.id,result:{PATH:process.env.PATH,HOME:root,TMPDIR:os.tmpdir()}})+'\n');return;
    }
    const index=waiters.findIndex(waiter=>waiter.type===event.type&&waiter.match(event));
    if(index<0)events.push(event);else{const waiter=waiters.splice(index,1)[0];clearTimeout(waiter.timer);waiter.resolve(event);}
  },fail));
  child.on('exit',code=>fail(new Error(`Runtime exited ${code}: ${stderr}`)));
  return {child,events,seen,send:command=>child.stdin.write(JSON.stringify(command)+'\n'),next(type,match=()=>true){
    const index=events.findIndex(event=>event.type===type&&match(event));
    if(index>=0)return Promise.resolve(events.splice(index,1)[0]);
    return new Promise((resolve,reject)=>{const waiter={type,match,resolve,reject};waiter.timer=setTimeout(()=>{waiters.splice(waiters.indexOf(waiter),1);reject(new Error(`Waiting for ${type}: ${JSON.stringify(events.filter(event=>event.type==='error'||event.type==='retry_status'))}; ${stderr}`));},15000);waiters.push(waiter);});
  },close:()=>stopRuntimeChild(child)};
}
async function fixture(t,reply,seed){
  const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'pi-interruption-')));
  if(seed){fs.mkdirSync(path.join(root,'workspace'));fs.mkdirSync(path.join(root,'sessions'));const manager=SessionManager.create(path.join(root,'workspace'),path.join(root,'sessions'));seed(manager);fs.writeFileSync(path.join(root,'active-session.json'),JSON.stringify({id:manager.getSessionId()}));}
  let requests=0;
  const server=http.createServer(async(req,res)=>{try{let body='';for await(const data of req)body+=data;await reply(JSON.parse(body),res,++requests);}catch(error){res.destroy(error);}});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const config={provider:'custom',baseUrl:`http://127.0.0.1:${server.address().port}/v1`,model:'interruption-test',api:'openai-completions',apiKey:'fixture-only'},instances=[];
  t.after(async()=>{for(const runtime of instances)await runtime.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));fs.rmSync(root,{recursive:true,force:true});});
  return {root,get requests(){return requests;},async start(){const runtime=boot(root);instances.push(runtime);await runtime.next('ready');runtime.send({type:'configure',config});const configured=await runtime.next('configured');return {runtime,id:configured.sessionId};}};
}
async function history(runtime){
  for(let i=runtime.events.length-1;i>=0;i--)if(runtime.events[i].type==='history')runtime.events.splice(i,1);
  runtime.send({type:'history'});return runtime.next('history');
}
function card(event){const {type,turnId,...item}=event;return item;}
const usage={input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}};
function assistant(timestamp,stopReason,content,errorMessage){return {role:'assistant',api:'openai-completions',provider:'custom',model:'interruption-test',timestamp,stopReason,content,usage,...(errorMessage?{errorMessage}:{})};}

test('runtime exposes raw empty errors, partial length, and abort as separate persistent interruption cards', {timeout:30000},async t=>{
  const f=await fixture(t,async(input,res)=>{
    const text=JSON.stringify(input.messages.findLast(message=>message.role==='user').content);
    if(text.includes('HTTP413')){res.writeHead(413,{'content-type':'application/json'});res.end(JSON.stringify({error:{message:'request_too_large: max52428800',type:'request_too_large'}}));}
    else if(text.includes('LENGTH'))complete(res,{text:'服务端截断前的正文。',finish:'length',output:4096});
    else{res.writeHead(200,{'content-type':'text/event-stream'});res.write('data: '+JSON.stringify({id:'held',choices:[{index:0,delta:{role:'assistant',content:'中止前已经生成的正文。'},finish_reason:null}]})+'\n\n');}
  });
  let {runtime,id}=await f.start();
  runtime.send({type:'prompt',id:'empty-error',text:'HTTP413'});
  const failed=await runtime.next('assistant_interruption',event=>event.turnId==='empty-error');
  assert.equal(failed.reason,'error');assert.match(failed.message,/request_too_large/);assert.match(failed.message,/52428800/);
  const terminal=await runtime.next('error',event=>event.turnId==='empty-error');assert.equal(terminal.interruptionId,failed.id);assert.equal(terminal.message,failed.message);
  let restored=await history(runtime);assert.equal(restored.busy,false);assert.equal(restored.retry,null);assert.deepEqual(restored.items.filter(item=>item.role==='interruption'),[card(failed)]);
  const records=fs.readdirSync(path.join(f.root,'sessions')).filter(name=>name.endsWith('.jsonl')).flatMap(name=>fs.readFileSync(path.join(f.root,'sessions',name),'utf8').trim().split('\n').map(line=>JSON.parse(line)));
  assert.equal(records.find(entry=>entry.type==='message'&&entry.message.role==='assistant'&&entry.message.stopReason==='error').message.errorMessage,failed.message);
  runtime.send({type:'prompt',id:'length',text:'LENGTH'});const length=await runtime.next('assistant_interruption',event=>event.turnId==='length');await runtime.next('turn_end',event=>event.turnId==='length');
  assert.equal(length.reason,'length');assert.match(length.message,/输出长度限制/);
  restored=await history(runtime);assert.ok(restored.items.some(item=>item.role==='assistant'&&item.text==='服务端截断前的正文。'));assert.deepEqual(restored.items.find(item=>item.id===length.id),card(length));
  runtime.send({type:'prompt',id:'aborted',text:'ABORT'});await runtime.next('delta',event=>event.turnId==='aborted');
  const streaming=await history(runtime);assert.equal(streaming.busy,true);assert.ok(streaming.items.some(item=>item.role==='assistant'&&item.text==='中止前已经生成的正文。'));
  runtime.send({type:'stop'});const aborted=await runtime.next('assistant_interruption',event=>event.turnId==='aborted');assert.equal(aborted.reason,'aborted');assert.equal((await runtime.next('turn_end',event=>event.turnId==='aborted')).aborted,true);
  restored=await history(runtime);assert.ok(restored.items.some(item=>item.text==='中止前已经生成的正文。'));assert.deepEqual(restored.items.find(item=>item.id===aborted.id),card(aborted));assert.equal(f.requests,3);
  await runtime.close();({runtime}=await f.start());restored=await history(runtime);assert.equal(restored.sessionId,id);
  for(const event of [failed,length,aborted])assert.deepEqual(restored.items.find(item=>item.id===event.id),card(event));
});

test('SDK automatic retry keeps busy and history snapshots while retaining the failed attempt after reopen', {timeout:30000},async t=>{
  const f=await fixture(t,async(input,res,request)=>complete(res,request===1?{text:'重试前已生成的片段。',finish:'network_error'}:{text:'重试完成后的正文。'}));
  let {runtime,id}=await f.start();runtime.send({type:'prompt',id:'retry-turn',text:'保留失败尝试'});
  const interrupted=await runtime.next('assistant_interruption'),waiting=await runtime.next('retry_status',event=>event.status==='waiting');
  assert.equal(waiting.source,'model');assert.equal(waiting.turnId,'retry-turn');assert.equal(waiting.attempt,1);assert.equal(waiting.maxAttempts,3);assert.equal(waiting.delayMs,2000);assert.equal(waiting.message,interrupted.message);assert.equal(Object.hasOwn(waiting,'success'),false);
  const {type,...retrySnapshot}=waiting;
  let snapshot=await history(runtime);assert.equal(snapshot.busy,true);assert.deepEqual(snapshot.retry,retrySnapshot);
  assert.deepEqual(snapshot.items.find(item=>item.id===interrupted.id),card(interrupted));assert.ok(snapshot.items.some(item=>item.text==='重试前已生成的片段。'));
  assert.ok(!runtime.seen.some(event=>(event.type==='error'||event.type==='turn_end')&&event.turnId==='retry-turn'));
  const finished=await runtime.next('retry_status',event=>event.status==='finished');assert.equal(finished.success,true);assert.equal(finished.attempt,1);assert.equal(finished.maxAttempts,3);await runtime.next('turn_end');
  snapshot=await history(runtime);assert.equal(snapshot.busy,false);assert.equal(snapshot.retry,null);assert.deepEqual(snapshot.items.find(item=>item.id===interrupted.id),card(interrupted));assert.ok(snapshot.items.some(item=>item.text==='重试完成后的正文。'));assert.equal(f.requests,2);
  runtime.send({type:'new_session'});await runtime.next('session_changed');assert.deepEqual((await history(runtime)).items,[]);
  runtime.send({type:'open_session',id});await runtime.next('session_changed');assert.deepEqual((await history(runtime)).items.find(item=>item.id===interrupted.id),card(interrupted));
  await runtime.close();({runtime}=await f.start());snapshot=await history(runtime);assert.equal(snapshot.sessionId,id);assert.deepEqual(snapshot.items.find(item=>item.id===interrupted.id),card(interrupted));assert.equal(snapshot.retry,null);
});

test('runtime history reads compacted original records from the current branch without abandoned errors', {timeout:20000},async t=>{
  const persisted=assistant(2,'error',[],'原始分支错误 fetch failed');
  const f=await fixture(t,async(input,res)=>complete(res),manager=>{
    manager.appendMessage({role:'user',content:'已压缩的原始提问',timestamp:1});manager.appendMessage(persisted);
    const kept=manager.appendMessage({role:'user',content:'当前保留提问',timestamp:3});manager.appendMessage(assistant(4,'error',[],'不应显示的废弃分支错误'));
    manager.branch(kept);manager.appendMessage(assistant(5,'stop',[{type:'text',text:'当前分支正文'}]));manager.appendCompaction('仅供模型的压缩摘要',kept,100);
  });
  const {runtime}=await f.start(),snapshot=await history(runtime);
  assert.ok(snapshot.items.some(item=>item.text==='已压缩的原始提问'));assert.ok(snapshot.items.some(item=>item.text==='当前分支正文'));
  assert.equal(snapshot.items.find(item=>item.role==='interruption').message,persisted.errorMessage);assert.equal(snapshot.items.filter(item=>item.role==='interruption').length,1);
  assert.ok(!JSON.stringify(snapshot.items).includes('不应显示的废弃分支错误'));assert.equal(f.requests,0);
});

test('compaction retry progress and terminal failure are visible without discarding the truncated assistant', {timeout:30000},async t=>{
  const f=await fixture(t,async(input,res,request)=>{
    if(request===1)complete(res,{text:'恢复前的部分正文。',finish:'length',output:1});
    else complete(res,{finish:request===2?'network_error':'content_filter'});
  },manager=>{
    const now=Date.now()-10000;manager.appendMessage({role:'user',content:'上一个较长回合',timestamp:now});
    manager.appendMessage(assistant(now+1,'stop',[{type:'text',text:'history '.repeat(16000)}]));
  });
  const {runtime}=await f.start();runtime.send({type:'prompt',id:'compaction-turn',text:'继续后服务端截断'});
  const truncated=await runtime.next('assistant_interruption');assert.equal(truncated.reason,'length');
  const compacting=await runtime.next('retry_status',event=>event.source==='compaction'&&event.status==='waiting'&&event.attempt===0);assert.match(compacting.message,/压缩上下文/);
  const waiting=await runtime.next('retry_status',event=>event.source==='compaction'&&event.status==='waiting'&&event.attempt===1);assert.match(waiting.message,/network_error/);
  let snapshot=await history(runtime);assert.equal(snapshot.busy,true);assert.equal(snapshot.retry.source,'compaction');assert.equal(snapshot.retry.attempt,1);assert.deepEqual(snapshot.items.find(item=>item.id===truncated.id),card(truncated));
  const finished=await runtime.next('retry_status',event=>event.source==='compaction'&&event.status==='finished');assert.equal(finished.success,false);assert.match(finished.message,/content_filter/);
  const error=await runtime.next('error',event=>event.turnId==='compaction-turn');assert.equal(error.message,finished.message);assert.equal(Object.hasOwn(error,'interruptionId'),false,'Summary errors have no persisted assistant record to deduplicate');
  snapshot=await history(runtime);assert.equal(snapshot.busy,false);assert.equal(snapshot.retry,null);assert.deepEqual(snapshot.items.find(item=>item.id===truncated.id),card(truncated));assert.ok(snapshot.items.some(item=>item.text==='恢复前的部分正文。'));assert.equal(f.requests,3);
});
