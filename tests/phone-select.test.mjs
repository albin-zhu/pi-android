import {test} from 'node:test';
import assert from 'node:assert/strict';
import {choosePhoneTarget} from '../runtime/phone-select.mjs';

const pkg='com.android.settings';
const node=(nodeId,text,{parentId=null,clickable=false,description='',packageName=pkg,bounds=[0,0,200,60]}={})=>({nodeId,parentId,text,description,package:packageName,clickable,bounds});
const screen=nodes=>({height:1000,nodes});

test('unique visible label selects its clickable parent',()=>{
  const value=choosePhoneTarget(screen([node('root',''),node('row','',{parentId:'root',clickable:true}),node('label','蓝牙',{parentId:'row'})]),['蓝牙'],pkg);
  assert.equal(value.match.nodeId,'row');
});

test('normalization and exact match beat a longer partial label',()=>{
  const value=choosePhoneTarget(screen([node('wifi','Wi-Fi',{clickable:true}),node('wifi-info','Wi-Fi 设置',{clickable:true})]),['wifi'],pkg);
  assert.equal(value.match.nodeId,'wifi');
});

test('missing, ambiguous, wrong-package, and static labels never select a node',()=>{
  assert.equal(choosePhoneTarget(screen([node('wifi','Wi-Fi',{clickable:true})]),['蓝牙'],pkg).reason,'not_found');
  assert.equal(choosePhoneTarget(screen([node('one','蓝牙',{clickable:true}),node('two','蓝牙',{clickable:true,bounds:[0,80,200,140]})]),['蓝牙'],pkg).reason,'ambiguous');
  assert.equal(choosePhoneTarget(screen([node('one','蓝牙',{clickable:true})]),['蓝牙'],'other.package').reason,'not_found');
  assert.equal(choosePhoneTarget(screen([node('one','蓝牙')]),['蓝牙'],pkg).reason,'not_clickable');
});

test('disabled or password targets and cross-package ancestors are rejected',()=>{
  assert.equal(choosePhoneTarget(screen([{...node('off','确认',{clickable:true}),enabled:false}]),['确认'],pkg).reason,'not_found');
  assert.equal(choosePhoneTarget(screen([{...node('secret','确认',{clickable:true}),password:true}]),['确认'],pkg).reason,'not_found');
  const value=choosePhoneTarget(screen([node('parent','',{clickable:true,packageName:'other.app'}),node('label','确认',{parentId:'parent'})]),['确认'],pkg);
  assert.equal(value.reason,'not_clickable');
});
