import {stopRuntimeChild} from './runtime-cleanup.mjs';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {realpathSync} from 'node:fs';
import http from 'node:http';
import {jsonLines} from '../runtime/protocol.mjs';

test('local .pi skills persist, enter configured Pi resources, and delete safely', {timeout:30000},async()=>{
  const requestedRoot=await mkdtemp(path.join(os.tmpdir(),'zfold-skills-'));const root=realpathSync(requestedRoot);
  const server=http.createServer(async(req,res)=>{
    for await(const part of req){}
    res.writeHead(200,{'content-type':'text/event-stream'});
    res.end('data: '+JSON.stringify({id:'skills',choices:[{index:0,delta:{role:'assistant',content:'ready.'},finish_reason:null}]})+'\n\n'+
      'data: '+JSON.stringify({id:'skills',choices:[{index:0,delta:{},finish_reason:'stop'}],usage:{prompt_tokens:1,completion_tokens:1,total_tokens:2}})+'\n\ndata: [DONE]\n\n');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe']});
  const closed=new Promise(resolve=>child.once('close',resolve));
  const send=value=>child.stdin.write(JSON.stringify(value)+'\n');
  let stderr='',configured=0;
  const waiters=[];const events=[];
  child.stderr.on('data',part=>stderr+=part);
  child.stdout.setEncoding('utf8');child.stdout.on('data',jsonLines(event=>{
    if(event.type==='error')for(const pending of waiters.splice(0))pending.reject(new Error(event.message));
    else{const index=waiters.findIndex(waiter=>waiter.type===event.type&&(!waiter.action||event.action===waiter.action));if(index>=0)waiters.splice(index,1)[0].resolve(event);else events.push(event);}
  },error=>{for(const pending of waiters.splice(0))pending.reject(error);}));
  child.on('exit',(code,signal)=>{for(const pending of waiters.splice(0))pending.reject(new Error(`Runtime exited ${signal||code}: ${stderr}`));});
  const next=(type,action)=>{const index=events.findIndex(event=>event.type===type&&(!action||event.action===action));if(index>=0)return Promise.resolve(events.splice(index,1)[0]);return new Promise((resolve,reject)=>waiters.push({type,action,resolve,reject}));};
  try{
    await next('ready');
    send({type:'skills_manage',action:'list'});
    const initial=await next('skills');
    assert.equal(initial.roots.project,path.join(root,'workspace','.pi','skills'));
    assert.equal(initial.roots.global,path.join(root,'.pi','skills'));

    send({type:'skills_manage',action:'write',scope:'project',name:'note-helper',description:'整理本地笔记',content:'把用户给出的要点整理为三段。'});
    const written=await next('skills','write');
    assert.equal(written.skills.find(skill=>skill.name==='note-helper').scope,'project');

    send({type:'skills_manage',action:'write',scope:'global',name:'global-helper',description:'读取全局能力',content:'报告当前设备状态。'});
    const global=await next('skills','write');
    assert.equal(global.skills.find(skill=>skill.name==='global-helper').scope,'global');

    send({type:'configure',config:{baseUrl:`http://127.0.0.1:${server.address().port}/v1`,model:'skills-test',apiKey:'fixture',api:'openai-completions'}});
    const configuration=await next('configured');
    configured++;
    assert.ok(configuration.skills.includes('note-helper'));
    assert.ok(configuration.skills.includes('global-helper'));
    assert.ok(configuration.skills.includes('phone-control'));
    assert.ok(configuration.skills.includes('skill-creator'));
    for(const tool of ['read','edit','write','ls','bash','screen_read','split_screen','phone_task'])assert.ok(configuration.tools.includes(tool),`${tool} missing`);
    assert.ok(!configuration.tools.includes('powershell'));
    assert.ok(!configuration.tools.includes('grep'));
    assert.ok(!configuration.tools.includes('find'));

    send({type:'skills_manage',action:'delete',scope:'project',name:'note-helper'});
    await next('configured');configured++;
    const deleted=await next('skills','delete');
    assert.ok(!deleted.skills.some(skill=>skill.name==='note-helper'));
    assert.equal(configured,2);

    send({type:'skills_manage',action:'reload'});
    await next('configured');configured++;
    await next('skills','reload');
    assert.equal(configured,3);
  }finally{
    await stopRuntimeChild(child);server.closeAllConnections();server.close();
    // SIGTERM dispatch is not process exit: wait before removing writable state.
    await closed;
    await rm(root,{recursive:true,force:true});
  }
});
