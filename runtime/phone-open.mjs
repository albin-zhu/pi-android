#!/usr/bin/env node
import {PhoneToolSession} from './phone-tools.mjs';

const argv=process.argv.slice(2),literal=argv[0]==='--';
if(literal)argv.shift();
if(!literal&&argv.length===1&&['--help','-h'].includes(argv[0])){
  process.stdout.write(`Usage: xdg-open [--] <URL-or-guest-path>
Open workspace Markdown, HTML/HTM, SVG and readable text in the app's preview.
Other files and URLs use Android handlers; directories use the file manager.
Local files must be inside ZFOLD_PHONE_PATH_MAP's guest bind root.
Preview is offline/local-only and offers external opening; .gtm is text, not a special format.
Android permissions and APK installation confirmation still apply.
Exit: 0 dispatched/confirmation requested (not proof of rendering); 1 failed; 2 uncertain; 130 cancelled.
`);
}else if(argv.length!==1||!argv[0].trim()||!literal&&argv[0].startsWith('-')){
  process.stderr.write('xdg-open: expected one URL or file path; use --help\n');
  process.exitCode=1;
}else{
  const controller=new AbortController(),stop=()=>controller.abort();
  process.on('SIGINT',stop);process.on('SIGTERM',stop);
  let phone;
  try{
    phone=new PhoneToolSession();
    const {details}=await phone.call('open',{target:argv[0]},{signal:controller.signal});
    if(details.opened!==true&&details.confirmationRequested!==true){
      throw new Error(details.error||details.status||'Android did not open the target');
    }
    if(details.confirmationRequested)process.stderr.write('xdg-open: confirm APK installation on Android; not installed yet\n');
  }catch(error){
    process.stderr.write(`xdg-open: ${error.message}\n`);
    process.exitCode=error.code==='NATIVE_UNCONFIRMED'?2:controller.signal.aborted?130:1;
  }finally{
    phone?.close();process.removeListener('SIGINT',stop);process.removeListener('SIGTERM',stop);
  }
}
