// Shared binary framing for the phone speech bridge and host diagnostics.
import {gzipSync,gunzipSync} from 'node:zlib';
export const SERVICES = Object.freeze({
  tts: {url:'wss://openspeech.bytedance.com/api/v3/tts/bidirection', resource:'seed-tts-2.0'},
  asr: {url:'wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async', resource:'volc.seedasr.sauc.duration'},
});
export const VOICES = Object.freeze([
  {name:'vivi', label:'Vivi 2.0', speaker:'zh_female_vv_uranus_bigtts'},
  {name:'xiaohe', label:'小何 2.0', speaker:'zh_female_xiaohe_uranus_bigtts'},
  {name:'yunzhou', label:'云舟 2.0', speaker:'zh_male_m191_uranus_bigtts'},
]);
export const SAMPLE_TEXT = '你好，我是橙橙。你先忙，我来检查这段代码。API 返回了 403，可能是权限配置的问题。修好后我会告诉你，别担心。';
export const ASR_HOTWORDS = Object.freeze(['橙橙','Pi','API','ADB']);
export const MAX_FRAME = 2 * 1024 * 1024;
export const MAX_AUDIO = 16000 * 2 * 60;

export function diagnosticError(code, status) {
  // Never echo remote bodies, response headers, socket errors, or credential substrings.
  const error = new Error(code);
  error.code = code;
  if (Number.isInteger(status)) error.status = status;
  return error;
}
export function errorRecord(error) {
  const codes = new Set(['invalid_api_key','access_denied','rate_limited','http_error','network_error',
    'timeout','closed_early','protocol_error','remote_error','session_failed','empty_audio',
    'empty_transcript','audio_limit','invalid_audio','invalid_service']);
  return {ok:false, error:codes.has(error?.code) ? error.code : 'probe_failed',
    ...(Number.isInteger(error?.status) ? {httpStatus:error.status} : {})};
}
function int32(value) { const b = Buffer.alloc(4); b.writeInt32BE(value); return b; }
function sized(value) { return Buffer.concat([int32(value.length), value]); }

export function ttsRequest(event, sessionId, value = {}) {
  if (![1,2,100,101,102,200].includes(event) || (event >= 100 && !sessionId)) throw diagnosticError('protocol_error');
  return Buffer.concat([Buffer.from([0x11,0x14,0x10,0]), int32(event),
    ...(event >= 100 ? [sized(Buffer.from(sessionId))] : []), sized(Buffer.from(JSON.stringify(value)))]);
}
export function asrRequest(kind, sequence, value, last = false) {
  if (![1,2].includes(kind) || !Number.isInteger(sequence) || sequence < 1) throw diagnosticError('protocol_error');
  const payload = kind === 1 ? Buffer.from(JSON.stringify(value)) : value;
  return Buffer.concat([Buffer.from([0x11,(kind << 4) | (last ? 3 : 1),(kind === 1 ? 0x10 : 0) | 1,0]),
    int32(last ? -sequence : sequence), sized(gzipSync(payload))]);
}
export function decodeResponse(data) {
  try {
    const b = Buffer.from(data);
    if (b.length < 8 || b.length > MAX_FRAME || b[0] >> 4 !== 1 || (b[0] & 15) < 1) throw 0;
    let p = (b[0] & 15) * 4;
    const kind = b[1] >> 4, flags = b[1] & 15, serialization = b[2] >> 4, compression = b[2] & 15;
    if (![9,11,15].includes(kind) || ![0,1].includes(compression) || ![0,1].includes(serialization)) throw 0;
    const number = () => { if (p + 4 > b.length) throw 0; const n = b.readInt32BE(p); p += 4; return n; };
    const blob = () => { const size = number(); if (size < 0 || p + size > b.length) throw 0; const value = b.subarray(p,p + size); p += size; return value; };
    let code, sequence, event, id;
    if (kind === 15) code = number();
    else {
      if (flags & 1) sequence = number();
      if (flags & 4) { event = number(); id = blob().toString('utf8'); }
    }
    let payload = blob();
    if (p !== b.length) throw 0;
    if (compression === 1) payload = gunzipSync(payload, {maxOutputLength:MAX_FRAME});
    const json = serialization === 1 ? JSON.parse(payload.toString('utf8')) : undefined;
    return {kind, flags, code, sequence, event, id, payload, json, final:Boolean(flags & 2) || sequence < 0};
  } catch { throw diagnosticError('protocol_error'); }
}
