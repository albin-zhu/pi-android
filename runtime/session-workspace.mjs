import fs from 'node:fs';
import path from 'node:path';
/** Working directories remain inside the existing app-owned file-tool root. */
export function resolveSessionWorkspace(value,{root,current}){
  if(typeof value!=='string'||!value.trim()||/[\x00-\x1f]/.test(value))throw new Error('请输入有效的工作目录');
  let input=value.trim();
  for(const alias of ['/storage/pi','/storage/emulated/0/pi','/sdcard/pi'])if(input===alias||input.startsWith(alias+'/')){input=root+input.slice(alias.length);break;}
  const requested=path.resolve(current,input),base=path.resolve(root);
  if(requested!==base&&!requested.startsWith(base+path.sep))throw new Error('工作目录必须位于 /storage/pi 内');
  let real;
  try{real=fs.realpathSync(requested);}catch{throw new Error('工作目录不存在或无法访问');}
  let realRoot;try{realRoot=fs.realpathSync(base);}catch{realRoot=base;}
  if(real!==realRoot&&!real.startsWith(realRoot+path.sep))throw new Error('工作目录不能通过链接跳出 /storage/pi');
  if(!fs.statSync(real).isDirectory())throw new Error('工作目录必须是目录，不是文件');
  try{fs.accessSync(real,fs.constants.R_OK|fs.constants.W_OK|fs.constants.X_OK);}catch{throw new Error('工作目录不可读写');}
  return real;
}
