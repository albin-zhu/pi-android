// Diagnostic only: this is not connected to the Android microphone or player.
import {parseEnv} from 'node:util';
import {randomUUID} from 'node:crypto';
import WebSocket from 'ws';

import {SERVICES,VOICES,SAMPLE_TEXT,ASR_HOTWORDS,MAX_FRAME,MAX_AUDIO,diagnosticError,errorRecord,ttsRequest,asrRequest,decodeResponse} from '../../runtime/volc-speech-protocol.mjs';
export {SERVICES,VOICES,SAMPLE_TEXT,ASR_HOTWORDS,MAX_FRAME,MAX_AUDIO,diagnosticError,errorRecord,ttsRequest,asrRequest,decodeResponse};
export function readApiKey(source) {
  const key = parseEnv(source).VOLC_API_KEY?.trim();
  if (!key || /\s/.test(key)) throw new Error('请在本地 .env 中设置有效的 VOLC_API_KEY');
  return key;
}
// Injectable transport for deterministic tests; the command line cannot change endpoints.
export function exchange({service, apiKey, onOpen, onMessage, timeoutMs = 45000, connect = (url, options) => new WebSocket(url,options)}) {
  if (!Object.hasOwn(SERVICES,service)) return Promise.reject(diagnosticError('invalid_service'));
  return new Promise((resolve,reject) => {
    const config = SERVICES[service], started = performance.now();
    let ws, settled = false;
    const timer = setTimeout(() => finish(diagnosticError('timeout')),timeoutMs);
    function finish(error, value) {
      if (settled) return;
      settled = true; clearTimeout(timer);
      // Also aborts a pending handshake; no provider retry or credential forwarding.
      ws?.terminate();
      error ? reject(error) : resolve(value);
    }
    function call(callback, message) {
      if (settled) return;
      try { callback?.({ws, started, finish, message, active:() => !settled}); }
      catch (error) { finish(error?.code ? error : diagnosticError('protocol_error')); }
    }
    try {
      ws = connect(config.url,{headers:{'X-Api-Key':apiKey,'X-Api-Resource-Id':config.resource,
        'X-Api-Request-Id':randomUUID(),'X-Api-Connect-Id':randomUUID()},
        followRedirects:false, handshakeTimeout:10000, maxPayload:MAX_FRAME});
      ws.on('open',() => call(onOpen));
      ws.on('message',(data,isBinary) => {
        if (settled) return;
        if (isBinary === false) { finish(diagnosticError('protocol_error')); return; }
        try {
          const message = decodeResponse(data);
          if (message.kind === 15) finish(diagnosticError('remote_error'));
          else call(onMessage,message);
        } catch { finish(diagnosticError('protocol_error')); }
      });
      ws.on('unexpected-response',(_,response) => {
        const status = response.statusCode;
        response.destroy();
        finish(diagnosticError(status === 401 ? 'invalid_api_key' : status === 403 ? 'access_denied' : status === 429 ? 'rate_limited' : 'http_error',status));
      });
      ws.on('error',() => finish(diagnosticError('network_error')));
      ws.on('close',() => finish(diagnosticError('closed_early')));
    } catch { finish(diagnosticError('network_error')); }
  });
}
export function probeAuth(apiKey,service,options = {}) {
  return exchange({...options, service, apiKey, onOpen:({started,finish}) =>
    finish(null,{ok:true,stage:'websocket_handshake_only',connectMs:Math.round(performance.now()-started)})});
}

