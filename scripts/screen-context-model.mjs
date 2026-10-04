// Local deterministic model endpoint; echoes the hash of the image it actually received.
import http from 'node:http';
import {createHash} from 'node:crypto';
http.createServer(async(req,res)=>{
  let body='';for await(const chunk of req)body+=chunk;
  const input=JSON.parse(body),user=input.messages.findLast(message=>message.role==='user');
  const picture=Array.isArray(user?.content)?user.content.find(part=>part.type==='image_url'):null;
  const data=picture?.image_url?.url?.split(',')[1];
  const continued=JSON.stringify(user?.content).includes('CONTINUE_AFTER_CLOSE');
  if(continued)await new Promise(resolve=>setTimeout(resolve,1800));
  const answer=continued?'CONTINUED_AFTER_CLOSE':data?'SCREEN_HASH:'+createHash('sha256').update(Buffer.from(data,'base64')).digest('hex'):'NO_IMAGE';
  res.writeHead(200,{'content-type':'text/event-stream'});
  const chunk=(delta,finish)=>res.write('data: '+JSON.stringify({id:'screen-context',object:'chat.completion.chunk',model:'screen-context-test',choices:[{index:0,delta,finish_reason:finish}]})+'\n\n');
  chunk({role:'assistant',content:answer},null);chunk({},'stop');res.end('data: [DONE]\n\n');
}).listen(18875,'127.0.0.1',()=>console.log('Screen model fixture :18875'));
