import fs from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {SessionManager} from '@earendil-works/pi-coding-agent';

export function atomicJson(file,value){
  const temporary=`${file}.${randomUUID()}.tmp`;
  try{fs.writeFileSync(temporary,JSON.stringify(value)+'\n',{flag:'wx',mode:0o600});fs.renameSync(temporary,file);}
  finally{try{fs.unlinkSync(temporary);}catch(error){if(error.code!=='ENOENT')throw error;}}
}

/** Pi JSONL remains authoritative; only archive/trash flags live alongside it. */
export class SessionCatalog {
  constructor(directory,workspace){this.directory=path.resolve(directory);this.workspace=workspace;this.file=path.join(directory,'.session-state.json');}
  read(){
    try{
      const data=JSON.parse(fs.readFileSync(this.file,'utf8'));
      if(data.version!==1||!data.items||typeof data.items!=='object'||Array.isArray(data.items))throw new Error();
      for(const value of Object.values(data.items)){
        if(!value||typeof value!=='object'||Array.isArray(value))throw new Error();
        for(const key of ['archivedAt','deletedAt','purgedAt'])if(Object.hasOwn(value,key)&&(!Number.isFinite(value[key])||value[key]<=0))throw new Error();
      }
      return Object.assign(Object.create(null),data.items);
    }catch(error){if(error.code==='ENOENT')return Object.create(null);throw new Error('会话管理记录无法读取，原记录未改动');}
  }
  safeFile(file){
    if(path.dirname(path.resolve(file))!==this.directory||path.extname(file)!=='.jsonl'||fs.lstatSync(this.directory).isSymbolicLink())throw new Error('会话文件位置无效');
    const stat=fs.lstatSync(file);if(!stat.isFile()||stat.isSymbolicLink())throw new Error('会话文件位置无效');return file;
  }
  persist(manager){
    const file=manager.getSessionFile();
    if(!file||path.dirname(path.resolve(file))!==this.directory||fs.lstatSync(this.directory).isSymbolicLink())throw new Error('会话文件位置无效');
    if(fs.existsSync(file)){this.safeFile(file);return;}
    // Pi defers new files until the first assistant reply. Persist the real SDK
    // header/entries now so an empty conversation can be named and resumed.
    fs.writeFileSync(file,[manager.getHeader(),...manager.getEntries()].map(value=>JSON.stringify(value)).join('\n')+'\n',{flag:'wx',mode:0o600});
    manager.setSessionFile(file);
  }
  async list(){
    const flags=this.read(),items=await SessionManager.list(this.workspace,this.directory);
    return items.filter(item=>{try{this.safeFile(item.path);return !flags[item.id]?.purgedAt;}catch{return false;}})
      .map(item=>({...item,archived:Boolean(flags[item.id]?.archivedAt),deleted:Boolean(flags[item.id]?.deletedAt)}));
  }
  async find(id){const item=(await this.list()).find(item=>item.id===id);if(!item)throw new Error('这个会话不存在或已被彻底删除');return item;}
  open(item){return SessionManager.open(this.safeFile(item.path),this.directory,this.workspace);}
  rename(item,name,current){
    if(typeof name!=='string')throw new Error('请输入会话名称');
    name=name.trim();if(!name||Array.from(name).length>80||/[\u0000-\u001f\u007f]/u.test(name))throw new Error('会话名称需要 1–80 个字符，不能含换行或控制字符');
    const manager=current?.getSessionId()===item.id?current:this.open(item);
    this.safeFile(item.path);manager.appendSessionInfo(name);
  }
  status(id,value){const items=this.read();if(value&&Object.keys(value).length)items[id]=value;else delete items[id];atomicJson(this.file,{version:1,items});}
  change(item,action){
    const old=this.read()[item.id]||{},next={...old};
    if(action==='archive'){if(item.deleted)throw new Error('请先从最近删除中恢复');next.archivedAt=Date.now();}
    else if(action==='delete')next.deletedAt=Date.now();
    else if(action==='restore'){delete next.archivedAt;delete next.deletedAt;}
    else throw new Error('不支持的会话操作');
    this.status(item.id,next);return old;
  }
  purge(item){
    if(!item.deleted)throw new Error('请先把会话移到最近删除');
    // The existing deletedAt flag is an ID-only tombstone. Keep it after unlink
    // so a stale active pointer cannot recreate this conversation after a crash.
    fs.unlinkSync(this.safeFile(item.path));
  }
}
