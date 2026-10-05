import http from 'node:http';
import fs from 'node:fs';
import crypto from 'node:crypto';
const records=[];
const target='artifacts/video-phone-actions/model-requests.json';
const server=http.createServer(async(req,res)=>{
  try{
    let body='';for await(const chunk of req)body+=chunk;
    const data=JSON.parse(body),users=data.messages.filter(message=>message.role==='user');
    const images=message=>Array.isArray(message?.content)?message.content.filter(part=>part.type==='image_url'):[];
    const latest=images(users.at(-1)),all=users.flatMap(images);
    if(all.length>1)throw new Error('Historical camera image budget exceeded');
    const picture=latest[0]?.image_url.url;
    const bytes=picture?Buffer.from(picture.split(',')[1],'base64'):null;
    if(bytes&&(bytes[0]!==255||bytes[1]!==216||bytes.length>512*1024))throw new Error('Invalid or oversized JPEG');
    const hash=bytes?crypto.createHash('sha256').update(bytes).digest('hex'):'NONE';
    records.push({currentImages:latest.length,totalImages:all.length,bytes:bytes?.length||0,hash,mime:picture?.split(';')[0]||null});fs.writeFileSync(target,JSON.stringify(records,null,2)+'\n');
    res.writeHead(200,{'content-type':'text/event-stream'});
    res.end('data: '+JSON.stringify({id:'video-fixture',choices:[{index:0,delta:{role:'assistant',content:'CAMERA_HASH:'+hash},finish_reason:null}]})+'\n\ndata: '+JSON.stringify({id:'video-fixture',choices:[{index:0,delta:{},finish_reason:'stop'}]})+'\n\ndata: [DONE]\n\n');
  }catch(error){res.writeHead(400);res.end(JSON.stringify({error:{message:error.message}}));}
});
server.listen(18876,'127.0.0.1',()=>process.stdout.write('Video fixture listening on 18876\n'));
