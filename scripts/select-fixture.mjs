// Deterministic model endpoint for the one-call phone selection integration check.
import http from 'node:http';
let started;
const server=http.createServer(async(req,res)=>{
  let body='';for await(const part of req)body+=part;
  const input=JSON.parse(body),lastUser=input.messages.findLastIndex(message=>message.role==='user');
  const results=input.messages.slice(lastUser+1).filter(message=>message.role==='tool');
  res.writeHead(200,{'content-type':'text/event-stream'});
  const event=(delta,finish=null)=>res.write('data: '+JSON.stringify({id:'zfold-select',object:'chat.completion.chunk',created:1,model:'zfold-device-test',choices:[{index:0,delta,finish_reason:finish}]})+'\n\n');
  if(results.length===0){
    started=performance.now();
    event({role:'assistant',tool_calls:[{index:0,id:'select-back',type:'function',function:{name:'ui_select',arguments:JSON.stringify({targets:['返回上一级','Navigate up','Back'],packageName:'com.android.settings'})}}]});event({},'tool_calls');
  }else{
    let result={};try{result=JSON.parse(results[0].content);}catch{}
    const okay=result.performed===true&&result.verified===true&&result.screen?.nodes?.some(node=>node.package==='com.android.settings');
    event({role:'assistant',content:okay?'已完成界面操作。':'选择目标失败，未完成操作。'});event({},'stop');
    console.log(JSON.stringify({performed:result.performed,verified:result.verified,reason:result.reason,selected:result.selected?.text||result.selected?.description,toolCycleMs:Math.round(performance.now()-started)}));
  }
  res.end('data: [DONE]\n\n');
});
server.listen(18871,'127.0.0.1',()=>console.log('Selection fixture listening on 127.0.0.1:18871'));
