import {test} from 'node:test';
import assert from 'node:assert/strict';
import {PhoneTurnGuard} from '../runtime/phone-turn-guard.mjs';

test('an empty target tree prevents detours into unrelated apps and binds visual actions',()=>{
  const guard=new PhoneTurnGuard();
  guard.observed('screen_read',{packageName:'com.tencent.mm',nodes:[]});
  assert.throws(()=>guard.before('app_open',{packageName:'com.android.chrome'}),/其他应用/);
  assert.equal(guard.before('ui_input',{text:'小贼'}).expectedPackageName,'com.tencent.mm');
  assert.deepEqual(guard.before('app_open',{packageName:'com.tencent.mm'}),{packageName:'com.tencent.mm'});
});
test('repeated empty reads stop while a normal tree does not create a restriction',()=>{
  const guard=new PhoneTurnGuard();
  guard.observed('screen_read',{packageName:'test.app',nodes:[{}]});
  assert.equal(guard.packageName,null);
  guard.observed('screen_read',{packageName:'test.app',nodes:[]});
  guard.observed('screen_read',{packageName:'test.app',nodes:[]});
  assert.throws(()=>guard.observed('screen_read',{packageName:'test.app',nodes:[]}),/重复读屏/);
});

test('an input submitted without read-back cannot be blindly repeated',()=>{
  const guard=new PhoneTurnGuard(),args={packageName:'test.app',text:'中文查询'};
  guard.observed('ui_input',{performed:true,verified:false},args);
  assert.throws(()=>guard.before('ui_input',args),/避免重复输入/);
  assert.doesNotThrow(()=>guard.before('screen_capture',{}));
});

test('adjacent split-screen launch requires confirmed split state',()=>{
  const guard=new PhoneTurnGuard();
  guard.observed('screen_read',{packageName:'test.app',nodes:[],readable:false});
  assert.throws(()=>guard.before('split_screen',{action:'launch',packageName:'other.app'}),/尚未确认进入分屏/);
  guard.observed('split_screen',{split:true,applicationWindows:2});
  assert.doesNotThrow(()=>guard.before('split_screen',{action:'launch',packageName:'other.app'}));
  guard.observed('split_screen',{state:{split:false,applicationWindows:1}});
  assert.throws(()=>guard.before('split_screen',{action:'launch',packageName:'other.app'}),/尚未确认进入分屏/);
});
