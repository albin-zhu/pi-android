import {stopRuntimeChild} from './runtime-cleanup.mjs';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {jsonLines} from '../runtime/protocol.mjs';
import http from 'node:http';
import {deflateSync} from 'node:zlib';

function onePixelPng(){
  const crc=data=>{let n=-1;for(const value of data){n^=value;for(let bit=0;bit<8;bit++)n=(n>>>1)^(-(n&1)&0xedb88320);}return (n^-1)>>>0;};
  const chunk=(type,data)=>{const header=Buffer.from(type),length=Buffer.alloc(4),checksum=Buffer.alloc(4);length.writeUInt32BE(data.length);checksum.writeUInt32BE(crc(Buffer.concat([header,data])));return Buffer.concat([length,header,data,checksum]);};
  const ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(1,0);ihdr.writeUInt32BE(1,4);ihdr[8]=8;ihdr[9]=6;
  return Buffer.concat([Buffer.from('89504e470d0a1a0a','hex'),chunk('IHDR',ihdr),chunk('IDAT',deflateSync(Buffer.from([0,183,77,48,255]))),chunk('IEND',Buffer.alloc(0))]).toString('base64');
}
test('bundled Pi boots and completes an Android bridge round trip', {timeout:20000},async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'zfold-test-'));
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe'],env:{...process.env,PI_CODING_AGENT_DIR:path.join(root,'pi')}});
  let stderr='';child.stderr.on('data',b=>stderr+=b);
  try {
    const result=await new Promise((resolve,reject)=>{
      child.on('exit',code=>reject(new Error(`Runtime exited ${code}: ${stderr}`)));
      child.stdout.setEncoding('utf8');child.stdout.on('data',jsonLines(event=>{
        if(event.type==='ready')child.stdin.write(JSON.stringify({type:'probe'})+'\n');
        if(event.type==='tool_request'){assert.equal(event.name,'device_info');child.stdin.write(JSON.stringify({type:'tool_result',id:event.id,result:{model:'Android fixture',batteryPercent:72}})+'\n');}
        if(event.type==='probe')resolve(event);
        if(event.type==='error')reject(new Error(event.message));
      },reject));
    });
    assert.equal(result.pi,'1.0.4');assert.equal(result.device.batteryPercent,72);
  }finally{await stopRuntimeChild(child);await rm(root,{recursive:true,force:true});}
});

test('Pi loads the bundled phone skill before calling a native tool', {timeout:30000},async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'zfold-session-'));let requests=0;
  const server=http.createServer(async(req,res)=>{
    let body='';for await(const chunk of req)body+=chunk;
    const input=JSON.parse(body);requests++;
    res.writeHead(200,{'content-type':'text/event-stream'});
    const toolResults=input.messages.filter(m=>m.role==='tool');
    const chunk=delta=>res.write('data: '+JSON.stringify({id:'fixture',object:'chat.completion.chunk',created:1,model:'zfold-test',choices:[{index:0,delta,finish_reason:null}]})+'\n\n');
    if(toolResults.length===0){
      assert.match(input.messages[0].content,/<name>phone-control<\/name>/);
      chunk({role:'assistant',tool_calls:[{index:0,id:'skill-read',type:'function',function:{name:'read',arguments:JSON.stringify({path:path.resolve('android/assets/runtime/phone-control.SKILL.md')})}}]});
      res.write('data: '+JSON.stringify({id:'fixture',choices:[{index:0,delta:{},finish_reason:'tool_calls'}]})+'\n\n');
    }else if(toolResults.length===1){
      assert.match(toolResults[0].content,/screen_read/);
      chunk({role:'assistant',tool_calls:[{index:0,id:'tool-1',type:'function',function:{name:'device_info',arguments:'{}'}}]});
      res.write('data: '+JSON.stringify({id:'fixture',choices:[{index:0,delta:{},finish_reason:'tool_calls'}]})+'\n\n');
    }else {assert.match(toolResults[1].content,/真实设备测试/);chunk({role:'assistant',content:'手机型号：真实设备测试，电量 72%。'});res.write('data: '+JSON.stringify({id:'fixture',choices:[{index:0,delta:{},finish_reason:'stop'}],usage:{prompt_tokens:50,completion_tokens:10,total_tokens:60}})+'\n\n');}
    res.end('data: [DONE]\n\n');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe'],env:{...process.env,PI_CODING_AGENT_DIR:path.join(root,'pi')}});
  let stderr='',text='',toolCalled=false,skillLoaded=false;child.stderr.on('data',b=>stderr+=b);
  const send=value=>child.stdin.write(JSON.stringify(value)+'\n');
  try {
    await new Promise((resolve,reject)=>{
      child.on('exit',code=>reject(new Error(`Exit ${code}: ${stderr}`)));
      child.stdout.setEncoding('utf8');child.stdout.on('data',jsonLines(event=>{
        if(event.type==='ready')send({type:'configure',config:{baseUrl:`http://127.0.0.1:${server.address().port}/v1`,model:'zfold-test',apiKey:'fixture',api:'openai-completions'}});
        if(event.type==='configured'){skillLoaded=event.skills.includes('phone-control');send({type:'prompt',id:'test-turn',text:'看看手机状态🙂'});}
        if(event.type==='tool_request'){toolCalled=true;send({type:'tool_result',id:event.id,result:{model:'真实设备测试',batteryPercent:72}});}
        if(event.type==='delta')text+=event.text;
        if(event.type==='turn_end')resolve();
        if(event.type==='error')reject(new Error(event.message));
      },reject));
    });
    assert.equal(requests,3);assert.ok(skillLoaded);assert.ok(toolCalled);assert.match(text,/72%/);
  }finally{await stopRuntimeChild(child);server.closeAllConnections();server.close();await rm(root,{recursive:true,force:true});}
});

