// Local-only deterministic model for the full Pi -> Android -> process path.
import http from 'node:http';
const port=Number(process.env.RUNTIME_FIXTURE_PORT||18872);
http.createServer(async(req,res)=>{
  let body='';for await(const part of req)body+=part;
  const input=JSON.parse(body),messages=input.messages||[];
  const at=messages.findLastIndex(message=>message.role==='user');
  const content=messages[at]?.content;
  const text=typeof content==='string'?content:(content||[]).filter(part=>part.type==='text').map(part=>part.text).join('\n');
  const line=text.split('\n').find(line=>line.startsWith('RUNTIME_FIXTURE '));
  if(!line){res.writeHead(400);res.end('Missing synthetic runtime fixture');return;}
  const plan=JSON.parse(line.slice('RUNTIME_FIXTURE '.length));
  res.writeHead(200,{'content-type':'text/event-stream'});
  const event=(delta,finish=null)=>res.write('data: '+JSON.stringify({id:'runtime-lifecycle',choices:[{index:0,delta,finish_reason:finish}]})+'\n\n');
  const results=messages.slice(at+1).filter(message=>message.role==='tool');
  const call=(name,args)=>{event({role:'assistant',tool_calls:[{index:0,id:'lifecycle-'+results.length,type:'function',function:{name,arguments:JSON.stringify(args)}}]});event({},'tool_calls');};
  const answer=ok=>{event({role:'assistant',content:ok?'LIFECYCLE_VERIFIED':'LIFECYCLE_FAILED'});event({},'stop');};
  const toolText=result=>typeof result?.content==='string'?result.content:(result?.content||[]).filter(part=>part.type==='text').map(part=>part.text).join('\n');
  const parsed=result=>{try{return JSON.parse(toolText(result));}catch{return {};}};
  if(plan.tool==='environment'){
    if(results.length===0)call('runtime_exec',{argv:plan.argv});
    else if(results.length===1)call('pty',{action:'open',argv:plan.argv});
    else if(!parsed(results[1]).id)answer(false);
    else if(results.length===2)call('pty',{action:'read',id:parsed(results[1]).id,timeoutMs:1000});
    else if(results.length===3)call('pty',{action:'close',id:parsed(results[1]).id});
    else if(results.length===4)call('bash',{command:plan.argv.map(value=>"'"+value.replaceAll("'","'\\''")+"'").join(' ')});
    else {
      const outputs=[parsed(results[0]).stdout,parsed(results[2]).output,toolText(results[4])].map(value=>String(value||'').replaceAll('\r','').trim());
      answer(outputs.every(output=>output===plan.expected));
    }
  }else if(plan.tool==='pty'){
    if(results.length===0)call('pty',{action:'open',argv:plan.argv});
    else{
      const content=results[0].content;
      let opened;try{opened=JSON.parse(typeof content==='string'?content:content.find(part=>part.type==='text').text);}catch{}
      if(!opened?.id)answer(false);
      else if(results.length===1)call('pty',{action:'read',id:opened.id,timeoutMs:1000});
      else if(results.length===2)call('pty',{action:'close',id:opened.id});
      else answer(JSON.stringify(results[1].content).includes('/dev/pts/')&&JSON.stringify(results[1].content).includes('RUNTIME_PTY_OK'));
    }
  }else if(!results.length){
    call('runtime_exec',plan);
  }else{
    answer(JSON.stringify(results[0].content).includes('RUNTIME_LONG_OK'));
  }
  res.end('data: [DONE]\n\n');
}).listen(port,'127.0.0.1',()=>console.log(`Runtime fixture listening on ${port}`));
