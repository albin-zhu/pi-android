import {stopRuntimeChild} from './runtime-cleanup.mjs';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,rm,readFile,readdir} from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import {jsonLines} from '../runtime/protocol.mjs';

test('real Pi sends one current camera image per user round and keeps originals on disk',{timeout:30000},async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'zfold-camera-'));
  const pictures=['iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQAAAABJRU5ErkJggg==','iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII='];
  let requests=0,failure;const payloads=[];
  const server=http.createServer(async(req,res)=>{
    let body='';for await(const part of req)body+=part;
    try{
      const payload=JSON.parse(body);payloads.push(payload);requests++;
      const images=payload.messages.flatMap(message=>Array.isArray(message.content)?message.content:[]).filter(part=>part.type==='image_url');
      assert.equal(images.length,1);assert.ok(images[0].image_url.url.includes(pictures[requests-1]));
      assert.ok(!body.includes('_zfoldCameraFrame'));
      const names=payload.tools.map(t=>t.function.name);
      for(const name of ['camera_capture','phone_call','phone_sms','contact_search','phone_capabilities'])assert.ok(names.includes(name));
      res.writeHead(200,{'content-type':'text/event-stream'});
      res.end('data: '+JSON.stringify({id:'camera',choices:[{index:0,delta:{role:'assistant',content:'收到当前画面。'},finish_reason:null}]})+'\n\ndata: '+JSON.stringify({id:'camera',choices:[{index:0,delta:{},finish_reason:'stop'}]})+'\n\ndata: [DONE]\n\n');
    }catch(error){failure=error;res.writeHead(400);res.end(String(error));}
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe'],env:{...process.env,PI_CODING_AGENT_DIR:path.join(root,'pi')}});
  const send=value=>child.stdin.write(JSON.stringify(value)+'\n');let stderr='',sessionId;
  child.stderr.on('data',value=>stderr+=value);
  const prompt=index=>send({type:'prompt',id:'camera-'+index,sessionId,text:'看这张图 '+index,cameraFrame:{frameId:String(index),facing:index?'front':'back',capturedAt:Date.now()},images:[{source:'camera',frameId:String(index),data:pictures[index],mime:'image/png'}]});
  try{
    await new Promise((resolve,reject)=>{
      child.on('exit',code=>reject(new Error('Exit '+code+': '+stderr)));
      child.stdout.setEncoding('utf8');child.stdout.on('data',jsonLines(event=>{
        try{
          if(event.type==='ready')send({type:'configure',config:{baseUrl:`http://127.0.0.1:${server.address().port}/v1`,model:'camera-fixture',apiKey:'fixture',api:'openai-completions'}});
          if(event.type==='configured'){sessionId=event.sessionId;prompt(0);}
          if(event.type==='turn_end'){if(requests===1)prompt(1);else resolve();}
          if(event.type==='error')reject(failure||new Error(event.message));
        }catch(error){reject(error);}
      },reject));
    });
    assert.equal(requests,2);assert.equal(payloads.length,2);
    const sessionFiles=await readdir(path.join(root,'sessions'),{recursive:true});let history='';
    for(const file of sessionFiles.filter(name=>name.endsWith('.jsonl')))history+=await readFile(path.join(root,'sessions',file),'utf8');
    assert.ok(history.includes(pictures[0])&&history.includes(pictures[1]),'Persisted history lost a frame');
  }finally{await stopRuntimeChild(child);server.closeAllConnections();server.close();await rm(root,{recursive:true,force:true});}
});
