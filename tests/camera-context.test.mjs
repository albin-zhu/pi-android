import {test} from 'node:test';
import assert from 'node:assert/strict';
import {cameraWorkingContext,cameraPromptSuffix} from '../runtime/camera-context.mjs';

test('live camera history is bounded without erasing stored history or explicit attachments',()=>{
  const messages=[
    {role:'user',content:[{type:'text',text:'first'},{type:'image',data:'old',mimeType:'image/jpeg',_zfoldCameraFrame:'one'},{type:'image',data:'file',mimeType:'image/png'}]},
    {role:'assistant',content:[{type:'text',text:'old observation'}]},
    {role:'toolResult',content:[{type:'image',data:'explicit photo',mimeType:'image/jpeg'}]},
    {role:'user',content:[{type:'image',data:'new',mimeType:'image/jpeg',_zfoldCameraFrame:'two'}]},
  ];
  const original=JSON.stringify(messages),filtered=cameraWorkingContext(messages);
  assert.equal(JSON.stringify(messages),original);
  assert.deepEqual(filtered.filter(m=>m.role==='user').flatMap(m=>m.content).filter(p=>p.type==='image').map(p=>p.data),['file','new']);
  assert.equal(filtered[2],messages[2]);
  assert.ok(!JSON.stringify(filtered).includes('_zfoldCameraFrame'));
  assert.match(cameraPromptSuffix({facing:'front',capturedAt:1}),/前置/);
  assert.match(cameraPromptSuffix({facing:'back',capturedAt:1}),/不提供手机屏幕点击坐标/);
});
