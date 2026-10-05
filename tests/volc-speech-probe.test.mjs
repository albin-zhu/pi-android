import {test} from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {gzipSync,gunzipSync} from 'node:zlib';
import {readApiKey,ttsRequest,asrRequest,decodeResponse,exchange,probeAuth,synthesizeSample,recognizeSample,pcmWave,errorRecord,VOICES,SAMPLE_TEXT} from '../scripts/lib/volc-speech-probe.mjs';

const int = n => { const b = Buffer.alloc(4); b.writeInt32BE(n); return b; };
const blob = b => Buffer.concat([int(b.length),b]);
function ttsReply(event,id,json = {},audio) {
  return Buffer.concat([Buffer.from(audio ? [0x11,0xb4,0,0] : [0x11,0x94,0x10,0]),int(event),blob(Buffer.from(id)),blob(audio || Buffer.from(JSON.stringify(json)))]);
}
function asrReply(sequence,text,last = false) {
  return Buffer.concat([Buffer.from([0x11,last ? 0x93 : 0x91,0x11,0]),int(last ? -sequence : sequence),
    blob(gzipSync(Buffer.from(JSON.stringify({result:{text}}))))]);
}
class Socket extends EventEmitter {
  sent = []; terminated = false;
  send(b) { this.sent.push(b); this.onSend?.(b); }
  terminate() { this.terminated = true; }
  reply(b) { this.emit('message',b,true); }
}
function transport(socket,onOpen = s => s.emit('open')) {
  return (url,options) => { socket.url = url; socket.options = options; queueMicrotask(() => onOpen(socket)); return socket; };
}

