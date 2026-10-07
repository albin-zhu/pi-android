import fs from 'node:fs';
const waitBuffer=new Int32Array(new SharedArrayBuffer(4));
export function writeJsonLine(fd,event,write=fs.writeSync,pause=()=>Atomics.wait(waitBuffer,0,0,2)){
  const data=Buffer.from(JSON.stringify(event)+'\n');let offset=0,retries=0;
  while(offset<data.length){
    try{const size=write(fd,data,offset,data.length-offset);if(size<=0)throw new Error('Protocol output made no progress');offset+=size;retries=0;}
    catch(error){if(!['EAGAIN','EINTR'].includes(error.code)||++retries>2500)throw error;pause();}
  }
}
