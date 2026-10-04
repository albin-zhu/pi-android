// Deterministic model endpoint for the Android floating-control integration check.
import http from 'node:http';
const server=http.createServer(async(req,res)=>{
  let body='';for await(const part of req)body+=part;
  const input=JSON.parse(body),lastUser=input.messages.findLastIndex(message=>message.role==='user');
  const results=input.messages.slice(lastUser+1).filter(message=>message.role==='tool');
  res.writeHead(200,{'content-type':'text/event-stream'});
  const event=(delta,finish=null)=>res.write('data: '+JSON.stringify({id:'zfold-floating',object:'chat.completion.chunk',created:1,model:'zfold-device-test',choices:[{index:0,delta,finish_reason:finish}]})+'\n\n');
  const call=(name,args)=>{event({role:'assistant',tool_calls:[{index:0,id:`floating-${results.length}`,type:'function',function:{name,arguments:JSON.stringify(args)}}]});event({},'tool_calls');};
  const value=index=>{try{return JSON.parse(results[index].content);}catch{return null;}};
  if(results.length===0)call('app_open',{packageName:'com.android.settings'});
  else if(results.length===1){if(value(0)?.launched===true&&value(0)?.ready===true)call('screen_read',{});else{event({role:'assistant',content:'设置窗口尚未就绪。'});event({},'stop');}}
  else{
    const nodes=value(1)?.nodes;
    console.log('screen result:',Array.isArray(nodes)?{count:nodes.length,packages:[...new Set(nodes.map(node=>node.package))]}:String(results[1].content).slice(0,160));
    event({role:'assistant',content:Array.isArray(nodes)&&nodes.some(node=>node.package==='com.android.settings')?'已打开设置并读取目标页面。':'未能读取设置页面。'});
    event({},'stop');
  }
  res.end('data: [DONE]\n\n');
});
server.listen(18870,'127.0.0.1',()=>console.log('Floating fixture listening on 127.0.0.1:18870'));
