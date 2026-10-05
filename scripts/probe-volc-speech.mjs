#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import {parseArgs} from 'node:util';
import {readApiKey,probeAuth,synthesizeSample,recognizeSample,pcmWave,errorRecord,VOICES} from './lib/volc-speech-probe.mjs';

async function main() {
  const {values} = parseArgs({options:{env:{type:'string',default:'.env'},out:{type:'string',default:'artifacts/speech-evaluation'},samples:{type:'boolean',default:false},help:{type:'boolean',short:'h'}}});
  if (values.help) {
    console.log('node scripts/probe-volc-speech.mjs [--env .env] [--out artifacts/speech-evaluation] [--samples]\n默认仅验证两项服务的 WebSocket 握手，不发送音频或文本。\n--samples：生成 3 段固定中文试听，并将第一段合成音发送给 ASR；会消耗语音 API 额度。\n不录制麦克风、不读取聊天，不接受命令行密钥或自定义服务端地址。');
    return;
  }
  const apiKey = readApiKey(fs.readFileSync(values.env,'utf8'));
  const directory = path.resolve(values.out,new Date().toISOString().replaceAll(/[:.]/g,'-'));
  fs.mkdirSync(directory,{recursive:true,mode:0o700});
  const report = {at:new Date().toISOString(),samplesRequested:values.samples,auth:{},tts:[],asr:null};
  for (const service of ['tts','asr']) {
    try { report.auth[service] = await probeAuth(apiKey,service); }
    catch (error) { report.auth[service] = errorRecord(error); }
  }
  let firstPcm;
  if (values.samples && report.auth.tts.ok) {
    for (const voice of VOICES) {
      try {
        const {pcm,metrics} = await synthesizeSample(apiKey,voice);
        fs.writeFileSync(path.join(directory,voice.name+'.wav'),pcmWave(pcm),{mode:0o600});
        report.tts.push(metrics); firstPcm ??= pcm;
      } catch (error) { report.tts.push({name:voice.name,...errorRecord(error)}); }
    }
  }
  if (values.samples && report.auth.asr.ok && firstPcm) {
    try { report.asr = await recognizeSample(apiKey,firstPcm); }
    catch (error) { report.asr = errorRecord(error); }
  }
  // Defense in depth; never write a key even if a provider response echoes it.
  const output = JSON.stringify(report,null,2).replaceAll(apiKey,'[redacted]')+'\n';
  fs.writeFileSync(path.join(directory,'results.json'),output,{mode:0o600});
  console.log(output.trim());
  console.log('报告：'+path.join(directory,'results.json'));
  if (!report.auth.tts.ok || !report.auth.asr.ok || (values.samples && (report.tts.length !== VOICES.length || report.tts.some(item => !item.ok) || !report.asr?.ok))) process.exitCode = 1;
}
main().catch(() => { console.error('语音验证未完成：检查参数、本地 .env 与输出目录；密钥不会写入日志。'); process.exitCode = 1; });
