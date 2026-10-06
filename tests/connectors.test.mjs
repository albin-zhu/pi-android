import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {ConnectorManager} from '../runtime/connectors.mjs';
import {startConnectorFixture} from '../scripts/connector-fixture.mjs';

test('non-DCR MCP explains registration and supports private pre-registered client credentials',async()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'mcp-preregistered-')),fixture=await startConnectorFixture(0,{registration:false,clientSecret:'fixture-client-secret'});
  const manager=new ConnectorManager({directory:root,workspace:()=>root});
  try{
    const id=manager.save({name:'Static OAuth',url:fixture.origin+'/oauth',auth:'oauth'});const missing=await manager.connect(id);
    assert.equal(missing.oauthNeedsClient,true);assert.match(missing.error,/Client ID/);assert.equal(missing.issuer,fixture.origin);
    manager.save({id,name:'Static OAuth',url:fixture.origin+'/oauth',auth:'oauth',clientId:'fixture-client',clientSecret:'fixture-client-secret',issuer:fixture.origin});
    const login=await manager.connect(id);assert.equal(login.state,'auth_required');assert.equal(login.hasClientSecret,true);assert.ok(!JSON.stringify(login).includes('fixture-client-secret'));
    const redirect=await fetch(login.authUrl,{redirect:'manual'});await manager.finishOAuth(redirect.headers.get('location'));
    assert.equal(manager.publicConfig(manager.configs.get(id)).state,'connected');assert.equal(fixture.state.tokenExchanges,1);
    manager.save({id,name:'Renamed connection',url:fixture.origin+'/oauth',auth:'oauth',clientId:'fixture-client',issuer:fixture.origin,keepSecrets:true});
    assert.equal(manager.configs.get(id).clientSecret,'fixture-client-secret');assert.ok(manager.configs.get(id).oauth.tokens);
    const redacted=manager.redact(new Error('fixture-client-secret '+Buffer.from('fixture-client:fixture-client-secret').toString('base64')),manager.configs.get(id));assert.ok(!redacted.includes('fixture-client-secret'));assert.ok(!redacted.includes(Buffer.from('fixture-client:fixture-client-secret').toString('base64')));
    manager.save({id,name:'Different client',url:fixture.origin+'/oauth',auth:'oauth',clientId:'different',issuer:fixture.origin,keepSecrets:true});assert.equal(manager.configs.get(id).clientSecret,'');assert.equal(manager.configs.get(id).oauth,undefined);
  }finally{await manager.close();await fixture.close();fs.rmSync(root,{recursive:true,force:true});}
});

test('MCP validates callback issuer, parameters and deduplicates the same code exchange',async()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'mcp-callback-')),fixture=await startConnectorFixture(0,{issuerResponse:true,tokenDelayMs:50});
  const manager=new ConnectorManager({directory:root,workspace:()=>root});
  try{
    const id=manager.save({name:'Issuer OAuth',url:fixture.origin+'/oauth',auth:'oauth'}),login=await manager.connect(id);
    const redirect=await fetch(login.authUrl,{redirect:'manual'}),callback=redirect.headers.get('location');
    const wrong=new URL(callback);wrong.searchParams.set('iss','https://other.example');await assert.rejects(manager.finishOAuth(wrong.href),/授权服务/);assert.equal(fixture.state.tokenExchanges,0);
    await assert.rejects(manager.finishOAuth(callback+'&state=duplicate'),/参数/);
    const first=manager.finishOAuth(callback);await assert.rejects(manager.finishOAuth(wrong.href),/不一致/);
    const both=await Promise.all([first,manager.finishOAuth(callback)]);assert.equal(both[0].connected,true);assert.equal(both[1].connected,true);assert.equal(fixture.state.tokenExchanges,1);
    await assert.rejects(manager.finishOAuth(callback),/过期/);
  }finally{await manager.close();await fixture.close();fs.rmSync(root,{recursive:true,force:true});}
});

test('GitHub client secret exchange is form encoded and pinned to its token endpoint',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'mcp-github-'));const manager=new ConnectorManager({directory:root,workspace:()=>root});
  try{
    const id=manager.save({name:'GitHub',url:'https://api.githubcopilot.com/mcp/',auth:'oauth',clientId:'fixture-client',clientSecret:'fixture-secret',issuer:'https://github.com/login/oauth'});
    const provider=manager.provider(manager.configs.get(id),{}),params=new URLSearchParams();
    provider.addClientAuthentication(new Headers(),params,'https://github.com/login/oauth/access_token');assert.equal(params.get('client_secret'),'fixture-secret');
    assert.throws(()=>provider.addClientAuthentication(new Headers(),new URLSearchParams(),'https://other.example/token'),/不匹配/);
  }finally{fs.rmSync(root,{recursive:true,force:true});}
});

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