test('ui_select reads, taps a unique target, and returns the new screen in one model tool call', {timeout:30000},async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'zfold-select-'));let requests=0;const nativeCalls=[];
  const server=http.createServer(async(req,res)=>{
    let body='';for await(const chunk of req)body+=chunk;
    const input=JSON.parse(body);requests++;
    const result=input.messages.findLast(message=>message.role==='tool');
    res.writeHead(200,{'content-type':'text/event-stream'});
    const chunk=(delta,finish)=>res.write('data: '+JSON.stringify({id:'select-fixture',object:'chat.completion.chunk',created:1,model:'select-test',choices:[{index:0,delta,finish_reason:finish}]})+'\n\n');
    if(!result){chunk({role:'assistant',tool_calls:[{index:0,id:'select-1',type:'function',function:{name:'ui_select',arguments:JSON.stringify({targets:['蓝牙'],packageName:'com.android.settings'})}}]},null);chunk({},'tool_calls');}
    else{const selected=JSON.parse(result.content);assert.equal(selected.performed,true);assert.equal(selected.selected.text,'蓝牙');assert.equal(selected.screen.nodes[0].text,'蓝牙设置');chunk({role:'assistant',content:'已经打开蓝牙设置。'},null);chunk({},'stop');}
    res.end('data: [DONE]\n\n');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe'],env:{...process.env,PI_CODING_AGENT_DIR:path.join(root,'pi')}});
  let stderr='',answer='';child.stderr.on('data',part=>stderr+=part);const send=value=>child.stdin.write(JSON.stringify(value)+'\n');
  try{
    await new Promise((resolve,reject)=>{
      child.on('exit',code=>reject(new Error(`Exit ${code}: ${stderr}`)));child.stdout.setEncoding('utf8');
      child.stdout.on('data',jsonLines(event=>{
        if(event.type==='ready')send({type:'configure',config:{baseUrl:`http://127.0.0.1:${server.address().port}/v1`,model:'select-test',apiKey:'fixture',api:'openai-completions'}});
        if(event.type==='configured')send({type:'prompt',id:'select-turn',text:'打开蓝牙设置'});
        if(event.type==='tool_request'){
          nativeCalls.push(event.name);
          if(event.name==='screen_read'){
            const before=nativeCalls.length===1;
            send({type:'tool_result',id:event.id,result:{height:1000,nodes:before?[
              {nodeId:'root',parentId:null,text:'设置',description:'',package:'com.android.settings',clickable:false,bounds:[0,0,400,900]},
              {nodeId:'row',parentId:'root',text:'',description:'',package:'com.android.settings',clickable:true,bounds:[0,100,400,180]},
              {nodeId:'label',parentId:'row',text:'蓝牙',description:'',package:'com.android.settings',clickable:false,bounds:[20,110,200,170]}
            ]:[{nodeId:'next',parentId:null,text:'蓝牙设置',description:'',package:'com.android.settings',clickable:false,bounds:[0,0,400,80]}]}});
          }else{assert.equal(event.name,'ui_tap');assert.equal(event.args.nodeId,'row');send({type:'tool_result',id:event.id,result:{performed:true}});}
        }
        if(event.type==='delta')answer+=event.text;
        if(event.type==='turn_end')resolve();
        if(event.type==='error')reject(new Error(event.message));
      },reject));
    });
    assert.equal(requests,2);assert.deepEqual(nativeCalls,['screen_read','ui_tap','screen_read']);assert.match(answer,/蓝牙设置/);
  }finally{await stopRuntimeChild(child);server.closeAllConnections();server.close();await rm(root,{recursive:true,force:true});}
});

