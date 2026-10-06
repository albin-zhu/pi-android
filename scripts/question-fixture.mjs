import http from 'node:http';
const state={calls:0,receipts:[]};
const server=http.createServer(async(req,res)=>{
  if(req.method==='GET'){res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(state));return;}
  let raw='';for await(const part of req)raw+=part;const input=JSON.parse(raw),messages=input.messages;
  const at=messages.findLastIndex(message=>message.role==='user'),recent=messages.slice(at+1);const tool=recent.find(message=>message.role==='tool');
  let delta,reason;
  if(tool){state.receipts.push(tool.content);delta={role:'assistant',content:String(tool.content).includes('"status":"declined"')?'QUESTION_DECLINED':'QUESTION_CONFIRMED'};reason='stop';}
  else{
    const simple=JSON.stringify(messages[at]).includes('SIMPLE');
    const questions=simple?[{id:'note',question:'补充一句你的要求'}]:[
      {id:'platform',header:'平台',question:'优先支持哪个平台？',options:[{label:'Android',description:'先在手机上完成'},{label:'桌面',description:'先做桌面端'}]},
      {id:'features',header:'能力',question:'要包含哪些能力？',multiSelect:true,options:[{label:'语音'},{label:'相机'}]},
      {id:'note',header:'补充',question:'还有什么要求？'}];
    delta={role:'assistant',tool_calls:[{index:0,id:'question-fixture-'+(++state.calls),type:'function',function:{name:'ask_user_question',arguments:JSON.stringify({questions})}}]};reason='tool_calls';
  }
  res.writeHead(200,{'content-type':'text/event-stream'});res.end('data: '+JSON.stringify({id:'question-fixture',choices:[{index:0,delta,finish_reason:reason}]})+'\n\ndata: [DONE]\n\n');
});
server.listen(Number(process.env.QUESTION_FIXTURE_PORT||18876),'127.0.0.1',()=>console.log('Question fixture ready'));
