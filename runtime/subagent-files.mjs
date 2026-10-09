import fs from 'node:fs/promises';
import path from 'node:path';
// Bounded, shell-free read-only search. Symlinks are never traversed.
export function createSubagentSearchTools({Type,cwd,resolvePath}){
  const pattern=Type.String({minLength:1,maxLength:256}),root=Type.Optional(Type.String({maxLength:1024}));
  const walk=async function*(directory,signal){
    const queue=[{file:resolvePath(directory||cwd),depth:0}];let visited=0;
    while(queue.length&&visited++<2000){
      if(signal?.aborted)throw new Error('子任务已停止');const {file,depth}=queue.shift();
      let info;try{info=await fs.lstat(file);}catch{continue;}if(info.isSymbolicLink())continue;
      const checked=resolvePath(file);
      if(info.isDirectory()){
        if(depth>=16)continue;let names;try{names=await fs.readdir(checked);}catch{continue;}
        for(const name of names.sort()){if(['.git','node_modules'].includes(name))continue;if(queue.length>=2000)break;queue.push({file:path.join(checked,name),depth:depth+1});}
      }else if(info.isFile())yield {file:checked,size:info.size};
    }
  };
  const result=(lines,notice)=>({content:[{type:'text',text:lines.join('\n')||'(无匹配；'+notice+')'}],details:{bounded:true,notice}});
  return [{name:'find',label:'子Agent查文件',description:'只读文件名匹配，pattern支持*和?；最多扫描2000条目/16层，跳过符号链接、.git、node_modules；最多200个匹配。',parameters:Type.Object({pattern,path:root}),async execute(_id,args,signal){
    const regex=new RegExp('^'+args.pattern.split('').map(c=>c==='*'?'.*':c==='?'?'.':c.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')).join('')+'$');const lines=[];
    for await(const entry of walk(args.path,signal)){if(regex.test(path.basename(entry.file))||regex.test(path.relative(cwd,entry.file)))lines.push(path.relative(cwd,entry.file));if(lines.length>=200)break;}
    return result(lines,'扫描和结果有上限，并非完整遍历');
  }},{name:'grep',label:'子Agent查文本',description:'只读字面量文本搜索，不执行正则；最多读取合计4MB，单文件512KB，最多200个结果；跳过二进制和符号链接。',parameters:Type.Object({pattern,path:root}),async execute(_id,args,signal){
    const lines=[];let bytes=0;
    for await(const entry of walk(args.path,signal)){
      if(entry.size>512*1024||bytes+entry.size>4*1024*1024)continue;bytes+=entry.size;
      let buffer;try{buffer=await fs.readFile(resolvePath(entry.file));}catch{continue;}if(buffer.includes(0))continue;
      let index=0;for(const line of buffer.toString('utf8').split('\n')){index++;if(line.includes(args.pattern))lines.push(path.relative(cwd,entry.file)+':'+index+': '+line.slice(0,500));if(lines.length>=200)break;}if(lines.length>=200||bytes>=4*1024*1024)break;
    }
    return result(lines,'仅字面量搜索；扫描、字节和结果有上限');
  }}];
}