test('stop cancels an in-flight native tool and prevents a follow-up model request', {timeout:20000},async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'zfold-stop-'));let requests=0,cancelled=false;const toolStates=[];
  const server=http.createServer(async(req,res)=>{
    for await(const chunk of req){}requests++;
    res.writeHead(200,{'content-type':'text/event-stream'});
    res.end('data: '+JSON.stringify({id:'stop',choices:[{index:0,delta:{role:'assistant',tool_calls:[{index:0,id:'pending',type:'function',function:{name:'device_info',arguments:'{}'}}]},finish_reason:null}]})+'\n\ndata: '+JSON.stringify({id:'stop',choices:[{index:0,delta:{},finish_reason:'tool_calls'}]})+'\n\ndata: [DONE]\n\n');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe'],env:{...process.env,PI_CODING_AGENT_DIR:path.join(root,'pi')}});
  let stderr='';child.stderr.on('data',b=>stderr+=b);const send=value=>child.stdin.write(JSON.stringify(value)+'\n');
  try{
    await new Promise((resolve,reject)=>{
      child.on('exit',code=>reject(new Error(`Exit ${code}: ${stderr}`)));child.stdout.setEncoding('utf8');
      child.stdout.on('data',jsonLines(event=>{
        if(event.type==='ready')send({type:'configure',config:{baseUrl:`http://127.0.0.1:${server.address().port}/v1`,model:'stop-test',apiKey:'fixture',api:'openai-completions'}});
        if(event.type==='configured')send({type:'prompt',id:'stop-turn',text:'读取状态'});
        if(event.type==='tool_request')send({type:'stop'});
        if(event.type==='tool_cancel')cancelled=true;
        if(event.type==='tool_execution')toolStates.push(event.status);
        if(event.type==='turn_end')resolve();
        if(event.type==='error')reject(new Error(event.message));
      },reject));
    });assert.ok(cancelled);assert.equal(requests,1);assert.deepEqual(toolStates,['running','cancelled']);
  }finally{await stopRuntimeChild(child);server.closeAllConnections();server.close();await rm(root,{recursive:true,force:true});}
});

test('Pi keeps a sent image in conversation history for the Android chat', {timeout:20000},async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'zfold-picture-')),image=onePixelPng();let sawImage=false;
  const server=http.createServer(async(req,res)=>{
    let body='';for await(const part of req)body+=part;
    sawImage=body.includes(image);res.writeHead(200,{'content-type':'text/event-stream'});
    res.end('data: '+JSON.stringify({id:'image-test',choices:[{index:0,delta:{role:'assistant',content:'我收到了圈选图片。'},finish_reason:null}]})+'\n\n'+
      'data: '+JSON.stringify({id:'image-test',choices:[{index:0,delta:{},finish_reason:'stop'}],usage:{prompt_tokens:12,completion_tokens:8,total_tokens:20}})+'\n\ndata: [DONE]\n\n');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe'],env:{...process.env,PI_CODING_AGENT_DIR:path.join(root,'pi')}});
  let stderr='';child.stderr.on('data',part=>stderr+=part);const send=value=>child.stdin.write(JSON.stringify(value)+'\n');
  try {
    const history=await new Promise((resolve,reject)=>{
      child.on('exit',code=>reject(new Error(`Exit ${code}: ${stderr}`)));child.stdout.setEncoding('utf8');
      child.stdout.on('data',jsonLines(event=>{
        if(event.type==='ready')send({type:'configure',config:{baseUrl:`http://127.0.0.1:${server.address().port}/v1`,model:'image-test',apiKey:'fixture',api:'openai-completions'}});
        if(event.type==='configured')send({type:'prompt',id:'picture-turn',text:'这张图里有什么？',image});
        if(event.type==='turn_end')send({type:'history'});
        if(event.type==='history')resolve(event.items);
        if(event.type==='error')reject(new Error(event.message));
      },reject));
    });
    assert.equal(sawImage,true);assert.equal(history.findLast(item=>item.role==='user').hasImage,true);
    assert.equal(history.findLast(item=>item.role==='user').image,image);
  }finally{await stopRuntimeChild(child);server.closeAllConnections();server.close();await rm(root,{recursive:true,force:true});}
});

