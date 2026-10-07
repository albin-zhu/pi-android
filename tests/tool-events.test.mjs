import {stopRuntimeChild} from './runtime-cleanup.mjs';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import {jsonLines} from '../runtime/protocol.mjs';

test('real Pi tool events and restart history retain success, failure, and arguments', {timeout:20000},async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'zfold-tool-events-'));
  const server=http.createServer(async(req,res)=>{
    let body='';for await(const chunk of req)body+=chunk;
    const input=JSON.parse(body),count=input.messages.filter(m=>m.role==='tool').length;
    res.writeHead(200,{'content-type':'text/event-stream'});
    const chunk=(delta,finish=null)=>res.write('data: '+JSON.stringify({id:'tools',choices:[{index:0,delta,finish_reason:finish}]})+'\n\n');
    if(count<2){
      chunk({role:'assistant',tool_calls:[{index:0,id:count?'read-failed':'info-ok',type:'function',function:{name:count?'read':'device_info',arguments:count?'{"path":"/not-a-skill"}':'{}'}}]});chunk({},'tool_calls');
    }else{chunk({role:'assistant',content:'手机已读取，另一个工具失败。'});chunk({},'stop');}
    res.end('data: [DONE]\n\n');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const config={baseUrl:`http://127.0.0.1:${server.address().port}/v1`,model:'tool-test',apiKey:'fixture',api:'openai-completions'};
  const boot=()=>{
    const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe'],env:{...process.env,PI_CODING_AGENT_DIR:path.join(root,'pi')}});
    const queue=[],waiters=[],tools=[];let stderr='';child.stderr.on('data',part=>stderr+=part);
    const send=value=>child.stdin.write(JSON.stringify(value)+'\n');
    child.stdout.setEncoding('utf8');child.stdout.on('data',jsonLines(event=>{
      if(event.type==='tool_request')send({type:'tool_result',id:event.id,result:{model:'测试手机',batteryPercent:72}});
      if(event.type==='tool_execution')tools.push(event);
      if(event.type==='error'){for(const w of waiters.splice(0))w.reject(new Error(event.message));return;}
      const index=waiters.findIndex(w=>w.type===event.type);
      if(index>=0)waiters.splice(index,1)[0].resolve(event);else queue.push(event);
    }));
    child.on('exit',code=>{for(const w of waiters.splice(0))w.reject(new Error(`Exit ${code}: ${stderr}`));});
    const next=type=>{const index=queue.findIndex(e=>e.type===type);return index>=0?Promise.resolve(queue.splice(index,1)[0]):new Promise((resolve,reject)=>waiters.push({type,resolve,reject}));};
    return {child,send,next,tools};
  };
  let runtime=boot();
  try{
    await runtime.next('ready');runtime.send({type:'configure',config});await runtime.next('configured');
    runtime.send({type:'prompt',id:'tools-turn',text:'读取手机状态'});await runtime.next('turn_end');
    assert.deepEqual(runtime.tools.map(e=>[e.name,e.status]),[['device_info','running'],['device_info','success'],['read','running'],['read','error']]);
    assert.match(runtime.tools[1].output,/72/);assert.equal(runtime.tools[2].args,'{"path":"/not-a-skill"}');
    runtime.send({type:'history'});const live=(await runtime.next('history')).items;
    assert.deepEqual(live.map(i=>i.role),['user','tool','tool','assistant']);
    const exited=new Promise(resolve=>runtime.child.once('exit',resolve));runtime.child.kill();await exited;
    runtime=boot();await runtime.next('ready');runtime.send({type:'configure',config});await runtime.next('configured');
    runtime.send({type:'history'});const restored=(await runtime.next('history')).items;
    assert.deepEqual(restored,live);
    runtime.send({type:'new_session'});await runtime.next('session_changed');assert.deepEqual((await runtime.next('history')).items,[]);
  }finally{await stopRuntimeChild(runtime.child);server.closeAllConnections();server.close();await rm(root,{recursive:true,force:true});}
});