test('dotenv credentials are data, including quotes and comments, never shell input',() => {
  assert.equal(readApiKey('export VOLC_API_KEY="synthetic-key" # comment\nIGNORED=123'),'synthetic-key');
  assert.equal(readApiKey("VOLC_API_KEY='literal-$(echo)'"),'literal-$(echo)');
  assert.throws(() => readApiKey('VOLC_API_KEY="has whitespace secret"'),error => !error.message.includes('secret'));
});
test('TTS connection request has the documented binary layout and UTF-8 lengths',() => {
  assert.equal(ttsRequest(1).toString('hex'),'1114100000000001000000027b7d');
  const b = ttsRequest(200,'中文',{text:'橙橙'});
  assert.equal(b.readInt32BE(8),6);assert.equal(b.subarray(12,18).toString(),'中文');
  assert.equal(b.readInt32BE(18),Buffer.byteLength('{"text":"橙橙"}'));
  assert.throws(() => ttsRequest(100),/protocol_error/);
});
test('TTS raw audio and gzipped ASR final packets decode without interpreting audio as JSON',() => {
  const pcm=Buffer.from([1,0,2,0]), tts=decodeResponse(ttsReply(352,'turn-1',{},pcm));
  assert.equal(tts.event,352);assert.equal(tts.id,'turn-1');assert.deepEqual(tts.payload,pcm);
  const asr=decodeResponse(asrReply(10,'返回了 403。',true));
  assert.equal(asr.sequence,-10);assert.equal(asr.final,true);assert.equal(asr.json.result.text,'返回了 403。');
});
test('ASR audio request uses negative final sequence and PCM bytes, config uses JSON',() => {
  const config=asrRequest(1,1,{audio:{rate:16000}});
  assert.equal(config[1],0x11);assert.equal(config[2],0x11);
  assert.deepEqual(JSON.parse(gunzipSync(config.subarray(12))),{audio:{rate:16000}});
  const pcm=Buffer.from([1,0,2,0]),last=asrRequest(2,2,pcm,true);
  assert.equal(last[1],0x23);assert.equal(last.readInt32BE(4),-2);assert.deepEqual(gunzipSync(last.subarray(12)),pcm);
});
test('truncated frames, unsupported encoding and decompression bombs fail with no body in errors',() => {
  const valid=asrReply(1,'synthetic-secret');
  for (let length=0;length<valid.length;length++) assert.throws(() => decodeResponse(valid.subarray(0,length)),/^Error: protocol_error$/);
  const bad=Buffer.from(valid);bad[2]=0x12;assert.throws(() => decodeResponse(bad),/protocol_error/);
  const bomb=Buffer.concat([Buffer.from([0x11,0x91,0x11,0]),int(1),blob(gzipSync(Buffer.alloc(3*1024*1024)))]);
  assert.throws(() => decodeResponse(bomb),/protocol_error/);
});
test('401 ends handshake once without echoing errors, following redirects or sending audio',async () => {
  const s=new Socket();
  const result=await probeAuth('synthetic-secret','tts',{connect:transport(s,s => {
    s.emit('unexpected-response',{}, {statusCode:401,destroy(){}});
    s.emit('error',new Error('synthetic-secret'));
  })}).catch(errorRecord);
  assert.deepEqual(result,{ok:false,error:'invalid_api_key',httpStatus:401});
  assert.equal(s.options.followRedirects,false);assert.match(s.url,/^wss:\/\/openspeech\.bytedance\.com\//);
  assert.equal(s.sent.length,0);assert.equal(s.terminated,true);
});
test('handshake success is labelled only as handshake; unknown service cannot receive a key',async () => {
  const s=new Socket(),r=await probeAuth('synthetic-secret','asr',{connect:transport(s)});
  assert.equal(r.stage,'websocket_handshake_only');assert.equal(s.sent.length,0);
  let called=false;await assert.rejects(probeAuth('synthetic-secret','other',{connect(){called=true}}),/invalid_service/);
  assert.equal(called,false);
});
test('timeout closes the transport and ignores late packets and private error messages',async () => {
  const s=new Socket();let count=0;
  await assert.rejects(exchange({service:'tts',apiKey:'synthetic-secret',timeoutMs:10,connect:transport(s),onMessage(){count++}}),/timeout/);
  s.reply(ttsReply(50,'late'));s.emit('error',new Error('synthetic-secret'));assert.equal(count,0);assert.equal(s.terminated,true);
  assert.deepEqual(errorRecord(Error('synthetic-secret')),{ok:false,error:'probe_failed'});
});
test('streamed TTS waits for session ack, ignores other sessions and finishes with nonempty audio',async () => {
  const s=new Socket(),fragments=[],pcm=Buffer.alloc(3200,1);
  let session;
  s.onSend=b => {
    const event=b.readInt32BE(4);
    if(event===1)queueMicrotask(() => s.reply(ttsReply(50,'connection')));
    else if(event===100){const n=b.readInt32BE(8);session=b.subarray(12,12+n).toString();queueMicrotask(() => s.reply(ttsReply(150,session)));}
    else if(event===200){const n=b.readInt32BE(8),json=JSON.parse(b.subarray(16+n));fragments.push(json.req_params.text);}
    else if(event===102){s.reply(ttsReply(352,'stale',{},Buffer.alloc(32)));s.reply(ttsReply(352,session,{},pcm));s.reply(ttsReply(152,session));}
  };
  const r=await synthesizeSample('synthetic-secret',VOICES[0],{connect:transport(s)});
  assert.equal(fragments.join(''),SAMPLE_TEXT);assert.ok(fragments.length>1);
  assert.deepEqual(r.pcm,pcm);assert.equal(r.metrics.audioDurationMs,100);assert.equal(r.metrics.sampleRate,16000);
});
test('ASR only submits final text, replacing the intermediate hypothesis; terminal audio is marked once',async () => {
  const s=new Socket();let chunks=0;
  s.onSend=b => {
    if(b[1]>>4!==2)return;
    chunks++;
    if(b[1]&2)queueMicrotask(() => s.reply(asrReply(3,'API 返回了 403。',true)));
    else s.reply(asrReply(2,'API 返回了四'));
  };
  const r=await recognizeSample('synthetic-secret',Buffer.alloc(12800),{connect:transport(s)});
  assert.equal(r.transcript,'API 返回了 403。');assert.equal(r.source,'synthetic_tts_audio');assert.equal(chunks,2);
  assert.equal(s.sent.filter(b => (b[1]>>4)===2 && (b[1]&2)).length,1);
});
test('empty recognition and oversized samples cannot be reported as successful',async () => {
  const s=new Socket();s.onSend=b => {if(b[1]>>4===2)queueMicrotask(() => s.reply(asrReply(2,'',true)))};
  await assert.rejects(recognizeSample('synthetic-secret',Buffer.alloc(320),{connect:transport(s)}),/empty_transcript/);
  await assert.rejects(recognizeSample('synthetic-secret',Buffer.alloc(16000*2*61)),/invalid_audio/);
});
test('WAV headers describe signed 16-bit mono PCM rather than mislabeled compressed audio',() => {
  const wave=pcmWave(Buffer.alloc(3200));
  assert.equal(wave.subarray(0,4).toString(),'RIFF');assert.equal(wave.readUInt32LE(24),16000);
  assert.equal(wave.readUInt16LE(22),1);assert.equal(wave.readUInt16LE(34),16);
  assert.equal(wave.readUInt32LE(40),3200);assert.equal(wave.length,3244);
});
