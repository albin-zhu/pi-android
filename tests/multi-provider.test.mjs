import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,rm,readFile} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import {jsonLines} from '../runtime/protocol.mjs';

test('runtime preserves multiple custom providers and switches without recreating the session',{timeout:30000},async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'zfold-providers-'));
  const server=http.createServer(async(req,res)=>{
    for await(const part of req){}
    res.writeHead(200,{'content-type':'text/event-stream'});
    res.end('data: '+JSON.stringify({id:'providers',choices:[{index:0,delta:{role:'assistant',content:'ready.'},finish_reason:null}]})+'\n\n'+
      'data: '+JSON.stringify({id:'providers',choices:[{index:0,delta:{},finish_reason:'stop'}],usage:{prompt_tokens:1,completion_tokens:1,total_tokens:2}})+'\n\ndata: [DONE]\n\n');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const url=`http://127.0.0.1:${server.address().port}/v1`;
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe']});
  const closed=new Promise(resolve=>child.once('close',resolve));
  const send=value=>child.stdin.write(JSON.stringify(value)+'\n');
  let stderr='',configured=0,catalog=null;
  child.stderr.on('data',part=>stderr+=part);
  child.stdout.setEncoding('utf8');child.stdout.on('data',jsonLines(event=>{
    if(event.type==='ready')send({type:'configure',config:{baseUrl:url,model:'first-test',apiKey:'one',api:'openai-completions'}});
    if(event.type==='configured'){
      configured++;
      if(configured===1){catalog=null;send({type:'configure',config:{provider:'gateway',baseUrl:url,model:'second-test',apiKey:'two',api:'openai-completions'}});}
      else send({type:'models_catalog'});
    }
    if(event.type==='models_catalog')catalog=event;
    if(event.type==='error')throw new Error(event.message);
  },error=>{throw error;},8*1024*1024));
  try{
    await new Promise((resolve,reject)=>{
      child.on('exit',code=>reject(new Error(`Runtime exited ${code}: ${stderr}`)));
      const timer=setTimeout(()=>reject(new Error('multi-provider timeout')),20000);
      child.stdout.on('close',()=>clearTimeout(timer));
      const poll=setInterval(()=>{
        // A catalog emitted for the first configure may arrive before the second catalog.
        if(configured>=2&&catalog?.selected?.provider==='gateway'){clearTimeout(timer);clearInterval(poll);resolve();}
      },30);
    });
    assert.equal(configured,2);
    const gateway=catalog.providers.find(provider=>provider.id==='gateway');
    const custom=catalog.providers.find(provider=>provider.id==='custom');
    assert.ok(gateway?.models.some(model=>model.id==='second-test'));
    assert.ok(custom?.models.some(model=>model.id==='first-test'));
    assert.equal(catalog.selected.provider,'gateway');
    assert.equal(catalog.selected.model,'second-test');
    const stored=JSON.parse(await readFile(path.join(root,'.pi','agent','models.json'),'utf8'));
    assert.equal(stored.providers.custom.models[0].id,'first-test');
    assert.equal(stored.providers.gateway.models[0].id,'second-test');
  }finally{
    child.kill();server.closeAllConnections();server.close();
    // Wait for pending model/session writes before deleting the fixture directory.
    await closed;
    await rm(root,{recursive:true,force:true});
  }
});
