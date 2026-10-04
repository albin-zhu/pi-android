import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {ConnectorManager} from '../runtime/connectors.mjs';
import {startConnectorFixture} from '../scripts/connector-fixture.mjs';

test('remote MCP saves privately, connects, discovers, calls and restores without exposing secrets',async()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'connectors-'));const fixture=await startConnectorFixture();
  const events=[];const create=()=>new ConnectorManager({directory:root,workspace:()=>root,onChange:event=>events.push(event)});let manager=create();
  try{
    const id=manager.save({name:'Fixture',url:fixture.origin+'/bearer?private=hidden-query',auth:'bearer',token:'fixture-bearer-secret'});
    await manager.connect(id);assert.equal(manager.snapshot().servers[0].state,'connected');
    const search=manager.search({query:'supplied'});assert.equal(search.tools[0].name,'echo');
    const result=await manager.call(id,'echo',{value:'hello'});assert.equal(result.content[0].text,'MCP_RESULT:hello');
    assert.equal(fs.statSync(manager.file).mode&0o777,0o600);
    assert.ok(!JSON.stringify(events).includes('fixture-bearer-secret'));assert.ok(!JSON.stringify(events).includes('hidden-query'));
    await manager.close();manager=create();await manager.restore();assert.equal(manager.snapshot().servers[0].state,'connected');
    assert.equal((await manager.call(id,'fail')).isError,true);
    await manager.disconnect(id,{disable:true});assert.equal(manager.snapshot().servers[0].enabled,false);
    await manager.disconnect(id,{remove:true});assert.equal(manager.snapshot().servers.length,0);
  }finally{await manager.close();await fixture.close();fs.rmSync(root,{recursive:true,force:true});}
});

test('MCP OAuth uses PKCE, checks state and preserves SDK issuer-bound credentials',async()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'connector-oauth-'));const fixture=await startConnectorFixture();
  let manager=new ConnectorManager({directory:root,workspace:()=>root});
  try{
    const id=manager.save({name:'OAuth',url:fixture.origin+'/oauth',auth:'oauth'});
    await manager.connect(id);let status=manager.snapshot().servers[0];assert.equal(status.state,'auth_required',JSON.stringify(status));
    await assert.rejects(manager.finishOAuth(manager.redirectUrl+'?code=x&state=wrong'),/过期/);
    const redirect=await fetch(status.authUrl,{redirect:'manual'});
    await manager.close();manager=new ConnectorManager({directory:root,workspace:()=>root});await manager.restore();
    assert.equal(manager.snapshot().servers[0].state,'auth_required','A restart must preserve pending PKCE state');
    await manager.finishOAuth(redirect.headers.get('location'));
    status=manager.snapshot().servers[0];assert.equal(status.state,'connected',JSON.stringify(status));
    const saved=JSON.parse(fs.readFileSync(manager.file)).servers[0];assert.equal(saved.oauth.tokens.issuer,fixture.origin);
    assert.ok(!JSON.stringify(status).includes('fixture-access-secret'));
    assert.equal((await manager.call(id,'echo',{value:'oauth'})).content[0].text,'MCP_RESULT:oauth');
  }finally{await manager.close();await fixture.close();fs.rmSync(root,{recursive:true,force:true});}
});

test('local stdio MCP uses an explicit executable, project cwd and workspace argument',async()=>{
  const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'connector-stdio-')));
  const manager=new ConnectorManager({directory:root,workspace:()=>root});
  try{
    const id=manager.save({name:'Local',transport:'stdio',command:process.execPath,args:[path.resolve('scripts/connector-stdio-fixture.mjs'),'{workspace}']});
    await manager.connect(id);assert.equal(manager.snapshot().servers[0].state,'connected',JSON.stringify(manager.snapshot()));
    const result=await manager.call(id,'echo',{value:'local'});assert.equal(result.content[0].text,'STDIO_RESULT:local');
    assert.equal(result.structuredContent.cwd,root);assert.equal(result.structuredContent.argument,root);
    await manager.disconnect(id,{disable:true});assert.equal(manager.snapshot().servers[0].state,'disabled');
  }finally{await manager.close();fs.rmSync(root,{recursive:true,force:true});}
});

test('server error bodies cannot expose saved credentials',async()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'connector-redaction-'));const fixture=await startConnectorFixture();
  const manager=new ConnectorManager({directory:root,workspace:()=>root});
  try{const id=manager.save({name:'Bad',url:fixture.origin+'/error',auth:'bearer',token:'fixture-bearer-secret'});await manager.connect(id);assert.equal(manager.snapshot().servers[0].state,'error');assert.ok(!JSON.stringify(manager.snapshot()).includes('fixture-bearer-secret'));}
  finally{await manager.close();await fixture.close();fs.rmSync(root,{recursive:true,force:true});}
});

test('imports are validated atomically and edits preserve only explicitly retained secrets',async()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'connector-config-'));const manager=new ConnectorManager({directory:root,workspace:()=>root});
  try{
    assert.throws(()=>manager.importJSON('{oops'),/JSON/);
    assert.throws(()=>manager.importJSON(JSON.stringify({mcpServers:{good:{url:'https://example.com/mcp'},bad:{url:'file:///tmp'}}})),/HTTP/);
    assert.equal(manager.snapshot().servers.length,0);
    const [id]=manager.importJSON(JSON.stringify({mcpServers:{local:{command:'node',args:['secret-arg'],env:{PRIVATE_TOKEN:'fixture-secret-value'}}}}));
    assert.ok(!JSON.stringify(manager.snapshot()).includes('secret-arg'));assert.ok(!JSON.stringify(manager.snapshot()).includes('fixture-secret-value'));
    manager.save({id,name:'Renamed',transport:'stdio',command:'node',keepArgs:true,keepEnvironment:true});
    assert.equal(manager.configs.get(id).args[0],'secret-arg');assert.equal(manager.configs.get(id).env.PRIVATE_TOKEN,'fixture-secret-value');
  }finally{await manager.close();fs.rmSync(root,{recursive:true,force:true});}
});

test('unresponsive connections time out and disabling an in-flight connection prevents stale success',{timeout:5000},async()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'connector-timeout-')),fixture=await startConnectorFixture();
  const manager=new ConnectorManager({directory:root,workspace:()=>root,connectTimeout:80});
  try{
    const id=manager.save({name:'Stalled',url:fixture.origin+'/stall'});
    await manager.connect(id);assert.equal(manager.snapshot().servers[0].state,'error');assert.match(manager.snapshot().servers[0].error,/超时/);
    const pending=manager.connect(id);await new Promise(resolve=>setTimeout(resolve,10));
    await manager.disconnect(id,{disable:true});await pending;assert.equal(manager.snapshot().servers[0].state,'disabled');
  }finally{await manager.close();await fixture.close();fs.rmSync(root,{recursive:true,force:true});}
});

test('legacy SSE works both explicitly and through HTTP fallback',{timeout:5000},async()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'connector-sse-')),fixture=await startConnectorFixture();
  const manager=new ConnectorManager({directory:root,workspace:()=>root,connectTimeout:1000});
  try{
    for(const transport of ['sse','http']){
      const id=manager.save({name:transport,url:fixture.origin+'/sse',transport});await manager.connect(id);
      assert.equal(manager.publicConfig(manager.configs.get(id)).state,'connected',JSON.stringify(manager.snapshot()));
      assert.equal((await manager.call(id,'echo',{value:transport})).content[0].text,'MCP_RESULT:'+transport);
      await manager.disconnect(id,{remove:true});
    }
  }finally{await manager.close();await fixture.close();fs.rmSync(root,{recursive:true,force:true});}
});
