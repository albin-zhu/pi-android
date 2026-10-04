import {test} from 'node:test';
import assert from 'node:assert/strict';
import {runtimePackCatalog} from '../runtime/runtime-packs.mjs';

test('runtime pack roadmap covers requested native agent runtime',()=>{
  assert.equal(runtimePackCatalog.architecture,'arm64-v8a');
  assert.equal(runtimePackCatalog.libc,'bionic');
  assert.equal(runtimePackCatalog.executionChannel.compat28.writableExec,true);
  const statuses=new Map(runtimePackCatalog.packs.map(item=>[item.id,item]));
  for(const id of ['git-base','gnu-userland','build-toolchain','go-toolchain'])assert.ok(statuses.has(id),id);
  const build=statuses.get('build-toolchain');
  for(const tool of ['make','clang','clang++','gcc-compatible','g++-compatible','cmake','python'])assert.ok(build.provides.includes(tool),tool);
  assert.match(build.policy,/aliases to Android\/bionic Clang/);
  assert.match(statuses.get('git-base').verified,/git 2\.56\.0/);
  for(const item of statuses.values())assert.equal(item.status,'built',item.id);
});
