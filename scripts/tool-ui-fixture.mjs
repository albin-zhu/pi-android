// Dedicated fixture for the native tool-card regression, isolated from user models.
import http from 'node:http';
http.createServer(async(req,res)=>{
  let body='';for await(const chunk of req)body+=chunk;
  const input=JSON.parse(body),last=input.messages.findLastIndex(m=>m.role==='user');
  const count=input.messages.slice(last+1).filter(m=>m.role==='tool').length;
  res.writeHead(200,{'content-type':'text/event-stream'});
  const event=(delta,finish=null)=>res.write('data: '+JSON.stringify({id:'tool-ui',choices:[{index:0,delta,finish_reason:finish}]})+'\n\n');
  if(count<2){event({role:'assistant',tool_calls:[{index:0,id:count?'read-failed':'info-ok',type:'function',function:{name:count?'read':'device_info',arguments:count?'{"path":"/not-a-skill"}':'{}'}}]});event({},'tool_calls');}
  else{event({role:'assistant',content:'已读取手机状态。工具记录中保留了返回值，以及一次读取失败的原因。'});event({},'stop');}
  res.end('data: [DONE]\n\n');
}).listen(18872,'127.0.0.1',()=>console.log('Tool UI fixture: 127.0.0.1:18872'));
