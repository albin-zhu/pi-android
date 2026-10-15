import {test} from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import {randomUUID} from 'node:crypto';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {encodePhoneFrame,PhoneFrameDecoder} from '../runtime/phone-frames.mjs';

const cli=fileURLToPath(new URL('../runtime/phone-open.mjs',import.meta.url));
function run(args,env={}){
  return new Promise((resolve,reject)=>{
    // Bun consumes the first leading "--" before passing script arguments.
    const child=spawn(process.execPath,[cli,...(process.versions.bun?['--']:[]),...args],{env:{...process.env,ZFOLD_PHONE_SOCKET:'',ZFOLD_PHONE_PATH_MAP:'',...env},timeout:5000});
    let stdout='',stderr='';
    child.stdout.on('data',data=>stdout+=data);child.stderr.on('data',data=>stderr+=data);
    child.once('error',reject);child.once('close',(code,signal)=>resolve({code,signal,stdout,stderr}));
  });
}

test('invalid opener targets and unmapped guest files fail without a usable phone endpoint',async()=>{
  for(const args of [[],['one','two'],['--invalid'],[''],['/tmp/private.txt']]){
    const result=await run(args);
    assert.equal(result.code,1);assert.equal(result.stdout,'');assert.equal(result.signal,null);
    assert.doesNotMatch(result.stderr,/PHONE_ENDPOINT_MISSING|at .*phone-open/);
  }
  const result=await run(['--','--help']);
  assert.equal(result.code,1);assert.match(result.stderr,/bind mapping/);assert.equal(result.stdout,'');
});

test('opener exit status distinguishes Android handoff, refusal, fallback and uncertain execution',{skip:process.platform!=='linux'},async t=>{
  const endpoint='phone-open-test-'+randomUUID(),sockets=new Set();
  let receipt;
  const server=net.createServer(socket=>{
    sockets.add(socket);socket.once('close',()=>sockets.delete(socket));socket.on('error',()=>{});
    socket.write(encodePhoneFrame({type:'bridge_ready',protocol:1,tools:['open'],workspaceRoot:'/android/data',workspace:'/android/data/workspace'}));
    const decoder=new PhoneFrameDecoder();
    socket.on('data',chunk=>decoder.push(chunk,message=>{
      if(receipt===null)socket.end();
      else socket.write(encodePhoneFrame({type:'tool_result',id:message.id,result:receipt}));
    }));
  });
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen('\0'+endpoint,resolve);});
  t.after(async()=>{for(const socket of sockets)socket.destroy();await new Promise(resolve=>server.close(resolve));});
  for(const [value,code,error] of [
    [{opened:true},0,null],
    [{opened:false,confirmationRequested:true},0,/not installed yet/],
    [{opened:false,status:'declined'},1,/declined/],
    [{opened:false,fallbackOpened:true},1,/containing directory instead/],
    [{opened:false},1,/did not open/],
    [null,2,/uncertain/]
  ]){
    receipt=value;
    const result=await run(['https://example.com/'],{ZFOLD_PHONE_SOCKET:endpoint});
    assert.equal(result.code,code,JSON.stringify(result));assert.equal(result.signal,null);assert.equal(result.stdout,'');
    if(error)assert.match(result.stderr,error);else assert.equal(result.stderr,'');
  }
});
