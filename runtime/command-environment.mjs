import fs from 'node:fs';
import path from 'node:path';

export function resolveRuntimeCommand(name,{directories,packageRoot,packages={}}){
  const runnable=file=>{try{fs.accessSync(file,fs.constants.X_OK);return fs.statSync(file).isFile();}catch{return false;}};
  const direct=directories.map(directory=>path.join(directory,name)).find(runnable);
  if(direct)return direct;
  const installed=Object.values(packages).flatMap(record=>record.files||[])
    .filter(file=>path.basename(file)===name).map(file=>path.join(packageRoot,file)).filter(runnable)
    .sort((a,b)=>(a.includes('/bin/')?0:a.includes('/libexec/')?1:2)-(b.includes('/bin/')?0:b.includes('/libexec/')?1:2));
  if(installed.length)return installed[0];
  throw new Error(`未安装可执行命令：${name}；可调用 pkg 安装`);
}

export function shellWithEnvironment(createShell,root,options,getEnvironment){
  const create=environment=>createShell(root,{...options,commandPrefix:'umask 077',
    spawnHook:context=>({...context,env:{...context.env,...environment}})});
  return {...create({}),async execute(...args){
    const environment=await getEnvironment(args[2]);
    return create(environment).execute(...args);
  }};
}