export function synthesizeSample(apiKey,voice,options = {}) {
  const text=options.text??SAMPLE_TEXT;
  if(typeof text!=='string'||!text.trim()||text.length>512)throw diagnosticError('text_limit');
  const sessionId = randomUUID(), chunks = [];
  let firstAudio, firstText, opened, bytes = 0, sessionStarted = false;
  return exchange({...options, service:'tts', apiKey,
    onOpen:({ws}) => { opened = performance.now(); ws.send(ttsRequest(1)); },
    onMessage:({ws,started,finish,message:m,active}) => {
      if ([51,153].includes(m.event)) { finish(diagnosticError('session_failed')); return; }
      if (m.event === 50) {
        ws.send(ttsRequest(100,sessionId,{user:{uid:'chengcheng-evaluation'},event:100,namespace:'BidirectionalTTS',
          req_params:{speaker:voice.speaker,audio_params:{format:'pcm',sample_rate:16000}}}));
      } else if (m.id === sessionId && m.event === 150 && !sessionStarted) {
        sessionStarted = true;
        // Deliberately send multiple fragments, not one request per sentence.
        const parts = [...text.matchAll(/.{1,8}/gu)].map(match => match[0]);
        const sendNext = () => {
          if (!active()) return;
          try {
            const text = parts.shift();
            if (text === undefined) { ws.send(ttsRequest(102,sessionId)); return; }
            firstText ??= performance.now();
            ws.send(ttsRequest(200,sessionId,{event:200,namespace:'BidirectionalTTS',req_params:{text}}));
            setTimeout(sendNext,80);
          } catch { finish(diagnosticError('network_error')); }
        };
        sendNext();
      } else if (m.id === sessionId && m.event === 352) {
        if (!sessionStarted || firstText === undefined || m.kind !== 11 || !m.payload.length || m.payload.length % 2) throw diagnosticError('protocol_error');
        firstAudio ??= performance.now(); bytes += m.payload.length;
        if (bytes > MAX_AUDIO) { finish(diagnosticError('audio_limit')); return; }
        chunks.push(m.payload);
      } else if (m.id === sessionId && m.event === 152) {
        if (!bytes) { finish(diagnosticError('empty_audio')); return; }
        finish(null,{pcm:Buffer.concat(chunks),metrics:{ok:true,name:voice.name,label:voice.label,speaker:voice.speaker,
          sampleRate:16000,text,connectMs:Math.round(opened-started),
          firstAudioFromConnectMs:Math.round(firstAudio-started),firstAudioFromTextMs:Math.round(firstAudio-firstText),
          totalMs:Math.round(performance.now()-started),audioDurationMs:Math.round(bytes/32)}});
      }
    }});
}

export async function recognizeSample(apiKey,pcm,options = {}) {
  if (!Buffer.isBuffer(pcm) || !pcm.length || pcm.length % 2 || pcm.length > MAX_AUDIO) throw diagnosticError('invalid_audio');
  let sequence = 1, opened, firstText, lastSent, transcript = '', packets = 0;
  return exchange({...options, service:'asr', apiKey,
    onOpen:({ws,active,finish}) => {
      opened = performance.now();
      ws.send(asrRequest(1,sequence++,{user:{uid:'chengcheng-evaluation'},audio:{format:'pcm',codec:'raw',rate:16000,bits:16,channel:1},
        request:{model_name:'bigmodel',enable_nonstream:true,enable_itn:true,enable_punc:true,show_utterances:true,result_type:'full',end_window_size:800,
          corpus:{context:JSON.stringify({hotwords:ASR_HOTWORDS.map(word => ({word}))})}}}));
      let offset = 0;
      const sendNext = () => {
        if (!active()) return;
        try {
          const end = Math.min(offset+6400,pcm.length), last = end === pcm.length;
          ws.send(asrRequest(2,sequence++,pcm.subarray(offset,end),last)); offset = end;
          if (last) lastSent = performance.now();
          else setTimeout(sendNext,200);
        } catch { finish(diagnosticError('network_error')); }
      };
      sendNext();
    },
    onMessage:({started,finish,message:m}) => {
      packets++;
      const result = m.json?.result;
      const text = Array.isArray(result) ? result.map(item => item.text || '').join('') : result?.text;
      if (typeof text === 'string' && text.trim()) { transcript = text.trim(); firstText ??= performance.now(); }
      if (m.final) {
        if (!transcript) { finish(diagnosticError('empty_transcript')); return; }
        finish(null,{ok:true,transcript,source:'synthetic_tts_audio',packets,connectMs:Math.round(opened-started),
          firstTextFromAudioMs:Math.round(firstText-opened),
          finalAfterAudioMs:lastSent === undefined ? null : Math.round(performance.now()-lastSent),
          totalMs:Math.round(performance.now()-started)});
      }
    }});
}
export function pcmWave(pcm,sampleRate = 16000) {
  const header = Buffer.alloc(44);
  header.write('RIFF'); header.writeUInt32LE(36+pcm.length,4); header.write('WAVEfmt ',8);
  header.writeUInt32LE(16,16); header.writeUInt16LE(1,20); header.writeUInt16LE(1,22);
  header.writeUInt32LE(sampleRate,24); header.writeUInt32LE(sampleRate*2,28);
  header.writeUInt16LE(2,32); header.writeUInt16LE(16,34); header.write('data',36); header.writeUInt32LE(pcm.length,40);
  return Buffer.concat([header,pcm]);
}
