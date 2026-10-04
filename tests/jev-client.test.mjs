import {test} from 'node:test';
import assert from 'node:assert/strict';
import {JEV_ENDPOINT,jevChoice,validateChoice,validateJevConfig} from '../runtime/jev-client.mjs';
const config={enabled:true,apiKey:'test-secret',model:'jev-1.13.0'},criteria={tap:'Click the current button',blocked:'No target'};
const valid={type:'choice',choice:'tap',confidence:0.95,probabilities:{tap:0.98,blocked:0.02}};
test('Jev HTTP follows the official contract and validates the returned distribution',async()=>{
  const result=await jevChoice({config,state:{goal:'tap'},criteria,instructions:'Select',fetchImpl:async(url,request)=>{
    assert.equal(url,JEV_ENDPOINT);assert.equal(request.redirect,'error');assert.equal(request.headers.Authorization,'Bearer test-secret');
    assert.deepEqual(JSON.parse(request.body).questions.next_action,{type:'choice',instructions:'Select',criteria});
    return new Response(JSON.stringify({model:config.model,answers:{next_action:valid}}));
  }});
  assert.equal(result.choice,'tap');assert.equal(result.model,config.model);assert.ok(result.margin>0.9);
  for(const bad of [{...valid,choice:'invented'},{...valid,probabilities:{tap:0.8}},{...valid,confidence:NaN},{...valid,probabilities:{tap:0.3,blocked:0.7}}])assert.throws(()=>validateChoice(bad,criteria));
});
test('provider errors never echo a key or private response; cancellation stops the request',async()=>{
  await assert.rejects(jevChoice({config,state:{},criteria,instructions:'test',fetchImpl:async()=>new Response('test-secret private screen',{status:401})}),error=>error.message.includes('密钥无效')&&!error.message.includes('test-secret'));
  const abort=new AbortController();
  const pending=jevChoice({config,state:{},criteria,instructions:'test',signal:abort.signal,fetchImpl:async(_url,request)=>new Promise((_resolve,reject)=>request.signal.addEventListener('abort',()=>reject(new Error('network request aborted'))))});
  abort.abort();await assert.rejects(pending,/操作已停止/);
});
test('Jev configuration preserves an existing key and supports explicit clearing',()=>{
  assert.equal(validateJevConfig({enabled:true},config).apiKey,'test-secret');
  assert.equal(validateJevConfig({enabled:false,apiKey:''},config).apiKey,'');
  assert.throws(()=>validateJevConfig({enabled:true,apiKey:'a\nb'}));
});
