import {stopRuntimeChild} from './runtime-cleanup.mjs';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import {jsonLines} from '../runtime/protocol.mjs';
import {startConnectorFixture} from '../scripts/connector-fixture.mjs';

test('real bundled Pi discovers and calls a saved MCP connector without exposing credentials',{timeout:30000},async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'pi-connector-')),fixture=await startConnectorFixture();
  let requests=0,serverFailure,connectorId,answer='';const timeline=[];
  const model=http.createServer(async(req,res)=>{
    try{
      let body='';for await(const part of req)body+=part;const input=JSON.parse(body);requests++;
      assert.ok(!body.includes('fixture-bearer-secret'),'Private connector token reached model');
      assert.ok(input.tools.some(tool=>tool.function.name==='connector_tools'));
      assert.ok(!input.tools.some(tool=>tool.function.name==='echo'),'External schemas should be loaded on demand');
      const results=input.messages.filter(message=>message.role==='tool');
      res.writeHead(200,{'content-type':'text/event-stream'});
      const chunk=(delta,finish=null)=>res.write('data: '+JSON.stringify({id:'mcp-model',object:'chat.completion.chunk',model:'connector-test',choices:[{index:0,delta,finish_reason:finish}]})+'\n\n');
      if(results.length<2){
        let name='connector_tools',args={query:'echo'};
        if(results.length){const catalog=JSON.parse(results[0].content);assert.equal(catalog.tools[0].connectorId,connectorId);assert.deepEqual(catalog.tools[0].inputSchema.required,['value']);name='connector_call';args={connectorId,tool:'echo',arguments:{value:'PHONE_AGENT_OK'}};}
        chunk({role:'assistant',tool_calls:[{index:0,id:'external-'+results.length,type:'function',function:{name,arguments:JSON.stringify(args)}}]});chunk({},'tool_calls');
      }else{assert.match(results[1].content,/MCP_RESULT:PHONE_AGENT_OK/);chunk({role:'assistant',content:'已通过手机连接完成工具调用。'});chunk({},'stop');}
      res.end('data: [DONE]\n\n');
    }catch(error){serverFailure=error;res.writeHead(500);res.end('Fixture assertion failed');}
  });
  await new Promise(resolve=>model.listen(0,'127.0.0.1',resolve));
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe'],env:{...process.env,PI_CODING_AGENT_DIR:path.join(root,'pi')}});
  let stderr='';child.stderr.on('data',part=>stderr+=part);const send=value=>child.stdin.write(JSON.stringify(value)+'\n');
  try{
    await new Promise((resolve,reject)=>{
      child.on('exit',code=>reject(new Error(`Runtime exit ${code}: ${stderr}`)));child.stdout.setEncoding('utf8');
      child.stdout.on('data',jsonLines(event=>{try{
        if(event.type==='ready')send({type:'connector_manage',action:'save',requestId:'setup',config:{name:'Fixture connection',url:fixture.origin+'/bearer',auth:'bearer',token:'fixture-bearer-secret'}});
        if(event.type==='connector_result'&&event.requestId==='setup'){
          assert.equal(event.ok,true);assert.equal(event.server.state,'connected');connectorId=event.id;
          send({type:'configure',config:{baseUrl:`http://127.0.0.1:${model.address().port}/v1`,model:'connector-test',apiKey:'fixture',api:'openai-completions'}});
        }
        if(event.type==='configured')send({type:'prompt',id:'mcp-turn',text:'查询连接的 echo 工具，并调用它验证手机 Agent。'});
        if(event.type==='tool_execution')timeline.push(event);
        if(event.type==='delta')answer+=event.text;
        if(event.type==='turn_end')resolve();
        if(event.type==='error')reject(new Error(event.message));
      }catch(error){reject(error);}},reject));
    });
    if(serverFailure)throw serverFailure;
    assert.equal(requests,3);assert.equal(fixture.state.calls.length,1);assert.match(answer,/完成工具调用/);
    assert.ok(timeline.some(event=>event.name==='connector_call'&&event.status==='success'));
  }finally{await stopRuntimeChild(child);model.closeAllConnections();await new Promise(resolve=>model.close(resolve));await fixture.close();await rm(root,{recursive:true,force:true});}
});
