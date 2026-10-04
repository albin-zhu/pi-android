import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import {jsonLines} from '../runtime/protocol.mjs';

test('in-process runtime exposes Pi engine, tool directory, and package manager',{timeout:20000},async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'zfold-sandbox-'));
  let requests=0;
  const server=http.createServer(async(req,res)=>{
    let body='';for await(const part of req)body+=part;
    const input=JSON.parse(body);requests++;
    res.writeHead(200,{'content-type':'text/event-stream'});
    const tool=(name,args={})=>res.write('data: '+JSON.stringify({id:'sandbox',choices:[{index:0,
      delta:{role:'assistant',tool_calls:[{index:0,id:`tool-${requests}`,type:'function',function:{name,arguments:JSON.stringify(args)}}]},
      finish_reason:null}]})+'\n\n');
    const complete=()=>{res.write('data: '+JSON.stringify({id:'sandbox',choices:[{index:0,delta:{},finish_reason:'tool_calls'}]})+'\n\ndata: [DONE]\n\n');};
    if(requests===1){tool('sandbox_status');complete();}
    else res.write('data: '+JSON.stringify({id:'sandbox',choices:[{index:0,delta:{role:'assistant',content:'sandbox ready'},finish_reason:null}]})+'\n\ndata: '+JSON.stringify({id:'sandbox',choices:[{index:0,delta:{},finish_reason:'stop'}],usage:{prompt_tokens:1,completion_tokens:1,total_tokens:2}})+'\n\ndata: [DONE]\n\n');
    res.end();
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe']});
  const send=value=>child.stdin.write(JSON.stringify(value)+'\n');
  let stderr='',status,tools,answer;
  child.stderr.on('data',part=>stderr+=part);
  child.stdout.setEncoding('utf8');child.stdout.on('data',jsonLines(event=>{
    if(event.type==='ready')send({type:'configure',config:{baseUrl:`http://127.0.0.1:${server.address().port}/v1`,model:'sandbox-test',apiKey:'fixture',api:'openai-completions'}});
    if(event.type==='configured'){tools=event.tools||[];send({type:'prompt',id:'sandbox-test',text:'检查运行时，然后回答 sandbox ready。不要直接回答。'});}
    if(event.type==='tool_execution'&&event.status==='success'){
      if(event.name==='sandbox_status')status=JSON.parse(event.output||'{}');
    }
    if(event.type==='delta')answer=(answer||'')+event.text;
    if(event.type==='turn_end')child.emit('done');
  }));
  try{
    await new Promise((resolve,reject)=>{
      child.on('exit',code=>reject(new Error(`Exit ${code}: ${stderr}`)));
      child.once('done',resolve);
    });
    assert.equal(status.embeddedEngine.available!==false,true,status && JSON.stringify(status));
    assert.match(status.embeddedEngine.version,/^v/);
    assert.equal(status.binaryExecution.toolsDirectory.executable,false);
    assert.equal(status.binaryExecution.packageRuntime.available,false);
    assert.match(status.binaryExecution.toolsDirectory.path,/runtime\/tools$/);
    assert.ok(tools.includes('tool_install'));
    assert.ok(tools.includes('pty'));
    assert.ok(tools.includes('open'));
    assert.ok(!tools.includes('node'));
    assert.ok(!tools.includes('npm'));
    assert.ok(!tools.includes('npx'));
    assert.equal(status.binaryExecution.downloadedBinaries,false,status && JSON.stringify(status));
    assert.match(status.binaryExecution.dataRoot.classification,/data only|W\^X/);
    assert.match(answer,/sandbox ready/);
  }finally{child.kill();server.closeAllConnections();server.close();await rm(root,{recursive:true,force:true});}
});
