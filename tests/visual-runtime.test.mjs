import {stopRuntimeChild} from './runtime-cleanup.mjs';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,rm} from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import {jsonLines} from '../runtime/protocol.mjs';

for(const detour of [false,true])test(detour?'real Pi aborts an unrelated-app detour after an empty tree':'real Pi returns screenshots with empty trees and after focused input',{timeout:20000},async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'zfold-visual-'));
  let requests=0,frames=0,halted=false;const calls=[];
  const image='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQAAAABJRU5ErkJggg==';
  const server=http.createServer(async(req,res)=>{
    let body='';for await(const part of req)body+=part;
    const input=JSON.parse(body);requests++;
    res.writeHead(200,{'content-type':'text/event-stream'});
    const chunk=(delta,finish=null)=>res.write('data: '+JSON.stringify({id:'visual',choices:[{index:0,delta,finish_reason:finish}]})+'\n\n');
    if(requests===1){chunk({role:'assistant',tool_calls:[{index:0,id:'observe',type:'function',function:{name:'screen_read',arguments:'{}'}}]});chunk({},'tool_calls');}
    else if(requests===2){
      assert.ok(body.includes(image));assert.ok(body.includes('screenshot_pixels'));
      const name=detour?'app_open':'ui_input',args=detour?{packageName:'com.android.chrome'}:{packageName:'com.tencent.mm',screenshotId:'frame-1',x:20,y:30,text:'样例'};
      chunk({role:'assistant',tool_calls:[{index:0,id:'act',type:'function',function:{name,arguments:JSON.stringify(args)}}]});chunk({},'tool_calls');
    }else{assert.ok(!detour);assert.ok(body.includes('frame-2'));chunk({role:'assistant',content:'输入已回读。'});chunk({},'stop');}
    res.end('data: [DONE]\n\n');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const child=spawn(process.execPath,['android/assets/runtime/agent.cjs','0','1',root],{stdio:['pipe','pipe','pipe'],env:{...process.env,PI_CODING_AGENT_DIR:path.join(root,'pi')}});
  const send=value=>child.stdin.write(JSON.stringify(value)+'\n');let stderr='';child.stderr.on('data',p=>stderr+=p);
  try{
    await new Promise((resolve,reject)=>{
      child.on('exit',code=>reject(new Error(`Exit ${code}: ${stderr}`)));
      child.stdout.setEncoding('utf8');child.stdout.on('data',jsonLines(event=>{
        try{
          if(event.type==='ready')send({type:'configure',config:{baseUrl:`http://127.0.0.1:${server.address().port}/v1`,model:'visual-fixture',apiKey:'fixture',api:'openai-completions'}});
          if(event.type==='configured')send({type:'prompt',id:'visual-test',text:'在微信中查找样例'});
          if(event.type==='tool_request'){
            calls.push(event.name);let result;
            if(event.name==='screen_read')result={nodes:[],readable:false,packageName:'com.tencent.mm'};
            else if(event.name==='screen_capture')result={image,mimeType:'image/png',frame:{screenshotId:`frame-${++frames}`,coordinateSpace:'screenshot_pixels',imageWidth:100,imageHeight:200,screenWidth:200,screenHeight:400}};
            else{assert.equal(event.name,'ui_input');assert.equal(event.args.expectedPackageName,'com.tencent.mm');assert.equal(event.args.screenshotId,'frame-1');result={performed:true,verified:true};}
            send({type:'tool_result',id:event.id,result});
          }
          if(event.type==='phone_halt')halted=true;
          if(event.type==='turn_end'){assert.ok(!detour);resolve();}
          if(event.type==='error'){if(detour){assert.match(event.message,/其他应用/);resolve();}else reject(new Error(event.message));}
        }catch(error){reject(error);}
      },reject));
    });
    assert.equal(requests,detour?2:3);assert.equal(halted,detour);
    assert.deepEqual(calls,detour?['screen_read','screen_capture']:['screen_read','screen_capture','ui_input','screen_capture']);
  }finally{await stopRuntimeChild(child);server.closeAllConnections();server.close();await rm(root,{recursive:true,force:true});}
});
