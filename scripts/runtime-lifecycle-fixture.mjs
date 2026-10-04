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
    else {
      const closeIndex=results.findIndex((result,index)=>index>1&&parsed(result).closed);
      const reads=results.slice(2,closeIndex<0?undefined:closeIndex).map(parsed);
      const output=reads.map(read=>read.output||'').join('').replaceAll('\r','').trim();
      if(closeIndex<0){
        if(output===plan.expected||reads.length>=12)call('pty',{action:'close',id:parsed(results[1]).id});
        else call('pty',{action:'read',id:parsed(results[1]).id,timeoutMs:1000});
      }else if(results.length===closeIndex+1)call('bash',{command:plan.argv.map(value=>"'"+value.replaceAll("'","'\\''")+"'").join(' ')});
      else answer([parsed(results[0]).stdout,output,toolText(results[closeIndex+1])].every(value=>String(value||'').replaceAll('\r','').trim()===plan.expected));
    }
  }else if(plan.tool==='pty'){
    if(results.length===0)call('pty',{action:'open',argv:plan.argv});
    else{
      const content=results[0].content;
      let opened;try{opened=JSON.parse(typeof content==='string'?content:content.find(part=>part.type==='text').text);}catch{}
      if(!opened?.id)answer(false);
      else {
        const output=results.slice(1).map(result=>parsed(result).output||'').join('');
        const seen=output.includes('/dev/pts/')&&output.includes('RUNTIME_PTY_OK');
        if(parsed(results.at(-1)).closed)answer(seen);
        else if(seen||results.length>12)call('pty',{action:'close',id:opened.id});
        else call('pty',{action:'read',id:opened.id,timeoutMs:1000});
      }
    }
  }else if(!results.length){
    call('runtime_exec',plan);
  }else{
    answer(JSON.stringify(results[0].content).includes('RUNTIME_LONG_OK'));
  }
  res.end('data: [DONE]\n\n');
}).listen(port,'127.0.0.1',()=>console.log(`Runtime fixture listening on ${port}`));
