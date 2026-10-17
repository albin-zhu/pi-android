import {test} from 'node:test';
import assert from 'node:assert/strict';
import {encodePhoneFrame,PhoneFrameDecoder,MAX_PHONE_FRAME_BYTES} from '../runtime/phone-frames.mjs';

function rawFrame(payload){const header=Buffer.alloc(4);header.writeUInt32BE(payload.length);return Buffer.concat([header,payload]);}

test('phone frames preserve split UTF-8 and decode coalesced receipts in order',()=>{
  const messages=[{type:'bridge_ready',protocol:1,tools:[],workspaceRoot:'/手机',workspace:'/手机/workspace'},{type:'tool_result',id:'request-1',result:{text:'中文 📱'}}];
  const frame=encodePhoneFrame(messages[0]),decoded=[],decoder=new PhoneFrameDecoder();
  for(const byte of frame)decoder.push(Buffer.from([byte]),message=>decoded.push(message));
  decoder.push(Buffer.concat([encodePhoneFrame(messages[1]),encodePhoneFrame(messages[1])]),message=>decoded.push(message));
  decoder.finish();assert.deepEqual(decoded,[messages[0],messages[1],messages[1]]);
});

test('phone framing rejects zero and oversize lengths before a payload arrives',()=>{
  for(const length of [0,MAX_PHONE_FRAME_BYTES+1,0xffffffff]){
    const header=Buffer.alloc(4);header.writeUInt32BE(length);
    assert.throws(()=>new PhoneFrameDecoder().push(header,()=>assert.fail('invalid frame delivered')),error=>error.code==='PHONE_PROTOCOL');
  }
  assert.throws(()=>encodePhoneFrame({text:'x'.repeat(MAX_PHONE_FRAME_BYTES)}),error=>error.code==='PHONE_PROTOCOL');
});

test('the exact maximum phone frame length remains valid',()=>{
  const message={text:'x'.repeat(MAX_PHONE_FRAME_BYTES-Buffer.byteLength(JSON.stringify({text:''})))};
  const frame=encodePhoneFrame(message);assert.equal(frame.readUInt32BE(0),MAX_PHONE_FRAME_BYTES);
  const decoder=new PhoneFrameDecoder();let result;
  decoder.push(frame,value=>{result=value;});decoder.finish();assert.deepEqual(result,message);
});

test('phone framing rejects malformed UTF-8, JSON and non-object frames',()=>{
  for(const payload of [Buffer.from([0x7b,0x22,0xff,0x22,0x3a,0x31,0x7d]),Buffer.from('{'),Buffer.from('[]'),Buffer.from('null')]){
    assert.throws(()=>new PhoneFrameDecoder().push(rawFrame(payload),()=>assert.fail('invalid frame delivered')),error=>error.code==='PHONE_PROTOCOL');
  }
});

test('EOF in a phone frame never becomes a partial receipt',()=>{
  const frame=encodePhoneFrame({type:'tool_result',id:'1',result:{performed:true}});
  for(const bytes of [1,3,4,frame.length-1]){
    const decoder=new PhoneFrameDecoder();decoder.push(frame.subarray(0,bytes),()=>assert.fail('partial receipt delivered'));
    assert.throws(()=>decoder.finish(),error=>error.code==='PHONE_PROTOCOL'&&/inside a frame/.test(error.message));
  }
});
