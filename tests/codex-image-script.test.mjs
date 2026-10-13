import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { parseArgs, runImageScript, imageMime } from '../runtime/codex-image-script.mjs';
const token = `header.${Buffer.from(JSON.stringify({'https://api.openai.com/auth':{chatgpt_account_id:'test-account'}})).toString('base64url')}.signature`;
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX2QAAAAASUVORK5CYII=', 'base64');
async function fixture(t) {
  const dir = await fs.realpath(await fs.mkdtemp(path.join(os.tmpdir(), 'image-script-')));
  t.after(() => fs.rm(dir,{recursive:true,force:true}));
  const promptFile = path.join(dir,'prompt.txt'); await fs.writeFile(promptFile,'adult warrior, forest');
  return { dir, options: { promptFile, references:[], outputDir:path.join(dir,'out') } };
}
test('CLI rejects missing prompt, unknown options and excessive references', () => {
  assert.throws(()=>parseArgs([])); assert.throws(()=>parseArgs(['--token','secret']));
  assert.throws(()=>parseArgs(['--status',...Array(6).fill(['--reference','x']).flat()]));
  assert.equal(parseArgs(['--status']).status,true);
});
test('status never calls image endpoint and does not expose auth', async () => {
  const result = await runImageScript({status:true},{getAuth:async()=>token,fetchImpl:()=>assert.fail()});
  assert.deepEqual(result,{status:'oauth_ready',entitlementVerified:false});
  await assert.rejects(runImageScript({status:true},{getAuth:async()=>{throw new Error(token);}}),e=>!e.message.includes(token));
});
test('generation sends fixed endpoint, saves unique PNG and excludes token from output', async t => {
  const {options} = await fixture(t);
  const dependencies={getAuth:async()=>token,fetchImpl:async(url,args)=>{
    assert.equal(url,'https://chatgpt.com/backend-api/codex/images/generations');
    assert.equal(args.redirect,'error'); assert.equal(JSON.parse(args.body).model,'gpt-image-2');
    return new Response(JSON.stringify({data:[{b64_json:png.toString('base64')}]}));
  }};
  const a=await runImageScript(options,dependencies),b=await runImageScript(options,dependencies);
  assert.notEqual(a.savedPath,b.savedPath);assert.deepEqual(await fs.readFile(a.savedPath),png);
  assert.equal(a.operation,'generate');assert.ok(!JSON.stringify(a).includes(token));
});
test('edit embeds only explicit references',async t=>{
  const {dir,options}=await fixture(t); const reference=path.join(dir,'ref.png');await fs.writeFile(reference,png);
  options.references=[reference];
  const result=await runImageScript(options,{getAuth:async()=>token,fetchImpl:async(url,args)=>{
    assert.ok(url.endsWith('/edits')); assert.equal(JSON.parse(args.body).images[0].image_url,`data:image/png;base64,${png.toString('base64')}`);
    return new Response(JSON.stringify({data:[{b64_json:png.toString('base64')}]}));
  }});assert.equal(result.operation,'edit');
});
test('HTTP errors never echo response bodies',async t=>{
  const {options}=await fixture(t);
  for(const status of [401,403,429,500]) await assert.rejects(runImageScript(options,{getAuth:async()=>token,fetchImpl:async()=>new Response(token,{status})}),e=>e.message.includes(String(status))&&!e.message.includes(token));
});
test('bad image, empty prompt and symlink output are rejected',async t=>{
  const {dir,options}=await fixture(t);
  assert.throws(()=>imageMime(Buffer.from('fake')));
  const dependencies={getAuth:async()=>token,fetchImpl:async()=>new Response(JSON.stringify({data:[{b64_json:'ZmFrZQ=='}]}))};
  await assert.rejects(runImageScript(options,dependencies),/invalid PNG/);
  await fs.symlink(path.join(dir,'out'),path.join(dir,'link'));options.outputDir=path.join(dir,'link','nested');
  await assert.rejects(runImageScript(options,dependencies),/symlinks/);
  await fs.writeFile(options.promptFile,' '); await assert.rejects(runImageScript(options,dependencies),/empty/);
});
