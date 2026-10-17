import {test} from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import {mkdtemp,rm} from 'node:fs/promises';
import {PhoneSocketClient} from '../runtime/phone-socket-client.mjs';
import {PhoneFrameDecoder,encodePhoneFrame} from '../runtime/phone-frames.mjs';

const ready={type:'bridge_ready',protocol:1,tools:['device_info','ui_tap'],workspaceRoot:'/android/data',workspace:'/android/data/workspace'};
function deferred(){let resolve;const promise=new Promise(done=>{resolve=done;});return {promise,resolve};}
// Exercise real AF_UNIX stream behavior on macOS too; only the address factory changes.
async function socketPeer(t,accept){
  const root=await mkdtemp(path.join(os.tmpdir(),'phone-socket-')),socketPath=path.join(root,'bridge.sock'),sockets=new Set();
  const server=net.createServer(socket=>{sockets.add(socket);socket.once('close',()=>sockets.delete(socket));socket.on('error',()=>{});accept(socket);});
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(socketPath,resolve);});
  t.after(async()=>{for(const socket of sockets)socket.destroy();await new Promise(resolve=>server.close(resolve));await rm(root,{recursive:true,force:true});});
  const client=new PhoneSocketClient({endpoint:'test-phone',createConnection:()=>net.createConnection({path:socketPath})});
  t.after(()=>client.close());
  return client;
}
function receive(socket,callback){
  const decoder=new PhoneFrameDecoder();socket.on('data',chunk=>decoder.push(chunk,callback));
}

test('an accepted action followed by EOF is uncertain and is never replayed on a later connection',async t=>{
  let connections=0;const requests=[];
  const client=await socketPeer(t,socket=>{
    connections++;socket.write(encodePhoneFrame(ready));
    receive(socket,message=>{
      requests.push(message);
      if(requests.length===1)socket.end();
      else socket.write(encodePhoneFrame({type:'tool_result',id:message.id,result:{observed:true}}));
    });
  });
  const error=await client.call('ui_tap',{nodeId:'real-node'}).catch(error=>error);
  assert.equal(error.code,'NATIVE_UNCONFIRMED');assert.match(error.message,/execution is uncertain/);
  assert.equal(client.bridge.pending.size,0);assert.equal(client.connection,null);assert.equal(connections,1);
  assert.deepEqual(await client.call('device_info',{}),{observed:true});
  assert.equal(connections,2);assert.equal(requests.length,2);
  assert.equal(requests[0].name,'ui_tap');assert.equal(requests[1].name,'device_info');assert.notEqual(requests[0].id,requests[1].id);
});

test('cancellation waits for the terminal receipt and preserves a completion that won the race',async t=>{
  const dispatched=deferred();let cancelCount=0,requestId;
  const client=await socketPeer(t,socket=>{
    socket.write(encodePhoneFrame(ready));
    receive(socket,message=>{
      if(message.type==='tool_request'){requestId=message.id;dispatched.resolve();}
      else if(message.type==='tool_cancel'){
        cancelCount++;assert.equal(message.id,requestId);
        socket.write(encodePhoneFrame({type:'tool_heartbeat',id:requestId}));
        socket.write(encodePhoneFrame({type:'tool_result',id:requestId,result:{performed:true,cancellationRaced:true}}));
      }
    });
  });
  const controller=new AbortController(),receipt=client.call('ui_tap',{nodeId:'node'},{signal:controller.signal});
  await dispatched.promise;controller.abort();
  assert.deepEqual(await receipt,{performed:true,cancellationRaced:true});assert.equal(cancelCount,1);assert.equal(client.bridge.pending.size,0);
});

test('EOF after sending cancellation does not claim that the phone stopped',async t=>{
  const dispatched=deferred();
  const client=await socketPeer(t,socket=>{
    socket.write(encodePhoneFrame(ready));
    receive(socket,message=>{if(message.type==='tool_request')dispatched.resolve();else if(message.type==='tool_cancel')socket.end();});
  });
  const controller=new AbortController(),receipt=client.call('ui_tap',{}, {signal:controller.signal}).catch(error=>error);
  await dispatched.promise;controller.abort();
  assert.equal((await receipt).code,'NATIVE_UNCONFIRMED');assert.equal(client.bridge.pending.size,0);
});

test('malformed framing after one real receipt preserves it and rejects other pending calls as uncertain',async t=>{
  const requests=[];
  const client=await socketPeer(t,socket=>{
    socket.write(encodePhoneFrame(ready));
    receive(socket,message=>{
      requests.push(message);
      if(requests.length===2)socket.write(Buffer.concat([encodePhoneFrame({type:'tool_result',id:requests[0].id,result:{performed:true}}),Buffer.alloc(4)]));
    });
  });
  const first=client.call('ui_tap',{}),second=client.call('device_info',{}).catch(error=>error);
  assert.deepEqual(await first,{performed:true});
  const error=await second;assert.equal(error.code,'NATIVE_UNCONFIRMED');assert.equal(error.cause.code,'PHONE_PROTOCOL');
  assert.equal(client.bridge.pending.size,0);
});

test('abort during readiness dispatches no phone request',async t=>{
  const accepted=deferred();let bytesReceived=0;
  const client=await socketPeer(t,socket=>{socket.on('data',chunk=>{bytesReceived+=chunk.length;});accepted.resolve();});
  const controller=new AbortController(),receipt=client.call('ui_tap',{}, {signal:controller.signal}).catch(error=>error);
  await accepted.promise;controller.abort();
  assert.equal((await receipt).code,'PHONE_CANCELLED');assert.equal(bytesReceived,0);assert.equal(client.bridge.pending.size,0);
});

test('truncated readiness fails without labeling any action as dispatched',async t=>{
  const client=await socketPeer(t,socket=>{const frame=encodePhoneFrame(ready);socket.end(frame.subarray(0,frame.length-1));});
  const error=await client.call('ui_tap',{}).catch(error=>error);
  assert.equal(error.code,'PHONE_PROTOCOL');assert.equal(client.bridge.sequence,0);assert.equal(client.bridge.pending.size,0);
});
