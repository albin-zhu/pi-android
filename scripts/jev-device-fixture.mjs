// Only planning is deterministic. phone_task uses the real TypeSafe API inside the APK.
import http from 'node:http';
const pkg='dev.zfold.assistant';
http.createServer(async(req,res)=>{
  let body='';for await(const part of req)body+=part;
  const input=JSON.parse(body),last=input.messages.findLastIndex(m=>m.role==='user'),tool=input.messages.slice(last+1).findLast(m=>m.role==='tool');
  res.writeHead(200,{'content-type':'text/event-stream'});
  const chunk=(delta,finish=null)=>res.write('data: '+JSON.stringify({id:'jev-live-device',choices:[{index:0,delta,finish_reason:finish}]})+'\n\n');
  if(!tool){
    const args={goal:'On the Zfold automation test page, enable the checkbox labelled 演示开关 and replace the 备注 text field with the exact supplied value 你好，Jev. Complete both requirements. Stay on this page.',allowedPackages:[pkg],textValues:['你好，Jev'],maxSteps:8,expected:[{kind:'checked',packageName:pkg,resourceId:pkg+':id/jev_fixture_toggle',value:true},{kind:'text',packageName:pkg,resourceId:pkg+':id/jev_fixture_input',text:'你好，Jev'}]};
    chunk({role:'assistant',tool_calls:[{index:0,id:'jev-phone-task',type:'function',function:{name:'phone_task',arguments:JSON.stringify(args)}}]});chunk({},'tool_calls');
  }else{
    let result;try{result=JSON.parse(tool.content);}catch{result={status:'tool_error',reason:tool.content};}console.log(JSON.stringify({status:result.status,reason:result.reason,verified:result.verified,steps:result.steps?.map(s=>({action:s.action,status:s.status,confidence:s.confidence,latencyMs:s.latencyMs,model:s.model}))}));
    chunk({role:'assistant',content:result.verified?'测试页开关和中文输入已验证。':'执行器未验证全部条件：'+result.reason});chunk({},'stop');
  }
  res.end('data: [DONE]\n\n');
}).listen(18873,'127.0.0.1',()=>console.log('Jev device planner fixture: 127.0.0.1:18873'));
