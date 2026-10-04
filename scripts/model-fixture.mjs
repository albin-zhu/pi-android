// Development-only deterministic inference endpoint. Pi itself runs inside the APK.
import http from 'node:http';
const server=http.createServer(async(req,res)=>{
  let body='';for await(const chunk of req)body+=chunk;
  const input=JSON.parse(body),tool=[...input.messages].reverse().find(m=>m.role==='tool');
  const lastUser=[...input.messages].reverse().find(m=>m.role==='user');
  if(JSON.stringify(lastUser?.content||'').includes('ui-error')){res.writeHead(400,{'content-type':'application/json'});res.end(JSON.stringify({error:{message:'测试接口暂时无法完成请求，请重试或检查模型设置。',type:'invalid_request_error'}}));return;}
  res.writeHead(200,{'content-type':'text/event-stream'});
  const event=(delta,finish=null)=>res.write('data: '+JSON.stringify({id:'zfold-fixture',object:'chat.completion.chunk',created:1,model:'zfold-device-test',choices:[{index:0,delta,finish_reason:finish}]})+'\n\n');
  if(!tool){event({role:'assistant',tool_calls:[{index:0,id:'phone-info',type:'function',function:{name:'device_info',arguments:'{}'}}]});event({},'tool_calls');}
  else {let info={};try{info=JSON.parse(tool.content);}catch{}event({role:'assistant',content:`本地链路验证成功。手机：${info.model}，Android ${info.android}，电量 ${info.batteryPercent}%。Pi 正在 APK 内运行。`});event({},'stop');console.log(JSON.stringify({nativeToolResult:info}));}
  res.end('data: [DONE]\n\n');
});
server.listen(18868,'127.0.0.1',()=>console.log('Deterministic model fixture: 127.0.0.1:18868'));
