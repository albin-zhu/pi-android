import {test} from 'node:test';
import assert from 'node:assert/strict';
import {assistantInterruption,chatHistory,toolCard} from '../runtime/chat-history.mjs';

test('history preserves text/tool order, errors, arguments, and image result markers',()=>{
  const call={type:'toolCall',id:'capture',name:'screen_capture',arguments:{}};
  const messages=[{role:'user',content:'检查页面'},
    {role:'assistant',content:[{type:'text',text:'先读屏。'},call]},
    {role:'toolResult',toolCallId:'capture',content:[{type:'image',data:'PRIVATE_IMAGE_DATA'}]},
    {role:'assistant',content:[{type:'toolCall',id:'tap',name:'ui_tap',arguments:{nodeId:'12:3'}}]},
    {role:'toolResult',toolCallId:'tap',isError:true,content:[{type:'text',text:'页面已变化'}]},
    {role:'assistant',content:[{type:'text',text:'需要重新读屏。'}]}];
  const items=chatHistory(messages);
  assert.deepEqual(items.map(item=>item.role),['user','assistant','tool','tool','assistant']);
  assert.equal(items[2].status,'success');assert.equal(items[2].hasImage,true);
  assert.equal(items[3].status,'error');assert.match(items[3].args,/12:3/);assert.match(items[3].output,/页面已变化/);
  assert.ok(!JSON.stringify(items).includes('PRIVATE_IMAGE_DATA'));
});

test('unfinished calls restore as interrupted, active calls remain running, no-op is distinct',()=>{
  const call={type:'toolCall',id:'select',name:'ui_select',arguments:{targets:['蓝牙']}};
  const messages=[{role:'assistant',content:[call]}];
  assert.equal(chatHistory(messages)[0].status,'interrupted');
  const active=new Map([['select',toolCard(call,null,'running')]]);
  assert.equal(chatHistory(messages,active)[0].status,'running');
  assert.equal(chatHistory([],active).length,1);
  const noop={content:[{type:'text',text:'{"performed":false,"reason":"ambiguous"}'}]};
  assert.equal(toolCard(call,noop).status,'not_performed');
});

test('tool previews are bounded so large screen dumps do not overflow the JNI event',()=>{
  const card=toolCard({id:'read',name:'screen_read',arguments:{}},{content:[{type:'text',text:'界面'.repeat(100000)}]});
  assert.ok(card.output.length<12100);assert.match(card.output,/截断/);
});

test('assistant failures without text retain the original error and stable record identity',()=>{
  const message={role:'assistant',provider:'openai-codex',model:'gpt-5.4',timestamp:1770000000123,stopReason:'error',errorMessage:'request_too_large: HTTP 413\nmax=52428800',content:[]};
  const expected={role:'interruption',id:'assistant:'+JSON.stringify([message.provider,message.model,message.timestamp]),reason:'error',message:message.errorMessage,timestamp:message.timestamp,provider:message.provider,model:message.model};
  assert.deepEqual(assistantInterruption(message),expected);
  assert.deepEqual(chatHistory([message]),[expected]);
  assert.deepEqual(chatHistory([JSON.parse(JSON.stringify(message))]),[expected]);
  assert.notEqual(assistantInterruption({...message,timestamp:message.timestamp+1}).id,expected.id);
  assert.notEqual(assistantInterruption({...message,model:'different-model'}).id,expected.id);
  assert.equal(assistantInterruption({...message,stopReason:'stop'}),null);
});

test('truncated and aborted assistants retain partial text and tools separately from interruption cards',()=>{
  const length={role:'assistant',provider:'glm',model:'GLM-5',timestamp:2,stopReason:'length',content:[{type:'thinking',thinking:'private reasoning'},{type:'text',text:'尚未完成的正文'},{type:'toolCall',id:'read-partial',name:'read',arguments:{path:'a.txt'}}]};
  const aborted={role:'assistant',provider:'glm',model:'GLM-5',timestamp:3,stopReason:'aborted',content:[{type:'text',text:'中止前的正文'}]};
  const items=chatHistory([length,aborted]);
  assert.deepEqual(items.map(item=>item.role),['assistant','tool','interruption','assistant','interruption']);
  assert.equal(items[0].text,'尚未完成的正文');assert.equal(items[1].status,'interrupted');
  assert.equal(items[2].reason,'length');assert.match(items[2].message,/输出长度限制/);
  assert.equal(items[3].text,'中止前的正文');assert.equal(items[4].reason,'aborted');assert.match(items[4].message,/已中止/);
  assert.ok(!JSON.stringify(items).includes('private reasoning'));
  const thinkingOnly=chatHistory([{...length,content:[{type:'thinking',thinking:'private reasoning'}]}]);
  assert.deepEqual(thinkingOnly,[assistantInterruption(length)]);
});

test('interruption cards share the existing 80-item and UTF-8 history budgets',()=>{
  const messages=Array.from({length:100},(_,i)=>({role:'assistant',provider:'custom',model:'history-test',timestamp:i,stopReason:'error',content:[],errorMessage:`错误 ${i}\n`+'界'.repeat(20000)}));
  const items=chatHistory(messages);
  assert.ok(items.length>0&&items.length<=80);
  assert.ok(Buffer.byteLength(JSON.stringify(items))<3_700_000);
  assert.equal(items.at(-1).timestamp,99);assert.equal(items.at(-1).message,messages.at(-1).errorMessage);
  assert.ok(items[0].timestamp>20,'UTF-8 bytes must trim more than the item count alone');
});

test('a single oversized provider error remains displayable without altering its persisted source',()=>{
  const message={role:'assistant',provider:'custom',model:'history-test',timestamp:101,stopReason:'error',content:[],errorMessage:'上游响应：'+'界'.repeat(2_000_000)};
  const card=assistantInterruption(message);
  assert.ok(Buffer.byteLength(JSON.stringify(card))<4*1024*1024);
  assert.match(card.message,/上游响应：.*显示已截断/s);
  assert.equal(message.errorMessage,'上游响应：'+'界'.repeat(2_000_000));
  assert.deepEqual(chatHistory([message]),[card]);
});
