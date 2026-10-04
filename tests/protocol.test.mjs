import {test} from 'node:test';
import assert from 'node:assert/strict';
import {jsonLines,validateConfig} from '../runtime/protocol.mjs';
test('JSON protocol accepts fragmented Chinese and multiple messages',()=>{
  const values=[],errors=[];const accept=jsonLines(v=>values.push(v),e=>errors.push(e));
  accept('{"text":"你好');accept('🙂"}\n{"type":"stop"}\n');
  assert.deepEqual(values,[{text:'你好🙂'},{type:'stop'}]);assert.equal(errors.length,0);
});
test('invalid and oversized input fails without executing partial messages',()=>{
  const values=[],errors=[];const accept=jsonLines(v=>values.push(v),e=>errors.push(e),30);
  accept('x\n');accept('x'.repeat(40));accept('{"ok":true}\n');
  assert.equal(errors.length,2);assert.deepEqual(values,[{ok:true}]);
});
test('model endpoint rejects embedded credentials and non-HTTP schemes',()=>{
  for(const baseUrl of ['file:///data/a','https://key:secret@example.com/v1','https://example.com/v1?key=secret'])assert.throws(()=>validateConfig({baseUrl,model:'test'}));
  assert.deepEqual(validateConfig({baseUrl:'https://example.com/v1/',model:' test ',apiKey:' key '}),{baseUrl:'https://example.com/v1',model:'test',apiKey:'key',api:'openai-responses'});
  assert.deepEqual(validateConfig({baseUrl:'https://example.com/v1',model:'test',apiKey:'key',api:'openai-completions'}).api,'openai-completions');
  assert.throws(()=>validateConfig({baseUrl:'https://example.com/v1',model:'test',apiKey:'key',api:'made-up'}));
});
