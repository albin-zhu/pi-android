import {stopRuntimeChild} from './runtime-cleanup.mjs';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,rm,access} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import {jsonLines} from '../runtime/protocol.mjs';

test('bash executes through POSIX shell and can access the storage root',{timeout:20000},async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'zfold-shell-'));
  const server=http.createServer(async(req,res)=>{
    if(req.method!=='POST'){
      res.writeHead(200,{'content-type':'application/json'});
      res.end(JSON.stringify({object:'list',data:[]}));
      return;
    }
    let body='';for await(const part of req)body+=part;
    if(!body.trim()){
      res.writeHead(200,{'content-type':'application/json'});
      res.end(JSON.stringify({object:'list',data:[]}));
      return;
    }
    const input=JSON.parse(body);
    res.writeHead(200,{'content-type':'text/event-stream'});
    if(!input.messages.some(message=>message.role==='tool')){
      const args=JSON.stringify({command:'printf "cwd=%s\\n" "$PWD" > shell-marker.txt; printf "cwd=%s" "$PWD"'});
      res.write('data: '+JSON.stringify({id:'shell',choices:[{index:0,delta:{role:'assistant',tool_calls:[{index:0,id:'shell-1',type:'function',function:{name:'bash',arguments:args}}]},finish_reason:null}]})+'\n\n');
      res.write('data: '+JSON.stringify({id:'shell',choices:[{index:0,delta:{},finish_reason:'tool_calls'}]})+'\n\ndata: [DONE]\n\n');
    }else{
      const content=input.messages.findLast(message=>message.role==='tool').content;
      const raw=typeof content==='string'?content:content[0]?.text;
      assert.match(String(raw),/cwd=/);
      res.write('data: '+JSON.stringify({id:'shell',choices:[{index:0,delta:{role:'assistant',content:'shell ready.'},finish_reason:null}]})+'\n\n');
      res.write('data: '+JSON.stringify({id:'shell',choices:[{index:0,delta:{},finish_reason:'stop'}],usage:{prompt_tokens:1,completion_tokens:1,total_tokens:2}})+'\n\ndata: [DONE]\n\n');
    }
    res.end();
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe']});
  const send=value=>child.stdin.write(JSON.stringify(value)+'\n');
  let stderr='',answer='',bash;child.stderr.on('data',part=>stderr+=part);
  child.stdout.setEncoding('utf8');child.stdout.on('data',jsonLines(event=>{
    if(event.type==='error')child.emit('runtime-error',new Error(event.message));
    if(event.type==='ready')send({type:'configure',config:{baseUrl:`http://127.0.0.1:${server.address().port}/v1`,model:'shell-test',apiKey:'fixture',api:'openai-completions'}});
    if(event.type==='configured')send({type:'prompt',id:'shell-test',text:'run shell'});
    if(event.type==='tool_execution'&&event.name==='bash')bash=event;
    if(event.type==='delta')answer+=event.text;
    if(event.type==='turn_end')child.emit('runtime-done');
  }));
  try{
    await new Promise((resolve,reject)=>{
      child.on('exit',(code,signal)=>reject(new Error(`Runtime exited ${signal||code}: ${stderr}`)));
      child.once('runtime-error',reject);child.once('runtime-done',resolve);
    });
    assert.equal(bash?.status,'success','bash=' + JSON.stringify(bash));assert.match(answer,/shell ready/);
    await assert.doesNotReject(()=>access(path.join(root,'workspace','shell-marker.txt')));
  }finally{await stopRuntimeChild(child);server.closeAllConnections();server.close();await rm(root,{recursive:true,force:true});}
});
