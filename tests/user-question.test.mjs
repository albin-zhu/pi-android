import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Type} from 'typebox';
import {normalizeQuestions,validateAnswers,createAskUserQuestionTool,QuestionToolGate} from '../runtime/user-question.mjs';
import {NativeToolBridge} from '../runtime/native-tools.mjs';
import {spawn} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import {jsonLines} from '../runtime/protocol.mjs';

const sample=[{id:'platform',question:'选择目标平台',options:[{label:'Android'},{label:'桌面'}]},{id:'features',question:'需要哪些能力？',multiSelect:true,options:[{label:'语音'},{label:'相机'}]}];
test('question and answer schema preserves explicit choices, free text and declined status',()=>{
  const questions=normalizeQuestions(sample);assert.equal(questions[0].multiSelect,false);
  const answers=validateAnswers(questions,{status:'answered',answers:[{id:'platform',selected:['Android'],text:'只用于 Fold'},{id:'features',selected:['语音','相机']}]});assert.deepEqual(answers.answers[1].selected,['语音','相机']);
  assert.throws(()=>validateAnswers(questions,{status:'answered',answers:[{id:'platform',selected:['桌面','Android']},{id:'features',text:'都不要'}]}));
  assert.throws(()=>validateAnswers(questions,{status:'answered',answers:[{id:'platform',selected:['unknown']},{id:'features',text:'自由回答'}]}));
  assert.throws(()=>validateAnswers(questions,{status:'answered',answers:[{id:'platform',text:''},{id:'features',text:'x'}]}));
  assert.equal(validateAnswers(questions,{status:'declined',answers:[{id:'platform',selected:['Android']}]}).answers.length,0);
  for(const value of [[],[...sample,{...sample[0]}],[{question:'',options:[]}],[{question:'x',options:[{label:'a'}]}],[{question:'x',options:[{label:'a'},{label:'a'}]}]])assert.throws(()=>normalizeQuestions(value));
});
test('asking blocks until an explicit receipt, heartbeats keep waiting and cancellation has no default answer',async t=>{
  t.mock.timers.enable({apis:['setTimeout']});const events=[];const bridge=new NativeToolBridge(event=>events.push(event));const abort=new AbortController();
  const tool=createAskUserQuestionTool({Type,call:(name,args,signal)=>bridge.call(name,args,{signal,turnId:'turn'}),sessionId:()=> 'session'});let resolved=false;
  const result=tool.execute('model-call',{questions:sample},abort.signal).then(value=>{resolved=true;return value;},error=>error);
  assert.equal(tool.executionMode,'sequential');assert.equal(events[0].args.sessionId,'session');assert.equal(events[0].args.toolCallId,'model-call');
  for(let i=0;i<9;i++){t.mock.timers.tick(10000);bridge.receive({type:'tool_heartbeat',id:events[0].id});}assert.equal(resolved,false);assert.equal(events.length,1);
  abort.abort();assert.equal(events[1].type,'tool_cancel');bridge.receive({type:'tool_result',id:events[0].id,error:'提问已取消'});assert.match((await result).message,/取消/);assert.equal(resolved,false);
});
test('a question batch cannot pre-execute actions before the model reads the answer',async()=>{
  const gate=new QuestionToolGate();let actions=0;const action=gate.wrap({name:'phone_sms',execute:()=>actions++}),ask=gate.wrap({name:'ask_user_question',execute:()=>({status:'declined'})});
  gate.observe({type:'message_start',message:{role:'assistant'}});gate.observe({type:'message_end',message:{role:'assistant',content:[{type:'toolCall',name:'phone_sms'},{type:'toolCall',name:'ask_user_question'}]}});
  assert.throws(()=>action.execute());assert.equal(ask.execute().status,'declined');assert.throws(()=>action.execute());assert.throws(()=>ask.execute());assert.equal(actions,0);
  gate.observe({type:'message_start',message:{role:'assistant'}});action.execute();assert.equal(actions,1);
});
test('bundled agent asks through the native bridge and reads the answer before continuing', {timeout:20000},async()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'ask-user-runtime-'));const requests=[];let messages;
  const server=http.createServer(async(req,res)=>{
    let data='';for await(const part of req)data+=part;messages=JSON.parse(data).messages;
    const answered=messages.some(m=>m.role==='tool'&&String(m.content).includes('"status":"answered"'));
    const call=(id,name,args)=>({index:id==='before'?0:id==='ask'?1:2,id,type:'function',function:{name,arguments:JSON.stringify(args)}});
    const delta=answered?{role:'assistant',content:'已根据用户选择继续。'}:{role:'assistant',tool_calls:[call('before','device_info',{}),call('ask','ask_user_question',{questions:sample}),call('after','device_info',{})]};
    res.writeHead(200,{'content-type':'text/event-stream'});res.end('data: '+JSON.stringify({id:'fixture',choices:[{index:0,delta,finish_reason:answered?'stop':'tool_calls'}]})+'\n\ndata: [DONE]\n\n');
  });await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe'],env:{...process.env,PI_CODING_AGENT_DIR:path.join(root,'pi')}});
  const events=[],waiters=[];const send=command=>child.stdin.write(JSON.stringify(command)+'\n');
  child.stdout.setEncoding('utf8');child.stdout.on('data',jsonLines(event=>{if(event.type==='tool_request')requests.push(event);const i=waiters.findIndex(w=>w.type===event.type);if(i>=0){const w=waiters.splice(i,1)[0];clearTimeout(w.timer);w.resolve(event);}else events.push(event);},()=>{}));
  const next=type=>{const i=events.findIndex(e=>e.type===type);if(i>=0)return Promise.resolve(events.splice(i,1)[0]);return new Promise((resolve,reject)=>{const w={type,resolve,reject};w.timer=setTimeout(()=>reject(new Error('Missing '+type)),8000);waiters.push(w);});};
  try{
    await next('ready');send({type:'configure',config:{provider:'custom',baseUrl:`http://127.0.0.1:${server.address().port}/v1`,model:'ask-fixture',api:'openai-completions',apiKey:'fixture'}});const configured=await next('configured');assert.ok(configured.tools.includes('ask_user_question'));
    send({type:'prompt',id:'ask-turn',text:'先问我再继续'});const question=await next('tool_request');assert.equal(question.name,'ask_user_question');assert.equal(question.args.sessionId,configured.sessionId);assert.equal(question.turnId,'ask-turn');
    send({type:'tool_result',id:question.id,result:{status:'answered',answers:[{id:'platform',selected:['Android']},{id:'features',text:'只要语音'}]}});await next('turn_end');assert.equal(requests.length,1);assert.ok(messages.some(m=>m.role==='tool'&&String(m.content).includes('只要语音')));
  }finally{for(const w of waiters)clearTimeout(w.timer);child.kill('SIGKILL');await new Promise(resolve=>child.once('exit',resolve));server.closeAllConnections();server.close();fs.rmSync(root,{recursive:true,force:true});}
});