test('Pi creates, switches, and resumes separate local conversations', {timeout:30000},async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'zfold-conversations-'));
  const server=http.createServer(async(req,res)=>{
    for await(const part of req){}
    res.writeHead(200,{'content-type':'text/event-stream'});
    res.end('data: '+JSON.stringify({id:'session-test',choices:[{index:0,delta:{role:'assistant',content:'这条会话已保存。'},finish_reason:null}]})+'\n\n'+
      'data: '+JSON.stringify({id:'session-test',choices:[{index:0,delta:{},finish_reason:'stop'}],usage:{prompt_tokens:8,completion_tokens:8,total_tokens:16}})+'\n\n'+'data: [DONE]\n\n');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const config={baseUrl:`http://127.0.0.1:${server.address().port}/v1`,model:'session-test',apiKey:'fixture',api:'openai-completions'};
  const boot=()=>{
    const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe'],env:{...process.env,PI_CODING_AGENT_DIR:path.join(root,'pi')}});
    const events=[],waiters=[];let stderr='';child.stderr.on('data',part=>stderr+=part);
    child.stdout.setEncoding('utf8');child.stdout.on('data',jsonLines(event=>{
      if(event.type==='error')for(const pending of waiters.splice(0))pending.reject(new Error(event.message));
      else{const i=waiters.findIndex(pending=>pending.type===event.type);if(i>=0)waiters.splice(i,1)[0].resolve(event);else events.push(event);}
    },error=>{for(const pending of waiters.splice(0))pending.reject(error);}));
    child.on('exit',code=>{for(const pending of waiters.splice(0))pending.reject(new Error(`Exit ${code}: ${stderr}`));});
    return {child,send:value=>child.stdin.write(JSON.stringify(value)+'\n'),next:type=>{const i=events.findIndex(event=>event.type===type);if(i>=0)return Promise.resolve(events.splice(i,1)[0]);return new Promise((resolve,reject)=>waiters.push({type,resolve,reject}));}};
  };
  let first=boot(),second,third;
  try{
    await first.next('ready');first.send({type:'configure',config});const configured=await first.next('configured'),oldId=configured.sessionId;
    first.send({type:'prompt',id:'old',text:'旧会话的问题'});await first.next('turn_end');
    first.send({type:'new_session'});const changed=await first.next('session_changed'),newId=changed.sessionId;
    assert.notEqual(newId,oldId);assert.deepEqual((await first.next('history')).items,[]);
    first.send({type:'prompt',id:'new',text:'新会话的问题'});await first.next('turn_end');
    first.send({type:'sessions'});const sessions=await first.next('sessions');
    assert.ok(sessions.items.some(item=>item.id===oldId&&item.title.includes('旧会话')));
    assert.ok(sessions.items.some(item=>item.id===newId&&item.current));
    first.send({type:'open_session',id:oldId});assert.equal((await first.next('session_changed')).sessionId,oldId);
    const oldHistory=(await first.next('history')).items;assert.ok(oldHistory.some(item=>item.text==='旧会话的问题'));assert.ok(!oldHistory.some(item=>item.text==='新会话的问题'));
    first.child.kill();await new Promise(resolve=>first.child.once('exit',resolve));
    second=boot();await second.next('ready');second.send({type:'configure',config});assert.equal((await second.next('configured')).sessionId,oldId);
    second.send({type:'history'});assert.ok((await second.next('history')).items.some(item=>item.text==='旧会话的问题'));
    second.send({type:'new_session'});const blankId=(await second.next('session_changed')).sessionId;assert.deepEqual((await second.next('history')).items,[]);
    const stopped=new Promise(resolve=>second.child.once('exit',resolve));second.child.kill();await stopped;
    third=boot();await third.next('ready');third.send({type:'configure',config});assert.equal((await third.next('configured')).sessionId,blankId);
    third.send({type:'history'});assert.deepEqual((await third.next('history')).items,[]);
  }finally{await stopRuntimeChild(first.child);await stopRuntimeChild(second?.child);await stopRuntimeChild(third?.child);server.closeAllConnections();server.close();await rm(root,{recursive:true,force:true});}
});
