// Development-only deterministic model. The APK runs Pi; this only supplies tool calls.
import http from 'node:http';
const skillPath=process.argv[2];
if(!skillPath)throw new Error('Pass the APK skill file path');
const server=http.createServer(async(req,res)=>{
  let body='';for await(const part of req)body+=part;
  const input=JSON.parse(body),lastUser=input.messages.findLastIndex(message=>message.role==='user');
  const results=input.messages.slice(lastUser+1).filter(message=>message.role==='tool');
  res.writeHead(200,{'content-type':'text/event-stream'});
  const event=(delta,finish=null)=>res.write('data: '+JSON.stringify({id:'zfold-control',object:'chat.completion.chunk',created:1,model:'zfold-device-test',choices:[{index:0,delta,finish_reason:finish}]})+'\n\n');
  const call=(name,args)=>{event({role:'assistant',tool_calls:[{index:0,id:`control-${results.length}`,type:'function',function:{name,arguments:JSON.stringify(args)}}]});event({},'tool_calls');};
  const value=index=>{try{return JSON.parse(results[index].content);}catch{return null;}};
  if(results.length===0)call('read',{path:skillPath});
  else if(results.length===1){
    if(!String(results[0].content).includes('screen_read')){event({role:'assistant',content:'内置说明读取失败。'});event({},'stop');}
    else call('screen_read',{});
  }
  else if(results.length===2){
    const screen=value(1),nodes=screen?.nodes;
    if(!Array.isArray(nodes)||!nodes.some(node=>node.package==='com.android.settings')){console.log('screen_read failure:',String(results[1].content).slice(0,160));event({role:'assistant',content:'未读取到系统设置页。'});event({},'stop');}
    else{
      const target=nodes.find(node=>/^(返回上一级|Navigate up|Back)$/i.test(`${node.text||node.description}`.trim()));
      call(target?'ui_tap':'ui_back',target?{nodeId:target.nodeId}:{});
    }
  }else if(results.length===3){
    const action=value(2);
    if(action?.performed!==true){event({role:'assistant',content:'界面操作失败。'});event({},'stop');}
    else call('screen_read',{});
  }else{
    const screen=value(3);
    event({role:'assistant',content:Array.isArray(screen?.nodes)&&screen.nodes.length>0?'已完成界面操作并重新读取屏幕。':'操作后屏幕读取失败。'});
    event({},'stop');
  }
  res.end('data: [DONE]\n\n');
});
server.listen(18869,'127.0.0.1',()=>console.log('Control fixture listening on 127.0.0.1:18869'));
