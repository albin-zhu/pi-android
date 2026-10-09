import WebSocket from 'ws';
import {randomUUID} from 'node:crypto';
import {SERVICES,decodeResponse,ttsRequest,asrRequest,ASR_HOTWORDS} from './volc-speech-protocol.mjs';

// Credentials are read on start and never returned in events or exceptions.
export class SpeechBridge {
  constructor({readConfig,emit,readAgentName=()=>'Zfold',connect=(url,options)=>new WebSocket(url,options)}) {
    this.readAgentName=readAgentName;this.readConfig=readConfig;this.emit=emit;this.connect=connect;this.tts=new Map();this.asr=new Map();
  }
  event(client,id,kind,extra={}) { this.emit({type:'speech_event',client,id,kind,...extra}); }
  config() {
    const config=this.readConfig();
    if(!config || typeof config.apiKey!=='string' || !config.apiKey.trim() || /\s/.test(config.apiKey))throw Error('missing_key');
    const name=String(this.readAgentName()||'Zfold').trim().slice(0,48)||'Zfold';
    let words=Array.isArray(config.asr?.hotwords)?config.asr.hotwords:ASR_HOTWORDS.slice(1);
    // Only migrate the old shipped defaults; keep user supplied vocabulary.
    if(JSON.stringify(words)===JSON.stringify(['橙橙','Pi','API','ADB']))words=words.slice(1);
    return {...config,asr:{...config.asr,hotwords:[...new Set([name,...words])]}};
  }
  receive(command) {
    if(command.type!=='speech')return false;
    const {client,id,action}=command;
    if(typeof client!=='string'||client.length>100||typeof id!=='string'||id.length>100)return true;
    try {
      if(action==='tts_stop'){this.tts.get(client)?.close();this.tts.delete(client);return true;}
      if(action==='asr_cancel'){this.asr.get(client)?.close();this.asr.delete(client);return true;}
      if(action==='tts_start') {
        let lane=this.tts.get(client);
        if(!lane){if(this.tts.size>=4)throw Error('busy');lane=new TtsLane(this,client,this.config());this.tts.set(client,lane);}
        lane.start(id);
      } else if(action==='tts_text')this.tts.get(client)?.text(id,command.text);
      else if(action==='tts_finish')this.tts.get(client)?.finish(id);
      else if(action==='asr_start') {
        this.asr.get(client)?.close();this.asr.delete(client);
        if(this.asr.size>=4)throw Error('busy');
        const stream=new AsrStream(this,client,id,this.config());this.asr.set(client,stream);stream.open();
      } else if(action==='asr_audio'||action==='asr_finish') {
        const stream=this.asr.get(client);
        if(stream?.id===id)stream.audio(command.data,action==='asr_finish');
      }
    } catch { this.event(client,id,'error',{service:action.startsWith('tts')?'tts':'asr',code:'configuration_or_request'}); }
    return true;
  }
  close(){for(const lane of this.tts.values())lane.close();for(const stream of this.asr.values())stream.close();this.tts.clear();this.asr.clear();}
}

class Connection {
  constructor(bridge,client,service,config){this.bridge=bridge;this.client=client;this.service=service;this.config=config;this.closed=false;this.timer=null;this.socket=null;}
  connect(onOpen,onMessage) {
    const info=SERVICES[this.service];
    this.arm(10000);
    try{
      const ws=this.bridge.connect(info.url,{headers:{'X-Api-Key':this.config.apiKey,'X-Api-Resource-Id':info.resource,
        'X-Api-Connect-Id':randomUUID(),'X-Api-Request-Id':randomUUID()},followRedirects:false,handshakeTimeout:10000,maxPayload:2*1024*1024});
      this.socket=ws;
      ws.on('open',()=>{if(!this.closed){this.arm(15000);onOpen();}});
      ws.on('message',(data,binary)=>{if(this.closed)return;try{
        if(binary===false)throw Error();const message=decodeResponse(data);
        if(message.kind===15){this.fail('service_error');return;}
        this.arm(15000);onMessage(message);
      }catch{this.fail('protocol_error');}});
      ws.on('unexpected-response',(_,res)=>{const status=res.statusCode;res.destroy();this.fail(status===401||status===403?'authentication':status===429?'rate_limited':'network');});
      ws.on('error',()=>this.fail('network'));ws.on('close',()=>{if(!this.closed)this.fail('closed_early');});
    }catch{this.fail('network');}
  }
  arm(ms){clearTimeout(this.timer);if(this.closed)return;this.timer=setTimeout(()=>this.fail('timeout'),ms);this.timer.unref?.();}
  send(packet){if(this.closed)return;if(this.socket?.bufferedAmount>256*1024){this.fail('backpressure');return;}try{this.socket.send(packet);}catch{this.fail('network');}}
  close(){if(this.closed)return;this.closed=true;clearTimeout(this.timer);this.socket?.terminate();}
}

