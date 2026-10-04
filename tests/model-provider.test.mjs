import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,mkdtemp,rm,readFile,writeFile} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import {jsonLines} from '../runtime/protocol.mjs';

test('legacy empty built-in provider config is repaired and configures xai',{timeout:20000},async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'zfold-provider-'));
  await mkdir(path.join(root,'.pi','agent'),{recursive:true});
  await writeFile(path.join(root,'.pi','agent','models.json'),JSON.stringify({providers:{xai:{}}}));
  const server=http.createServer((req,res)=>{
    res.writeHead(200,{'content-type':'application/json'});
    res.end(JSON.stringify({object:'list',data:[]}));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe']});
  const send=value=>child.stdin.write(JSON.stringify(value)+'\n');
  let stderr='',configured;
  child.stderr.on('data',part=>stderr+=part);
  child.stdout.setEncoding('utf8');child.stdout.on('data',jsonLines(event=>{
    if(event.type==='ready')send({type:'configure',config:{provider:'xai',model:'grok-4.6'}});
    if(event.type==='configured')configured=event;
    if(event.type==='error')child.emit('runtime-error',new Error(event.message));
  },error=>child.emit('runtime-error',error),1024*1024));
  try{
    await new Promise((resolve,reject)=>{
      child.on('exit',code=>reject(new Error(`Exit ${code}: ${stderr}`)));
      child.once('runtime-error',reject);
      const timer=setTimeout(()=>reject(new Error('provider timeout')),15000);
      const poll=setInterval(()=>{if(configured){clearTimeout(timer);clearInterval(poll);resolve();}},30);
    });
    assert.equal(configured.provider,'xai');
    const stored=JSON.parse(await readFile(path.join(root,'.pi','agent','models.json'),'utf8'));
    assert.equal(stored.providers.xai.name,'xAI');
  }finally{
    child.kill();server.closeAllConnections();server.close();await rm(root,{recursive:true,force:true});
  }
});
