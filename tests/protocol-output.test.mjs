import {test} from 'node:test';
import assert from 'node:assert/strict';
import {writeJsonLine} from '../runtime/protocol-output.mjs';
test('protocol output survives short writes and EAGAIN without duplicated UTF-8',()=>{
 const chunks=[];let calls=0;
 writeJsonLine(1,{text:'橙橙🙂'},(fd,data,offset,length)=>{if(++calls===2)throw Object.assign(new Error(),{code:'EAGAIN'});const size=Math.min(3,length);chunks.push(data.subarray(offset,offset+size));return size;},()=>{});
 assert.equal(Buffer.concat(chunks).toString(),JSON.stringify({text:'橙橙🙂'})+'\n');
});
test('output fails on closed pipe and zero progress',()=>{
 assert.throws(()=>writeJsonLine(1,{},()=>{throw Object.assign(new Error('closed'),{code:'EPIPE'});}),/closed/);
 assert.throws(()=>writeJsonLine(1,{},()=>0),/no progress/);
});
