import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mergeSpeechConfig,privateCommands,provision} from '../scripts/provision-speech.mjs';

test('speech provisioning replaces only the key in an existing Volc configuration',() => {
  const old={schemaVersion:1,provider:'volc',apiKey:'old-key',tts:{speaker:'chosen-voice',sampleRate:16000},custom:{preserve:true}};
  const next=mergeSpeechConfig(old,'new-key');
  assert.equal(next.apiKey,'new-key');assert.equal(next.tts.speaker,old.tts.speaker);assert.equal(next.tts.sampleRate,16000);
  assert.deepEqual(next.custom,old.custom);assert.equal(old.apiKey,'old-key');assert.ok(next.asr.hotwords.includes('Zfold'));
  assert.throws(() => mergeSpeechConfig({provider:'other'},'key'));
  assert.throws(() => mergeSpeechConfig({schemaVersion:2},'key'));
});

test('private install uses stdin, verifies byte-for-byte and refuses a normal write to an emulator',() => {
  const calls=[];let stored='',emulator=false;
  const run=(_cmd,args,options) => {
    calls.push({args,options});
    if(args.includes('get-state'))return {status:0,stdout:'device\n'};
    if(args.includes('getprop'))return {status:0,stdout:emulator?'1\n':'\n'};
    if(options.input){stored=options.input;return {status:0,stdout:''};}
    if(args.at(-1).includes('stat -c %a'))return {status:0,stdout:'600\n'};
    return {status:0,stdout:stored};
  };
  const r=provision({serial:'physical-test',apiKey:'synthetic-secret',run});
  assert.equal(r.verified,true);assert.equal(r.activation,'configuration_only');
  assert.ok(calls.every(call => !call.args.join(' ').includes('synthetic-secret')));
  assert.equal(JSON.parse(stored).apiKey,'synthetic-secret');
  assert.equal(calls.filter(call => call.options.input).length,1);
  assert.ok(!calls.some(call => call.args.includes('force-stop')));
  emulator=true;assert.throws(() => provision({serial:'emulator-5554',apiKey:'synthetic-secret',run}),/emulator_requires_test/);
});

test('verification mismatch does not report success or leak process output',() => {
  let reads=0;
  const run=(_cmd,args,options) => {
    if(args.includes('get-state'))return {status:0,stdout:'device\n'};
    if(args.includes('getprop'))return {status:0,stdout:''};
    if(options.input)return {status:0,stdout:''};
    return {status:0,stdout:reads++?'mismatched-secret':''};
  };
  assert.throws(() => provision({serial:'physical-test',apiKey:'synthetic-secret',run}),/^Error: verification_failed$/);
  const commands=privateCommands(true);
  assert.match(commands.file,/agent-smoke-credentials/);assert.match(commands.write,/mktemp/);assert.match(commands.write,/test ! -L/);
});
