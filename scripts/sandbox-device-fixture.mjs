// Development-only deterministic planner for the real Android Worker sandbox.
import http from 'node:http';

http.createServer(async(req,res)=>{
  let body='';for await(const part of req)body+=part;
  const input=JSON.parse(body),results=input.messages.filter(message=>message.role==='tool');
  res.writeHead(200,{'content-type':'text/event-stream'});
  const event=(delta,finish=null)=>res.write('data: '+JSON.stringify({id:'sandbox-device',choices:[{index:0,delta,finish_reason:finish}]})+'\n\n');
  const call=(name,args)=>{event({role:'assistant',tool_calls:[{index:0,id:`sandbox-${results.length}`,type:'function',function:{name,arguments:JSON.stringify(args)}}]});event({},'tool_calls');};
  if(results.length===0)call('sandbox_status',{});
  else if(results.length===1)call('bash',{command:'command -v cat'});
  else{
    let status={};try{status=JSON.parse(results[0].content);}catch{}
    let bash='';try{bash=JSON.parse(results[1].content).stdout;}catch{}
    event({role:'assistant',content:`沙箱可用，Pi 引擎 ${status?.embeddedEngine?.version||process.version}，cat=${bash}。`});event({},'stop');
    console.log(JSON.stringify(status));
  }
  res.end('data: [DONE]\n\n');
}).listen(18871,'127.0.0.1',()=>console.log('Sandbox fixture: 127.0.0.1:18871'));
