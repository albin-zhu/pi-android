import path from 'node:path';
import {fileURLToPath} from 'node:url';

const posix=path.posix;
function inside(root,value){return root==='/'?posix.isAbsolute(value):value===root||value.startsWith(root+'/');}
/** Mapping is an explicit bind-mount declaration, never evidence that a guest file exists. */
export class PhonePathMapping {
  constructor(value=process.env.ZFOLD_PHONE_PATH_MAP){
    this.hostRoot=null;this.guestRoot=null;
    if(value===undefined||value==='')return;
    let mapping;
    try{mapping=typeof value==='string'?JSON.parse(value):value;}catch(cause){throw new Error('ZFOLD_PHONE_PATH_MAP must be JSON {"hostRoot":"/android/root","guestRoot":"/guest/bind"}',{cause});}
    if(!mapping||typeof mapping.hostRoot!=='string'||typeof mapping.guestRoot!=='string'||!posix.isAbsolute(mapping.hostRoot)||!posix.isAbsolute(mapping.guestRoot)||mapping.hostRoot.includes('\0')||mapping.guestRoot.includes('\0'))throw new Error('ZFOLD_PHONE_PATH_MAP requires absolute hostRoot and guestRoot paths');
    this.hostRoot=posix.resolve(mapping.hostRoot);this.guestRoot=posix.resolve(mapping.guestRoot);
  }
  hostToGuest(value){
    if(!this.hostRoot||typeof value!=='string'||!posix.isAbsolute(value))return null;
    const normalized=posix.resolve(value);
    return inside(this.hostRoot,normalized)?posix.resolve(this.guestRoot,posix.relative(this.hostRoot,normalized)):null;
  }
  guestToHost(value,cwd){
    if(!this.guestRoot)throw new Error('Opening a guest file on Android requires an explicit ZFOLD_PHONE_PATH_MAP bind mapping; unbound guest paths are not Android files');
    const normalized=posix.resolve(cwd,value);
    if(!inside(this.guestRoot,normalized))throw new Error(`Guest file is outside the configured Android bind root ${this.guestRoot}`);
    return posix.resolve(this.hostRoot,posix.relative(this.guestRoot,normalized));
  }
  arguments(name,args,cwd){
    if(name!=='open'||typeof args.target!=='string')return args;
    const target=args.target.trim();
    if(/^[a-z][a-z0-9+.-]*:/i.test(target)&&!/^file:/i.test(target))return args;
    return {...args,target:this.guestToHost(/^file:/i.test(target)?fileURLToPath(target):target,cwd)};
  }
  readiness(ready){
    return {
      protocol:ready.protocol,tools:ready.tools,
      hostWorkspaceRoot:ready.workspaceRoot,hostWorkspace:ready.workspace,
      ...(this.hostRoot?{pathMapping:{hostRoot:this.hostRoot,guestRoot:this.guestRoot,bindingVerified:false}}:{}),
      ...(this.hostToGuest(ready.workspaceRoot)?{guestWorkspaceRoot:this.hostToGuest(ready.workspaceRoot)}:{}),
      ...(this.hostToGuest(ready.workspace)?{guestWorkspace:this.hostToGuest(ready.workspace)}:{}),
      pathNotice:'Android host paths are not guest paths. Guest paths are reported only for an explicitly configured bind mapping; the binding and file existence are not verified.'
    };
  }
  result(details){
    const fields=['path',...(details.kind==='file'||details.kind==='directory'?['target','fallback']:[])];
    const paths={};
    for(const field of fields){
      if(typeof details[field]!=='string'||!posix.isAbsolute(details[field]))continue;
      const hostPath=details[field],guestPath=this.hostToGuest(hostPath);
      paths[field]={hostPath,...(guestPath?{guestPath}:{}),scope:guestPath?'configured_guest_mapping':'android_host_only'};
      if(guestPath)details[field]=guestPath;else delete details[field];
    }
    if(Object.keys(paths).length){
      details.androidPaths=paths;
      details.pathNotice='Android host paths are not readable from this guest unless explicitly bound. Mapped paths describe the configured binding; file existence is not verified.';
    }
    return details;
  }
}
