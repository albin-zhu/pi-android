import {TextDecoder} from 'node:util';

export const MAX_PHONE_FRAME_BYTES=4*1024*1024;
export class PhoneProtocolError extends Error {
  constructor(message,options){super(`Android phone bridge protocol error: ${message}`,options);this.code='PHONE_PROTOCOL';}
}
export function encodePhoneFrame(message){
  const json=JSON.stringify(message);
  if(typeof json!=='string')throw new PhoneProtocolError('frame must contain JSON');
  const length=Buffer.byteLength(json);
  if(length===0||length>MAX_PHONE_FRAME_BYTES)throw new PhoneProtocolError(`frame length ${length} is outside 1..${MAX_PHONE_FRAME_BYTES}`);
  const frame=Buffer.allocUnsafe(4+length);
  frame.writeUInt32BE(length,0);frame.write(json,4,length,'utf8');
  return frame;
}
/** Incremental framing with one bounded payload allocation, even for byte-at-a-time reads. */
export class PhoneFrameDecoder {
  constructor(){this.header=Buffer.allocUnsafe(4);this.headerBytes=0;this.payload=null;this.payloadBytes=0;this.utf8=new TextDecoder('utf-8',{fatal:true});}
  push(chunk,receive){
    let offset=0;
    while(offset<chunk.length){
      if(this.payload===null){
        const count=Math.min(4-this.headerBytes,chunk.length-offset);
        chunk.copy(this.header,this.headerBytes,offset,offset+count);this.headerBytes+=count;offset+=count;
        if(this.headerBytes<4)continue;
        const length=this.header.readUInt32BE(0);
        if(length===0||length>MAX_PHONE_FRAME_BYTES)throw new PhoneProtocolError(`frame length ${length} is outside 1..${MAX_PHONE_FRAME_BYTES}`);
        this.payload=Buffer.allocUnsafe(length);this.payloadBytes=0;
      }
      const count=Math.min(this.payload.length-this.payloadBytes,chunk.length-offset);
      chunk.copy(this.payload,this.payloadBytes,offset,offset+count);this.payloadBytes+=count;offset+=count;
      if(this.payloadBytes===this.payload.length){
        let message;
        try{message=JSON.parse(this.utf8.decode(this.payload));}
        catch(cause){throw new PhoneProtocolError('invalid UTF-8 or JSON',{cause});}
        this.payload=null;this.payloadBytes=0;this.headerBytes=0;
        if(!message||typeof message!=='object'||Array.isArray(message))throw new PhoneProtocolError('frame must be a JSON object');
        receive(message);
      }
    }
  }
  finish(){
    if(this.headerBytes!==0||this.payload!==null)throw new PhoneProtocolError('connection ended inside a frame');
  }
}
