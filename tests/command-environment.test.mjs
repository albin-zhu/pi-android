import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {createBashToolDefinition} from '@earendil-works/pi-coding-agent';
import {resolveRuntimeCommand,shellWithEnvironment} from '../runtime/command-environment.mjs';

test('native command selection agrees with shell PATH, including non-executable shadows',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'runtime-env-'));
  try{
    const directories=['tools','pkg','pack'].map(name=>path.join(root,name));
    for(const directory of directories){fs.mkdirSync(directory);fs.writeFileSync(path.join(directory,'demo'),'#!/bin/sh\nexit 0\n',{mode:0o755});}
    const resolve=()=>resolveRuntimeCommand('demo',{directories,packageRoot:root});
    const shell=()=>execFileSync('/bin/sh',['-c','command -v demo'],{env:{PATH:directories.join(':')},encoding:'utf8'}).trim();
    assert.equal(resolve(),shell());
    fs.chmodSync(path.join(directories[0],'demo'),0o644);
    assert.equal(resolve(),shell());assert.equal(resolve(),path.join(directories[1],'demo'));
    assert.throws(()=>resolveRuntimeCommand('missing',{directories,packageRoot:root}),/未安装/);
  }finally{fs.rmSync(root,{recursive:true,force:true});}
});

test('Pi shell gets a fresh environment without shell-expanding its values',async()=>{
  const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'runtime-shell-')));
  let version=0;
  try{
    const tool=shellWithEnvironment(createBashToolDefinition,root,{shellPath:'/bin/sh',exposeSessionEnvironment:false},async()=>({RUNTIME_LITERAL:'$(echo must-not-expand)',ENV_VERSION:String(++version)}));
    const args={command:'printf "%s|%s|%s" "$RUNTIME_LITERAL" "$ENV_VERSION" "$PWD"'};
    const first=await tool.execute('one',args),second=await tool.execute('two',args);
    assert.equal(first.content[0].text,`$(echo must-not-expand)|1|${root}`);
    assert.equal(second.content[0].text,`$(echo must-not-expand)|2|${root}`);
  }finally{fs.rmSync(root,{recursive:true,force:true});}
});