class TtsLane extends Connection {
  constructor(bridge,client,config){super(bridge,client,'tts',config);this.jobs=[];this.stage='new';this.totalText=0;}
  start(id){
    if(this.jobs.some(job=>job.id===id))return;
    if(this.closed||this.jobs.length>=16)throw Error('busy');
    this.jobs.push({id,session:randomUUID(),parts:[],end:false,bytes:0});
    if(this.stage==='new'){
      this.stage='connecting';this.connect(()=>this.send(ttsRequest(1)),message=>this.message(message));
    }else if(this.stage==='ready')this.begin();
  }
  text(id,text){
    const job=this.jobs.find(job=>job.id===id);if(!job||job.end)return;
    if(typeof text!=='string'||text.length>8000||this.totalText+text.length>32000){this.fail('text_limit');return;}
    if(text){job.parts.push(text);this.totalText+=text.length;}this.pump();
  }
  finish(id){const job=this.jobs.find(job=>job.id===id);if(job){job.end=true;this.pump();}}
  begin(){
    const job=this.jobs[0];if(!job)return;
    this.stage='starting';this.arm(15000);
    const speaker=this.config.tts?.speaker||'zh_female_vv_uranus_bigtts';
    if(!/^zh_(female|male)_[a-z0-9_]+_uranus_bigtts$/.test(speaker)){this.fail('voice');return;}
    this.send(ttsRequest(100,job.session,{event:100,namespace:'BidirectionalTTS',user:{uid:'chengcheng-phone'},
      req_params:{speaker,audio_params:{format:'pcm',sample_rate:24000}}}));
  }
  pump(){
    if(this.closed||this.stage!=='streaming')return;const job=this.jobs[0];
    while(job.parts.length&&!this.closed){const text=job.parts.shift();this.totalText-=text.length;
      this.send(ttsRequest(200,job.session,{event:200,namespace:'BidirectionalTTS',req_params:{text}}));this.arm(15000);}
    if(job.end&&!this.closed){this.stage='finishing';this.send(ttsRequest(102,job.session));}
  }
  message(m){
    if([51,153].includes(m.event)){this.fail('service_error');return;}
    if(m.event===50){this.stage='ready';this.begin();return;}
    const job=this.jobs[0];if(!job||m.id!==job.session)return;
    if(m.event===150){this.stage='streaming';this.pump();}
    else if(m.event===352){
      if(m.kind!==11||m.payload.length%2){this.fail('protocol_error');return;}
      job.bytes+=m.payload.length;if(job.bytes>24000*2*180){this.fail('audio_limit');return;}
      this.bridge.event(this.client,job.id,'tts_audio',{data:m.payload.toString('base64'),sampleRate:24000});
    }else if(m.event===152){
      if(!job.bytes){this.fail('empty_audio');return;}
      this.bridge.event(this.client,job.id,'tts_done');this.jobs.shift();this.stage='ready';
      if(this.jobs.length)this.begin();else{clearTimeout(this.timer);this.timer=setTimeout(()=>{this.close();this.bridge.tts.delete(this.client);},15000);this.timer.unref?.();}
    }
  }
  fail(code){if(this.closed)return;const jobs=this.jobs.slice();this.close();this.bridge.tts.delete(this.client);for(const job of jobs)this.bridge.event(this.client,job.id,'error',{service:'tts',code});}
}

class AsrStream extends Connection {
  constructor(bridge,client,id,config){super(bridge,client,'asr',config);this.id=id;this.sequence=1;this.pending=[];this.bytes=0;this.totalBytes=0;this.ready=false;this.ended=false;}
  open(){this.connect(()=>{
    const hotwords=Array.isArray(this.config.asr?.hotwords)?this.config.asr.hotwords:ASR_HOTWORDS;
    this.send(asrRequest(1,this.sequence++,{user:{uid:'chengcheng-phone'},audio:{format:'pcm',codec:'raw',rate:16000,bits:16,channel:1},
      request:{model_name:'bigmodel',enable_nonstream:true,enable_itn:true,enable_punc:true,show_utterances:true,result_type:'full',end_window_size:800,
        corpus:{context:JSON.stringify({hotwords:hotwords.slice(0,15).map(word=>({word:String(word).slice(0,32)}))})}}}));
    this.ready=true;this.bridge.event(this.client,this.id,'asr_ready');this.pump();
  },message=>{
    const result=message.json?.result;
    const text=Array.isArray(result)?result.map(item=>item.text||'').join(''):result?.text;
    if(typeof text==='string'&&text.length>16000){this.fail('text_limit');return;}
    if(message.final){this.bridge.event(this.client,this.id,'asr_final',{text:typeof text==='string'?text:''});this.close();this.bridge.asr.delete(this.client);}
    else if(typeof text==='string'&&text)this.bridge.event(this.client,this.id,'asr_partial',{text});
  });}
  audio(data,last){
    if(this.closed||this.ended)return;
    if(typeof data!=='string'||data.length>24000||!/^[A-Za-z0-9+/]*={0,2}$/.test(data)){this.fail('invalid_audio');return;}
    const pcm=Buffer.from(data,'base64');if(pcm.length%2){this.fail('invalid_audio');return;}
    this.bytes+=pcm.length;this.totalBytes+=pcm.length;
    if(this.bytes>192000||this.totalBytes>16000*2*120){this.fail('audio_limit');return;}
    this.pending.push({pcm,last});this.ended=last;this.pump();
  }
  pump(){if(!this.ready||this.closed)return;while(this.pending.length&&!this.closed){const {pcm,last}=this.pending.shift();this.bytes-=pcm.length;this.send(asrRequest(2,this.sequence++,pcm,last));this.arm(last?6000:15000);}}
  fail(code){if(this.closed)return;this.close();this.bridge.asr.delete(this.client);this.bridge.event(this.client,this.id,'error',{service:'asr',code});}
}
