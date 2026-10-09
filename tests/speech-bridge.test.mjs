import {test} from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {gzipSync,gunzipSync} from 'node:zlib';
import {SpeechBridge} from '../runtime/speech-bridge.mjs';

const number=n=>{const b=Buffer.alloc(4);b.writeInt32BE(n);return b;};
const sized=b=>Buffer.concat([number(b.length),b]);
const reply=(event,id,payload={},audio)=>Buffer.concat([Buffer.from(audio?[0x11,0xb4,0,0]:[0x11,0x94,0x10,0]),number(event),sized(Buffer.from(id)),sized(audio||Buffer.from(JSON.stringify(payload)))]);
class Socket extends EventEmitter {sent=[];bufferedAmount=0;terminated=false;send(packet){this.sent.push(packet);this.handle?.(packet);}terminate(){this.terminated=true;}reply(packet){this.emit('message',packet,true);}}
function fixture(config={apiKey:'synthetic-secret'}) {
  const events=[],sockets=[];const bridge=new SpeechBridge({readConfig:()=>config,emit:e=>events.push(e),connect:(url,options)=>{const socket=new Socket();socket.options=options;socket.url=url;sockets.push(socket);return socket;}});
  const command=(action,id='one',extra={})=>bridge.receive({type:'speech',client:'client',id,action,...extra});
  return {bridge,command,events,sockets,config};
}
function clientTts(packet){const event=packet.readInt32BE(4);let offset=8,id='';if(event>=100){const n=packet.readInt32BE(offset);id=packet.subarray(offset+4,offset+4+n).toString();offset+=4+n;}return {event,id,json:JSON.parse(packet.subarray(offset+4))};}

test('speech commands do not consume model commands and missing credentials produce only safe events',()=>{
  const f=fixture({});assert.equal(f.bridge.receive({type:'prompt',text:'hello'}),false);
  f.command('asr_start');assert.equal(f.sockets.length,0);assert.equal(f.events[0].type,'speech_event');assert.equal(f.events[0].kind,'error');
  assert.ok(!JSON.stringify(f.events).includes('apiKey'));f.bridge.close();
});
test('TTS streams fragments, serializes sessions on one connection and labels PCM with its utterance',()=>{
  const f=fixture();f.command('tts_start');f.command('tts_text','one',{text:'你好，'});f.command('tts_text','one',{text:'我是橙橙。'});
  f.command('tts_finish');f.command('tts_start','two');f.command('tts_text','two',{text:'继续。'});f.command('tts_finish','two');
  const s=f.sockets[0];s.emit('open');s.reply(reply(50,'connection'));
  const first=clientTts(s.sent.at(-1));assert.equal(first.event,100);s.reply(reply(150,first.id));
  assert.deepEqual(s.sent.map(clientTts).filter(p=>p.event===200).map(p=>p.json.req_params.text),['你好，','我是橙橙。']);
  assert.equal(s.sent.map(clientTts).filter(p=>p.event===100).length,1);
  s.reply(reply(352,'stale',{},Buffer.alloc(20)));s.reply(reply(352,first.id,{},Buffer.alloc(320)));s.reply(reply(152,first.id));
  const second=clientTts(s.sent.at(-1));assert.equal(second.event,100);assert.notEqual(second.id,first.id);
  s.reply(reply(150,second.id));s.reply(reply(352,second.id,{},Buffer.alloc(640)));s.reply(reply(152,second.id));
  assert.deepEqual(f.events.map(e=>[e.id,e.kind]),[['one','tts_audio'],['one','tts_done'],['two','tts_audio'],['two','tts_done']]);
  assert.equal(f.sockets.length,1);assert.equal(s.options.followRedirects,false);f.bridge.close();
});
test('stop cancels queued and active TTS, ignores late audio, and a new request reloads credentials',()=>{
  const f=fixture();f.command('tts_start');const old=f.sockets[0];old.emit('open');old.reply(reply(50,'connection'));const session=clientTts(old.sent.at(-1)).id;
  f.command('tts_stop');old.reply(reply(352,session,{},Buffer.alloc(320)));assert.equal(f.events.length,0);assert.equal(old.terminated,true);
  f.config.apiKey='replacement-secret';f.command('tts_start','new');assert.equal(f.sockets[1].options.headers['X-Api-Key'],'replacement-secret');f.bridge.close();
});
test('ASR buffers until ready, streams config and PCM in order, and delivers revised final text once',()=>{
  const f=fixture();f.command('asr_start');f.command('asr_audio','one',{data:Buffer.alloc(6400).toString('base64')});f.command('asr_finish','one',{data:''});
  const s=f.sockets[0];assert.equal(s.sent.length,0);s.emit('open');assert.equal(s.sent.length,3);
  assert.equal(s.sent[0][1]>>4,1);const config=JSON.parse(gunzipSync(s.sent[0].subarray(12)));assert.equal(config.audio.rate,16000);
  assert.equal(s.sent[2].readInt32BE(4),-3);
  const asr=(text,last)=>Buffer.concat([Buffer.from([0x11,last?0x93:0x91,0x11,0]),number(last?-2:1),sized(gzipSync(Buffer.from(JSON.stringify({result:{text}}))))]);
  s.reply(asr('四零',false));s.reply(asr('403',true));s.reply(asr('late',true));
  assert.deepEqual(f.events.map(e=>e.kind),['asr_ready','asr_partial','asr_final']);assert.equal(f.events.at(-1).text,'403');assert.equal(f.bridge.asr.size,0);f.bridge.close();
});
test('ASR cancellation releases pending audio and does not deliver late results',()=>{
  const f=fixture();f.command('asr_start');f.command('asr_audio','one',{data:Buffer.alloc(6400).toString('base64')});const s=f.sockets[0];f.command('asr_cancel');s.emit('open');
  assert.equal(s.sent.length,0);assert.equal(f.events.length,0);assert.equal(s.terminated,true);f.bridge.close();
});
test('authentication and oversized buffers fail without exposing credentials or remote bodies',()=>{
  const f=fixture();f.command('tts_start');f.sockets[0].emit('unexpected-response',{}, {statusCode:401,destroy(){}});
  assert.equal(f.events.at(-1).code,'authentication');assert.equal(f.bridge.tts.size,0);
  f.command('asr_start');for(let i=0;i<31;i++)f.command('asr_audio','one',{data:Buffer.alloc(6400).toString('base64')});
  assert.equal(f.events.at(-1).code,'audio_limit');assert.equal(f.bridge.asr.size,0);
  assert.ok(!JSON.stringify(f.events).includes('synthetic-secret'));f.bridge.close();
});

 test('ASR uses the current agent name on each start and migrates legacy default hotwords',()=>{
  const config={apiKey:'synthetic-secret',asr:{hotwords:['橙橙','Pi','API','ADB']}};
  const f=fixture(config);let name='小折';f.bridge.readAgentName=()=>name;
  function words(){f.command('asr_start');const socket=f.sockets.at(-1);socket.emit('open');return JSON.parse(JSON.parse(gunzipSync(socket.sent[0].subarray(12))).request.corpus.context).hotwords.map(item=>item.word);}
  assert.deepEqual(words(),['小折','Pi','API','ADB']);name='阿蓝';assert.deepEqual(words(),['阿蓝','Pi','API','ADB']);
  config.asr.hotwords=['我的项目'];assert.deepEqual(words(),['阿蓝','我的项目']);name=' ';assert.deepEqual(words(),['Zfold','我的项目']);f.bridge.close();
});
