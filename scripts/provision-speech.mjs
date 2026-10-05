#!/usr/bin/env node
// Install a user-supplied speech credential without placing it in an APK or argv.
// This only stores configuration: the current Android build does not consume it yet.
import fs from 'node:fs';
import {parseArgs} from 'node:util';
import {spawnSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
import {readApiKey,ASR_HOTWORDS} from './lib/volc-speech-probe.mjs';

const PACKAGE = 'dev.zfold.assistant';
const MAX_CONFIG_BYTES = 65536;

export function mergeSpeechConfig(existing,apiKey) {
  if (!apiKey || apiKey.length > 4096 || /\s/.test(apiKey)) throw Error('invalid_key');
  if (existing !== null && (typeof existing !== 'object' || Array.isArray(existing))) throw Error('invalid_config');
  if (existing?.schemaVersion !== undefined && existing.schemaVersion !== 1) throw Error('unsupported_schema');
  const old = existing || {};
  if (old.provider !== undefined && old.provider !== 'volc') throw Error('different_provider');
  for (const field of ['tts','asr']) if (old[field] !== undefined && (old[field] === null || typeof old[field] !== 'object' || Array.isArray(old[field]))) throw Error('invalid_config');
  return {
    schemaVersion:1,
    provider:'volc',
    ...old,
    tts:{resourceId:'seed-tts-2.0',speaker:'zh_female_vv_uranus_bigtts',sampleRate:24000,...old.tts},
    asr:{resourceId:'volc.seedasr.sauc.duration',sampleRate:16000,hotwords:ASR_HOTWORDS,...old.asr},
    apiKey,
  };
}

export function privateCommands(testing) {
  const directory = `no_backup/${testing ? 'agent-smoke-credentials' : 'credentials'}`;
  const file = `${directory}/speech.json`;
  const guard = `set -eu; test ! -L no_backup; test ! -L ${directory}; test ! -L ${file};`;
  return {
    directory,file,
    read:`${guard} if test -e ${file}; then test -f ${file}; test "$(stat -c %s ${file})" -le ${MAX_CONFIG_BYTES}; cat ${file}; fi`,
    write:`${guard} umask 077; mkdir -p ${directory}; chmod 700 ${directory}; temporary=$(mktemp ${directory}/speech.XXXXXX); trap 'rm -f "$temporary"' EXIT; cat > "$temporary"; chmod 600 "$temporary"; mv -f "$temporary" ${file}`,
    mode:`${guard} stat -c %a ${file}`,
  };
}
// Shell quoting applies only to fixed commands and temporary variable syntax, never credentials.
const quote = value => "'"+value.replaceAll("'","'\\''")+"'";

export function provision({serial,apiKey,testing = false,run = spawnSync}) {
  if (!serial || !/^[A-Za-z0-9._:-]+$/.test(serial)) throw Error('invalid_serial');
  function adb(args,input) {
    const result = run('adb',['-s',serial,...args],{input,encoding:'utf8',timeout:15000,maxBuffer:MAX_CONFIG_BYTES});
    // Do not expose stdout/stderr in failures: either may contain a credential.
    if (result.error || result.status !== 0) throw Error('adb_failed');
    return result.stdout || '';
  }
  if (adb(['get-state']).trim() !== 'device') throw Error('device_not_ready');
  const emulator = adb(['shell','getprop','ro.kernel.qemu']).trim() === '1';
  if (emulator && !testing) throw Error('emulator_requires_test');
  if (!emulator && testing) throw Error('test_requires_emulator');
  const commands = privateCommands(testing);
  function shell(command,input) { return adb(['shell','run-as',PACKAGE,'sh','-c',quote(command)],input); }
  const previous = shell(commands.read).trim();
  let config;
  try { config = mergeSpeechConfig(previous ? JSON.parse(previous) : null,apiKey); }
  catch { throw Error('invalid_config'); }
  const payload = JSON.stringify(config)+'\n';
  if (Buffer.byteLength(payload) > MAX_CONFIG_BYTES) throw Error('config_too_large');
  shell(commands.write,payload);
  if (shell(commands.read) !== payload || shell(commands.mode).trim() !== '600') throw Error('verification_failed');
  return {written:true,verified:true,serial,privatePath:commands.file,permissions:'600',appRestarted:false,activation:'configuration_only'};
}

function main() {
  const {values} = parseArgs({options:{serial:{type:'string'},env:{type:'string',default:'.env'},test:{type:'boolean',default:false},help:{type:'boolean',short:'h'}}});
  if (values.help) {
    console.log('node scripts/provision-speech.mjs --serial <physical-device> [--env .env]\n从 .env 读取 VOLC_API_KEY，写入应用私有 no_backup/credentials/speech.json，并回读校验。\n不重启应用、不修改其他凭据、不启用尚未接入的云语音。--test 仅写模拟器的隔离配置。');
    return;
  }
  const apiKey = readApiKey(fs.readFileSync(values.env,'utf8'));
  console.log(JSON.stringify(provision({serial:values.serial,apiKey,testing:values.test}),null,2));
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try { main(); }
  catch {
    console.error('语音配置未完成写入或校验：检查目标设备、调试 APK、.env 和私有配置结构。未输出任何凭据或设备原始日志。');
    process.exitCode = 1;
  }
}
