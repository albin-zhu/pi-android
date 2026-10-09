import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import {realpathSync} from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {Type} from 'typebox';
import {createSubagentSearchTools} from '../runtime/subagent-files.mjs';
test('bounded shell-free file tools match literals and reject escape/symlink',{timeout:10000},async()=>{
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'sub-files-'));
 try{
  await fs.writeFile(path.join(root,'hello.mjs'),'literal [text]\norange');await fs.mkdir(path.join(root,'node_modules'));await fs.writeFile(path.join(root,'node_modules','hidden.mjs'),'orange');await fs.symlink('/system',path.join(root,'escape'));
  const resolvePath=p=>{const target=realpathSync(path.resolve(root,p));if(target!==root&&!target.startsWith(root+'/'))throw new Error('outside');return target;};
  const [find,grep]=createSubagentSearchTools({Type,cwd:root,resolvePath});
  assert.equal((await find.execute('1',{pattern:'*.mjs'})).content[0].text,'hello.mjs');
  assert.match((await grep.execute('2',{pattern:'[text]'})).content[0].text,/hello.mjs:1:/);
  assert.doesNotMatch((await grep.execute('3',{pattern:'orange'})).content[0].text,/hidden/);
  await assert.rejects(()=>find.execute('4',{pattern:'*',path:'../'}),/outside/);
  const c=new AbortController();c.abort();await assert.rejects(()=>grep.execute('5',{pattern:'orange'},c.signal),/停止/);
 }finally{await fs.rm(root,{recursive:true,force:true});}
});
