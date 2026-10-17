#!/usr/bin/env node
import {PhoneToolSession} from './phone-tools.mjs';

const help=`Usage:
  node /absolute/runtime/phone-cli.mjs list
  node /absolute/runtime/phone-cli.mjs call <tool> '<JSON object>'

Environment:
  ZFOLD_PHONE_SOCKET     Bare Android abstract AF_UNIX socket name (required).
  ZFOLD_PHONE_PATH_MAP   Optional JSON {"hostRoot":"/android/root","guestRoot":"/guest/bind"}.
                        Only configure a mapping for an actual explicit guest bind mount.

Examples:
  node /absolute/runtime/phone-cli.mjs call device_info '{}'
  node /absolute/runtime/phone-cli.mjs call screen_capture '{"save":false}'

Android owns system permissions and the independent-client approval setting.
By default, every request except device_info, phone_capabilities, and app_list
requires individual confirmation. The user can explicitly select Always allow
in the app's Phone capabilities settings; system permissions still apply.
One external call may execute globally; busy requests fail rather than queue.
This client never retries or replays calls.
SIGINT/SIGTERM requests cancellation and waits for the real terminal receipt.
A disconnected/unconfirmed request may already have changed the phone: inspect its
actual state before retrying, especially calls, SMS, input, or taps.

list/call write JSON to stdout; errors write JSON to stderr. Image bytes are image
content blocks, not embedded text or details. Unbound returned paths are explicitly
Android-host-only. Exit codes: 0 = received receipt (inspect its status/performed
fields, not proof of task success); 1 = usage/connection/tool error; 2 = execution
uncertain; 130 = interrupted before a confirmed successful receipt.
`;
const argv=process.argv.slice(2);
if(argv.length===0||['help','--help','-h'].includes(argv[0])){
  process.stdout.write(help);
}else{
  const controller=new AbortController();
  const stop=()=>controller.abort();
  process.on('SIGINT',stop);process.on('SIGTERM',stop);
  let phone;
  try{
    if(argv[0]==='list'&&argv.length!==1||argv[0]==='call'&&argv.length!==3||!['list','call'].includes(argv[0]))throw new Error('Expected list or call <tool> <JSON object>; use --help');
    let args;
    if(argv[0]==='call'){
      try{args=JSON.parse(argv[2]);}catch(cause){throw new Error('Tool arguments must be valid JSON',{cause});}
      if(!args||typeof args!=='object'||Array.isArray(args))throw new Error('Tool arguments must be a JSON object');
    }
    phone=new PhoneToolSession();
    const result=argv[0]==='list'?await phone.list({signal:controller.signal}):await phone.call(argv[1],args,{signal:controller.signal});
    process.stdout.write(JSON.stringify(result)+'\n');
    if(controller.signal.aborted&&!(argv[0]==='call'&&result.details?.performed===true))process.exitCode=130;
  }catch(error){
    const uncertain=error.code==='NATIVE_UNCONFIRMED';
    process.stderr.write(JSON.stringify({error:{code:error.code||'PHONE_ERROR',message:error.message,uncertain}})+'\n');
    process.exitCode=uncertain?2:controller.signal.aborted?130:1;
  }finally{
    phone?.close();process.removeListener('SIGINT',stop);process.removeListener('SIGTERM',stop);
  }
}
