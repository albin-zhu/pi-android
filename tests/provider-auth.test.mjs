import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {ProviderAuthManager,authFailure} from '../runtime/provider-auth.mjs';
import {openaiCodexOAuth} from '../runtime/oauth-bundle.mjs';

const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(check){for(let i=0;i<200;i++){if(check())return;await delay(5);}throw new Error('auth fixture timeout');}
function harness(login,timeoutMs){const events=[];const runtime={getProvider:()=>({auth:{oauth:{login(){}}}}),login:(_p,_t,interaction)=>login(interaction)};const manager=new ProviderAuthManager({getRuntime:async()=>runtime,emit:event=>events.push(event),timeoutMs});return {events,manager};}

test('auth URL and true prompt ID survive panel reopening; submitted secrets are never in snapshots',async()=>{
  let supplied;const {manager,events}=harness(async interaction=>{interaction.notify({type:'auth_url',url:'https://auth.example/authorize?state=fixture'});supplied=await interaction.prompt({type:'manual_code',message:'callback',placeholder:'http://localhost:1455/auth/callback'});return {type:'oauth',access:'access-secret',refresh:'refresh-secret'};});
  const done=manager.start({requestId:'browser',provider:'fixture'});await until(()=>manager.prompts.size===1);
  const restored=manager.snapshots()[0];assert.ok(restored.browser.url);assert.match(restored.promptId,/^browser:prompt:/);
  manager.answer({requestId:'browser',promptId:'browser',value:'wrong'});assert.equal(manager.prompts.size,1);assert.equal(events.at(-1).state,'command_error');
  manager.answer({requestId:'browser',promptId:restored.promptId,value:'manual-private-code'});await done;
  assert.equal(supplied,'manual-private-code');assert.equal(manager.prompts.size,0);assert.equal(events.at(-1).state,'success');
  const output=JSON.stringify(events);for(const secret of ['manual-private-code','access-secret','refresh-secret'])assert.ok(!output.includes(secret));
});
test('provider cancellation, prompt-specific abort and timeout clean up outstanding prompts',async()=>{
  const {manager,events}=harness(interaction=>interaction.prompt({type:'secret',message:'key'}));const pending=manager.start({requestId:'cancelled',provider:'fixture'});await until(()=>manager.prompts.size===1);
  await manager.start({requestId:'duplicate',provider:'fixture'});assert.equal(manager.flows.size,1);manager.cancel('cancelled');await pending;assert.equal(events.at(-1).state,'cancelled');assert.equal(manager.prompts.size,0);
  const abort=new AbortController();const own=harness(async interaction=>{try{await interaction.prompt({type:'manual_code',signal:abort.signal});}catch{}return {type:'oauth'};});
  const done=own.manager.start({requestId:'local-abort',provider:'fixture'});await until(()=>own.manager.prompts.size===1);abort.abort();await done;assert.equal(own.manager.prompts.size,0);assert.equal(own.events.at(-1).state,'success');
  const timed=harness(interaction=>interaction.prompt({type:'secret'}),25);const timeout=timed.manager.start({requestId:'timeout',provider:'fixture'});await delay(60);await timeout;assert.equal(timed.events.at(-1).state,'expired');assert.equal(timed.manager.prompts.size,0);
});
test('error events do not include token responses or callback query strings',async()=>{
  const raw='OpenAI Codex token exchange response missing fields: {"access_token":"private-token","refresh_token":"private-refresh"}';
  assert.ok(!authFailure(new Error(raw)).includes('private'));
  const {manager,events}=harness(async()=>{throw new Error(raw);});await manager.start({requestId:'failure',provider:'fixture'});
  assert.equal(events.at(-1).state,'error');assert.equal(events.some(e=>e.type==='error'),false);assert.ok(!JSON.stringify(events).includes('private-token'));
});
test('bundled Codex browser and device-code SDK flows complete through the auth manager with mocked OpenAI endpoints',async()=>{
  const original=globalThis.fetch;const requests=[];let browser;
  const access='header.'+Buffer.from(JSON.stringify({'https://api.openai.com/auth':{chatgpt_account_id:'fixture-account'}})).toString('base64')+'.signature';
  globalThis.fetch=async(input,init)=>{
    const url=String(input);requests.push(url);
    if(url.endsWith('/deviceauth/usercode'))return Response.json({device_auth_id:'fixture-device',user_code:'ABCD-EFGH',interval:0});
    if(url.endsWith('/deviceauth/token'))return Response.json({authorization_code:'device-code',code_verifier:'device-verifier'});
    assert.equal(url,'https://auth.openai.com/oauth/token');const body=new URLSearchParams(init.body);
    if(body.get('code')==='browser-code'){assert.equal(body.get('redirect_uri'),'http://localhost:1455/auth/callback');assert.equal(createHash('sha256').update(body.get('code_verifier')).digest('base64url'),new URL(browser.url).searchParams.get('code_challenge'));}
    else assert.equal(body.get('redirect_uri'),'https://auth.openai.com/deviceauth/callback');
    return Response.json({access_token:access,refresh_token:'fixture-refresh',expires_in:3600});
  };
  try{
    for(const method of ['browser','device_code']){
      const {manager,events}=harness(interaction=>openaiCodexOAuth.login(interaction));const done=manager.start({requestId:method,provider:'openai-codex'});
      await until(()=>manager.prompts.size===1);manager.answer({requestId:method,promptId:manager.snapshots()[0].promptId,value:method});
      if(method==='browser'){
        await until(()=>manager.snapshots()[0].browser&&manager.snapshots()[0].prompt);const flow=manager.snapshots()[0];browser=flow.browser;
        const state=new URL(browser.url).searchParams.get('state');manager.answer({requestId:method,promptId:flow.promptId,value:'http://localhost:1455/auth/callback?code=browser-code&state='+state});
      }
      await done;assert.equal(events.at(-1).state,'success');assert.equal(manager.prompts.size,0);
      if(method==='device_code')assert.ok(events.some(e=>e.device?.userCode==='ABCD-EFGH'));
      assert.ok(!JSON.stringify(events).includes('fixture-refresh'));
    }
    assert.equal(requests.filter(url=>url.endsWith('/oauth/token')).length,2);
  }finally{globalThis.fetch=original;}
});
