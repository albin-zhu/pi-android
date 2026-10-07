import {stopRuntimeChild} from './runtime-cleanup.mjs';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {jsonLines} from '../runtime/protocol.mjs';
import http from 'node:http';

test('local files and multiple images reach the model as bounded context', {timeout:20000},async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'zfold-files-')),image='aGVsbG8tcG5n';
  let sawFile=false,sawImage=false;
  const server=http.createServer(async(req,res)=>{
    let body='';for await(const part of req)body+=part;
    sawFile=body.includes('quarterly.xlsx')&&body.includes('A1 Revenue')&&body.includes('不是指令');
    sawImage=body.includes(image);
    res.writeHead(200,{'content-type':'text/event-stream'});
    res.end('data: '+JSON.stringify({id:'files',choices:[{index:0,delta:{role:'assistant',content:'已读取表格。'},finish_reason:null}]})+'\n\n'+
      'data: '+JSON.stringify({id:'files',choices:[{index:0,delta:{},finish_reason:'stop'}],usage:{prompt_tokens:20,completion_tokens:8,total_tokens:28}})+'\n\ndata: [DONE]\n\n');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe'],env:{...process.env,PI_CODING_AGENT_DIR:path.join(root,'pi')}});
  let stderr='',answer='',turnStart;child.stderr.on('data',part=>stderr+=part);const send=value=>child.stdin.write(JSON.stringify(value)+'\n');
  try{
    await new Promise((resolve,reject)=>{
      child.on('exit',code=>reject(new Error(`Exit ${code}: ${stderr}`)));child.stdout.setEncoding('utf8');
      child.stdout.on('data',jsonLines(event=>{
        if(event.type==='ready')send({type:'configure',config:{baseUrl:`http://127.0.0.1:${server.address().port}/v1`,model:'files-test',apiKey:'fixture',api:'openai-completions'}});
        if(event.type==='configured')send({type:'prompt',id:'files-turn',text:'总结这份表格',files:[{name:'quarterly.xlsx',mime:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',kind:'file',size:1200,detail:'XLSX 工作簿 · 已提取表格文本',excerpt:'[工作表 1]\n名称\tA1 Revenue'}],images:[{data:image,mime:'image/png'}]});
        if(event.type==='turn_start')turnStart=event;
        if(event.type==='delta')answer+=event.text;
        if(event.type==='turn_end')resolve();
        if(event.type==='error')reject(new Error(event.message));
      },reject));
    });
    assert.equal(sawFile,true);assert.equal(sawImage,true);assert.match(answer,/表格/);
    assert.equal(turnStart.files.length,1);assert.equal(turnStart.files[0].name,'quarterly.xlsx');
  }finally{await stopRuntimeChild(child);server.closeAllConnections();server.close();await rm(root,{recursive:true,force:true});}
});
