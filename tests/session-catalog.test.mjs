import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {SessionManager} from '@earendil-works/pi-coding-agent';
import {SessionCatalog} from '../runtime/session-catalog.mjs';

function setup(t){
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'chengcheng-catalog-')),workspace=path.join(root,'workspace'),directory=path.join(root,'sessions');
  fs.mkdirSync(workspace);fs.mkdirSync(directory);t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
  const catalog=new SessionCatalog(directory,workspace),manager=SessionManager.create(workspace,directory);catalog.persist(manager);
  return {root,workspace,directory,catalog,manager};
}
test('empty Pi sessions persist names and retain one header when the first reply arrives',async t=>{
  const {catalog,manager}=setup(t),id=manager.getSessionId();
  catalog.rename(await catalog.find(id),'橙橙的开发计划🙂',manager);
  assert.equal((await catalog.find(id)).name,'橙橙的开发计划🙂');
  const resumed=catalog.open(await catalog.find(id));assert.equal(resumed.getSessionName(),'橙橙的开发计划🙂');
  resumed.appendMessage({role:'user',content:'保留问题',timestamp:Date.now()});
  resumed.appendMessage({role:'assistant',content:[{type:'text',text:'保留回答'}],timestamp:Date.now(),stopReason:'stop',api:'openai-completions',provider:'fixture',model:'fixture',usage:{input:0,output:0,totalTokens:0,cost:{input:0,output:0,total:0}}});
  const lines=fs.readFileSync(resumed.getSessionFile(),'utf8').trim().split('\n').map(JSON.parse);
  assert.equal(lines.filter(e=>e.type==='session').length,1);assert.equal(lines.filter(e=>e.type==='message').length,2);
  assert.equal((await catalog.find(id)).name,'橙橙的开发计划🙂');
  for(const name of ['', '  ', 'a\nb','🙂'.repeat(81)])assert.throws(()=>catalog.rename({id,path:resumed.getSessionFile()},name,resumed));
});
test('archive, trash and restore preserve JSONL; purge deletes only the selected history',async t=>{
  const {workspace,catalog,manager}=setup(t),item=await catalog.find(manager.getSessionId());
  const userFile=path.join(workspace,'project.txt');fs.writeFileSync(userFile,'用户文件');const original=fs.readFileSync(item.path);
  catalog.change(item,'archive');assert.equal((await catalog.find(item.id)).archived,true);
  catalog.change(await catalog.find(item.id),'delete');assert.equal((await catalog.find(item.id)).deleted,true);
  catalog.change(await catalog.find(item.id),'restore');assert.equal((await catalog.find(item.id)).archived,false);assert.equal((await catalog.find(item.id)).deleted,false);
  assert.deepEqual(fs.readFileSync(item.path),original);assert.throws(()=>catalog.purge(item));
  catalog.change(item,'delete');catalog.purge(await catalog.find(item.id));
  assert.equal(fs.existsSync(item.path),false);assert.equal(fs.readFileSync(userFile,'utf8'),'用户文件');
  assert.equal((await catalog.list()).length,0);assert.ok(catalog.read()[item.id].deletedAt);
});
test('catalog rejects traversal and symlinks and does not erase corrupted metadata',async t=>{
  const {root,workspace,directory,catalog,manager}=setup(t),item=await catalog.find(manager.getSessionId());
  await assert.rejects(catalog.find('../../workspace/project.txt'));
  const outside=path.join(root,'outside.jsonl');fs.copyFileSync(item.path,outside);fs.unlinkSync(item.path);fs.symlinkSync(outside,item.path);
  assert.equal((await catalog.list()).length,0);assert.throws(()=>catalog.purge({...item,deleted:true}));assert.ok(fs.existsSync(outside));
  fs.writeFileSync(catalog.file,'corrupt');assert.throws(()=>catalog.status(item.id,{deletedAt:1}));assert.equal(fs.readFileSync(catalog.file,'utf8'),'corrupt');
  const invalid=JSON.stringify({version:1,items:{[item.id]:{archivedAt:'bad'}}});fs.writeFileSync(catalog.file,invalid);assert.throws(()=>catalog.status(item.id,{deletedAt:1}));assert.equal(fs.readFileSync(catalog.file,'utf8'),invalid);
  fs.unlinkSync(catalog.file);fs.unlinkSync(item.path);
  catalog.status('__proto__',{deletedAt:1});assert.equal(Object.hasOwn(catalog.read(),'__proto__'),true);assert.equal({}.deletedAt,undefined);
  const other=path.join(root,'other');fs.mkdirSync(other);const otherManager=SessionManager.create(other,directory);new SessionCatalog(directory,other).persist(otherManager);
  assert.equal((await catalog.list()).length,0,'Other workspace sessions must not be managed here');
});

test('pin persists independently of archive, trash and restore',async t=>{
  const {catalog,manager}=setup(t);let item=await catalog.find(manager.getSessionId());
  catalog.change(item,'pin');item=await catalog.find(item.id);assert.equal(item.pinned,true);
  catalog.change(item,'archive');item=await catalog.find(item.id);assert.equal(item.archived,true);assert.equal(item.pinned,true);
  catalog.change(item,'restore');item=await catalog.find(item.id);assert.equal(item.archived,false);assert.equal(item.pinned,true);
  catalog.change(item,'unpin');item=await catalog.find(item.id);assert.equal(item.pinned,false);
  catalog.change(item,'delete');item=await catalog.find(item.id);assert.throws(()=>catalog.change(item,'pin'),/恢复/);
});
