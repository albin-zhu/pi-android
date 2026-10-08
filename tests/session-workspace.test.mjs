import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';import os from 'node:os';import path from 'node:path';
import {resolveSessionWorkspace} from '../runtime/session-workspace.mjs';
test('session workspace validates existing directories, aliases, traversal and symlinks',()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'session-cwd-')),outside=fs.mkdtempSync(path.join(os.tmpdir(),'outside-cwd-'));
 try{
  const current=path.join(root,'workspace'),project=path.join(root,'project');fs.mkdirSync(current);fs.mkdirSync(project);
  const options={root,current};assert.equal(resolveSessionWorkspace('../project',options),project);assert.equal(resolveSessionWorkspace('/storage/pi/project',options),project);
  fs.writeFileSync(path.join(root,'file'),'x');fs.symlinkSync(outside,path.join(root,'escape'));
  for(const value of ['', '/storage/pi/missing','/storage/pi/file',outside,'/storage/pi/../escape','/storage/pi/escape','bad\npath'])assert.throws(()=>resolveSessionWorkspace(value,options));
 }finally{fs.rmSync(root,{recursive:true,force:true});fs.rmSync(outside,{recursive:true,force:true});}
});
