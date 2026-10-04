import {test} from 'node:test';
import assert from 'node:assert/strict';
import {chatHistory,toolCard} from '../runtime/chat-history.mjs';

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
