import fs from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {Client} from '@modelcontextprotocol/sdk/client/index.js';
import {StreamableHTTPClientTransport} from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import {SSEClientTransport} from '@modelcontextprotocol/sdk/client/sse.js';
import {StdioClientTransport,getDefaultEnvironment} from '@modelcontextprotocol/sdk/client/stdio.js';
import {ListRootsRequestSchema,ToolListChangedNotificationSchema} from '@modelcontextprotocol/sdk/types.js';

export const connectorRecipes=[
  {name:'远程 MCP',transport:'http',url:'',description:'粘贴服务提供的 MCP 地址，支持 Streamable HTTP 与旧版 SSE。',docs:'https://modelcontextprotocol.io/docs/develop/connect-remote-servers'},
  {name:'GitHub',transport:'http',url:'https://api.githubcopilot.com/mcp/',auth:'bearer',description:'使用 GitHub MCP 服务，填写有相应仓库权限的 Token。',docs:'https://github.com/github/github-mcp-server'},
  {name:'本机文件',transport:'stdio',command:'npx',args:['-y','@modelcontextprotocol/server-filesystem','{workspace}'],description:'在手机运行文件工具，需要先安装 Node.js 和 npm。首次连接会下载服务器包。',docs:'https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem'},
  {name:'本机记忆',transport:'stdio',command:'npx',args:['-y','@modelcontextprotocol/server-memory'],description:'在手机运行知识图谱记忆工具，需要 Node.js 和 npm。',docs:'https://github.com/modelcontextprotocol/servers/tree/main/src/memory'}
];
const authModes=new Set(['none','bearer','headers','oauth']);
function object(value){return value&&typeof value==='object'&&!Array.isArray(value);}
function strings(value,label){
  if(value===undefined)return {};
  if(!object(value))throw new Error(label+' 必须是 JSON 对象');
  const out={};for(const [key,item] of Object.entries(value)){
    if(!key||key.includes('\0')||typeof item!=='string'||item.includes('\0'))throw new Error(label+' 的键和值必须是有效文本');
    out[key]=item;
  }return out;
}
function endpoint(raw){
  let url;try{url=new URL(String(raw));}catch{throw new Error('请输入完整的 http:// 或 https:// MCP 地址');}
  if(!['http:','https:'].includes(url.protocol)||url.username||url.password||url.hash)throw new Error('MCP 地址需要 HTTP(S)，认证信息请填在认证栏');
  return url.href;
}
function publicURL(raw){try{const url=new URL(raw);return url.origin+url.pathname;}catch{return '';}}
function identity(config){return JSON.stringify([config.transport,config.url,config.command,config.args]);}

export function normalizeConnector(raw,previous){
  if(!object(raw))throw new Error('连接配置必须是对象');
  const name=String(raw.name||'').trim();if(!name||name.length>80)throw new Error('请填写 1 到 80 个字符的连接名称');
  const transport=raw.transport||(raw.url?'http':'stdio');
  if(!['http','sse','stdio'].includes(transport))throw new Error('连接方式只能是远程 HTTP、SSE 或本机命令');
  const config={id:previous?.id||randomUUID(),name,transport,enabled:raw.enabled??previous?.enabled??true};
  if(transport==='stdio'){
    config.command=String(raw.command||'').trim();if(!config.command||config.command.includes('\0'))throw new Error('请填写本机程序名称或路径');
    config.args=raw.keepArgs&&previous?.command===config.command?previous.args:(raw.args??[]);if(!Array.isArray(config.args)||config.args.some(arg=>typeof arg!=='string'||arg.includes('\0')))throw new Error('参数需要是字符串数组，每项对应一个参数');
    config.cwd=String(raw.cwd||'');config.env=strings(raw.env,'环境变量');
    config.auth='none';
  }else{
    config.url=endpoint(raw.keepAddress&&previous?previous.url:raw.url);config.auth=raw.auth||(raw.headers&&Object.keys(raw.headers).length?'headers':'none');
    if(!authModes.has(config.auth))throw new Error('不支持的认证方式');
    config.headers=strings(raw.headers,'Headers');
    for(const [key,value] of Object.entries(config.headers))if(!/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(key)||/[\r\n]/.test(value))throw new Error('Headers 格式无效');
    config.token=String(raw.token||'');
    config.clientId=String(raw.clientId||'');config.clientSecret=String(raw.clientSecret||'');config.issuer=raw.issuer?endpoint(raw.issuer):'';
    if(config.issuer&&new URL(config.issuer).search)throw new Error('授权服务地址不能包含查询参数');
    if(config.clientId&&!config.issuer)throw new Error('预注册 Client ID 需要填写对应的授权服务地址');
    if(config.clientSecret&&!config.clientId)throw new Error('Client Secret 需要对应的 Client ID');
  }
  if(previous&&identity(config)===identity(previous)&&config.auth===previous.auth){
    if(raw.keepSecrets){config.token=config.token||previous.token;}
    if(raw.keepHeaders)config.headers=previous.headers||{};
    if(raw.keepEnvironment)config.env=previous.env||{};
    if(config.clientId===previous.clientId&&config.issuer===previous.issuer){
      if(raw.keepSecrets&&!config.clientSecret)config.clientSecret=previous.clientSecret||'';
      if((config.clientSecret||'')===(previous.clientSecret||''))config.oauth=previous.oauth;
    }
  }
  return config;
}

export class ConnectorManager {
  constructor({directory,workspace,onChange=()=>{},environment=async()=>({}),resolveCommand=command=>command,allowStdio=true,redirectUrl='dev.zfold.assistant://connector-oauth',connectTimeout=20000}){
    this.directory=directory;this.workspace=workspace;this.onChange=onChange;this.environment=environment;this.resolveCommand=resolveCommand;
    this.allowStdio=allowStdio;this.redirectUrl=redirectUrl;this.connectTimeout=connectTimeout;this.entries=new Map();this.configs=new Map();
    this.file=path.join(directory,'connectors.json');this.storeError='';this.callbacks=new Map();
    try{const saved=JSON.parse(fs.readFileSync(this.file,'utf8'));if(!Array.isArray(saved.servers))throw new Error('format');for(const item of saved.servers){if(!item.id||!item.name)throw new Error('format');this.configs.set(item.id,item);}}
    catch(error){if(error.code!=='ENOENT')this.storeError='连接配置无法读取，原文件已保留。可通过导入配置恢复。';}
  }
  persist(){
    if(this.storeError)throw new Error(this.storeError);
    fs.mkdirSync(this.directory,{recursive:true,mode:0o700});
    fs.writeFileSync(this.file+'.tmp',JSON.stringify({version:1,servers:[...this.configs.values()]},null,2),{mode:0o600});
    fs.renameSync(this.file+'.tmp',this.file);
  }
  changed(){this.onChange(this.snapshot());}
  publicConfig(config){
    const entry=this.entries.get(config.id);const state=entry?.state||(config.enabled?'disconnected':'disabled');
    return {id:config.id,name:config.name,transport:config.transport,url:publicURL(config.url),
      address:config.transport==='stdio'?config.command:publicURL(config.url),command:config.command||'',
      args:[],argsCount:config.args?.length||0,cwd:config.cwd||'',auth:config.auth,
      enabled:config.enabled,state,connected:state==='connected',error:entry?.error||'',
      headerNames:Object.keys(config.headers||{}),environmentNames:Object.keys(config.env||{}),hasToken:Boolean(config.token),
      authenticated:Boolean(config.oauth?.tokens),toolCount:entry?.tools?.length||0,
      tools:(entry?.tools||[]).slice(0,12).map(tool=>({name:tool.name,description:String(tool.description||'').slice(0,500)})),
      authUrl:entry?.authUrl||'',serverName:entry?.serverName||'',clientId:config.clientId||'',issuer:config.issuer||(this.needsClient(config)?config.oauth?.discovery?.authorizationServerUrl:'')||'',hasClientSecret:Boolean(config.clientSecret),oauthNeedsClient:this.needsClient(config),redirectUrl:this.redirectUrl};
  }
  needsClient(config){const metadata=config.oauth?.discovery?.authorizationServerMetadata;return config.auth==='oauth'&&Boolean(metadata)&&!metadata.registration_endpoint&&!config.clientId&&!config.oauth?.client;}
  snapshot(){
    const servers=[...this.configs.values()].map(config=>this.publicConfig(config));
    return {connected:servers.some(server=>server.connected),servers,error:this.storeError,recipes:connectorRecipes,
      toolCount:servers.reduce((count,server)=>count+server.toolCount,0)};
  }
  redact(error,config){
    let message=String(error?.message||error||'连接未完成');
    if(config.url)message=message.replaceAll(config.url,publicURL(config.url));
    const headerValues=Object.values(config.headers||{});
    const secrets=[config.token,config.clientSecret,config.oauth?.client?.client_secret,config.oauth?.state,...(this.entries.get(config.id)?.temporarySecrets||[]),...headerValues,...headerValues.flatMap(value=>value.split(/[ ;=]/)),...Object.values(config.env||{}),...(config.args||[]),config.oauth?.tokens?.access_token,config.oauth?.tokens?.refresh_token,config.oauth?.verifier];
    for(const client of [{client_id:config.clientId,client_secret:config.clientSecret},config.oauth?.client])if(client?.client_secret)secrets.push(Buffer.from(client.client_id+':'+client.client_secret).toString('base64'));
    if(config.url){try{for(const value of new URL(config.url).searchParams.values())secrets.push(value);}catch{}}
    for(const secret of secrets)if(typeof secret==='string'&&secret.length>=4){message=message.replaceAll(secret,'[已隐藏]').replaceAll(encodeURIComponent(secret),'[已隐藏]');}
    return message.slice(0,600);
  }
  save(raw){
    if(this.storeError)throw new Error(this.storeError);
    const previous=raw.id?this.configs.get(raw.id):null;if(raw.id&&!previous)throw new Error('连接不存在');
    const config=normalizeConnector(raw,previous);
    const old=this.entries.get(config.id);if(old){old.intentional=true;old.abort?.abort();this.entries.delete(config.id);Promise.resolve(old.client?old.client.close():old.transport?.close()).catch(()=>{});}
    this.configs.set(config.id,config);this.persist();this.changed();return config.id;
  }
  importJSON(text){
    let document;try{document=JSON.parse(text);}catch{throw new Error('JSON 格式无效，请粘贴包含 mcpServers 或 servers 的配置');}
    const source=document.mcpServers||document.servers;
    if(!object(source))throw new Error('配置需要包含 mcpServers 或 servers 对象');
    const imported=Object.entries(source).map(([name,item])=>normalizeConnector({...item,name,transport:item.type==='sse'?'sse':item.url?'http':'stdio',auth:item.auth||((item.headers&&Object.keys(item.headers).length)?'headers':item.url?'oauth':'none')}));
    if(!imported.length)throw new Error('配置中没有连接');
    if(this.storeError){const recovery=this.file+'.recovery-'+Date.now();fs.copyFileSync(this.file,recovery);fs.chmodSync(recovery,0o600);this.storeError='';}
    for(const config of imported)this.configs.set(config.id,config);this.persist();this.changed();return imported.map(item=>item.id);
  }
  provider(config,entry){
    const stableIdentity=identity(config);const manager=this;
    const update=mutate=>{const latest=manager.configs.get(config.id);if(latest!==config||!config.enabled||manager.entries.get(config.id)!==entry||entry.intentional||identity(latest)!==stableIdentity)throw new Error('连接配置已变化，请重新登录');latest.oauth??={};mutate(latest.oauth);manager.persist();};
    return {
      redirectUrl:this.redirectUrl,
      clientMetadata:{client_name:'Zfold 手机工作台',redirect_uris:[this.redirectUrl],grant_types:['authorization_code','refresh_token'],response_types:['code'],token_endpoint_auth_method:'none'},
      state(){const state=randomUUID();update(oauth=>{oauth.state=state;oauth.startedAt=Date.now();});return state;},
      clientInformation:()=>config.oauth?.client||(config.clientId?{client_id:config.clientId,...(config.clientSecret?{client_secret:config.clientSecret}:{}),issuer:config.issuer||undefined}:undefined),
      // GitHub documents form-encoded client_secret; its discovery document omits
      // token_endpoint_auth_methods_supported, for which the SDK defaults to Basic.
      ...(config.clientSecret&&config.issuer==='https://github.com/login/oauth'?{addClientAuthentication(headers,params,url){
        if(new URL(url).href!=='https://github.com/login/oauth/access_token')throw new Error('GitHub 授权交换地址不匹配');
        params.set('client_id',config.clientId);params.set('client_secret',config.clientSecret);
      }}:{}),
      saveClientInformation:client=>update(oauth=>{oauth.client=client;}),
      tokens:()=>config.oauth?.tokens,
      saveTokens:tokens=>update(oauth=>{oauth.tokens=tokens;delete oauth.state;delete oauth.verifier;delete oauth.authorizationUrl;}),
      redirectToAuthorization:url=>{entry.authUrl=endpoint(url.href);update(oauth=>{oauth.authorizationUrl=entry.authUrl;});entry.state='auth_required';manager.changed();},
      saveCodeVerifier:verifier=>update(oauth=>{oauth.verifier=verifier;}),
      codeVerifier:()=>{if(!config.oauth?.verifier)throw new Error('登录会话已过期，请重新连接');return config.oauth.verifier;},
      saveDiscoveryState:discovery=>update(oauth=>{if(config.issuer&&endpoint(discovery.authorizationServerUrl)!==config.issuer)throw new Error('发现的授权服务与预注册 Client ID 不匹配');oauth.discovery=discovery;}),
      discoveryState:()=>config.oauth?.discovery,
      invalidateCredentials(scope){update(oauth=>{if(scope==='all')for(const key of Object.keys(oauth))delete oauth[key];else delete oauth[scope==='client'?'client':scope==='tokens'?'tokens':scope==='verifier'?'verifier':'discovery'];});}
    };
  }
  async disconnect(id,{disable=false,remove=false}={}){
    const config=this.configs.get(id);if(!config)throw new Error('连接不存在');
    const entry=this.entries.get(id);if(entry){entry.intentional=true;entry.abort?.abort();this.entries.delete(id);}
    if(remove)this.configs.delete(id);else if(disable){config.enabled=false;if(config.oauth){delete config.oauth.state;delete config.oauth.verifier;delete config.oauth.authorizationUrl;}}
    this.persist();this.changed();
    if(entry){await entry.client?.close().catch(()=>{});await entry.transport?.close().catch(()=>{});}
  }
  async connect(id,{authorizationCode}={}){
    let config=this.configs.get(id);if(!config)throw new Error('连接不存在');
    const old=this.entries.get(id);if(old){old.intentional=true;old.abort?.abort();}
    config.enabled=true;this.persist();
    const entry={state:'connecting',tools:[],error:'',intentional:false,abort:new AbortController(),temporarySecrets:authorizationCode?[authorizationCode]:[]};this.entries.set(id,entry);this.changed();
    const valid=()=>this.entries.get(id)===entry&&!entry.intentional;
    if(old){await Promise.resolve(old.client?old.client.close():old.transport?.close()).catch(()=>{});if(!valid())return this.publicConfig(config);}
    const deadline=setTimeout(()=>entry.abort.abort(new Error('连接超时，请检查 MCP 地址和网络')),this.connectTimeout);
    const transportFetch=(input,init={})=>{const confidential=String(new Headers(init.headers).get('authorization')||'').startsWith('Basic ')||new URLSearchParams(typeof init.body==='string'||init.body instanceof URLSearchParams?init.body:'').has('client_secret');return fetch(input,{...init,...(confidential?{redirect:'error'}:{}),signal:AbortSignal.any([init.signal,entry.abort.signal].filter(Boolean))});};
    const headers={...config.headers};if(config.auth==='bearer'&&config.token)headers.Authorization='Bearer '+config.token;
    const provider=config.auth==='oauth'?this.provider(config,entry):undefined;
    const createClient=()=>{
      const client=new Client({name:'zfold-android',version:'0.2.70'},{capabilities:{roots:{listChanged:true}}});
      client.setRequestHandler(ListRootsRequestSchema,async()=>({roots:[{uri:pathToFileURL(this.workspace()).href,name:'当前项目'}]}));
      client.setNotificationHandler(ToolListChangedNotificationSchema,()=>{if(valid()&&entry.state==='connected')this.loadTools(config,entry).catch(error=>{entry.error=this.redact(error,config);this.changed();});});
      client.onclose=()=>{if(valid()){entry.state='disconnected';entry.error='连接已关闭，点击重新连接';entry.tools=[];this.changed();}};
      client.onerror=()=>{};return client;
    };
    try{
      if(config.transport==='stdio'){
        if(!this.allowStdio)throw new Error('本机 MCP 命令需要完整执行版本；远程 MCP 仍可使用');
        const environment=await this.environment();const command=this.resolveCommand(config.command);
        if(!valid())return this.publicConfig(config);
        const cwd=config.cwd?path.resolve(this.workspace(),config.cwd):this.workspace();
        if(!fs.statSync(cwd,{throwIfNoEntry:false})?.isDirectory())throw new Error('工作目录不存在');
        entry.transport=new StdioClientTransport({command,args:config.args.map(arg=>arg.replaceAll('{workspace}',this.workspace())),cwd,
          env:{...getDefaultEnvironment(),...environment,...config.env},stderr:'pipe',maxBufferSize:4*1024*1024});
        entry.transport.stderr?.on('data',()=>{});
      }else{
        const options={requestInit:{headers},authProvider:provider,redirectPolicy:'same-origin',fetch:transportFetch};
        entry.transport=config.transport==='sse'?new SSEClientTransport(new URL(config.url),options):new StreamableHTTPClientTransport(new URL(config.url),options);
        if(authorizationCode)await entry.transport.finishAuth(authorizationCode);
      }
      if(!valid()){await entry.transport.close();return this.publicConfig(config);}
      entry.client=createClient();
      try{await entry.client.connect(entry.transport,{timeout:this.connectTimeout});}
      catch(error){
        const code=Number(error.code);
        if(config.transport==='http'&&[404,405].includes(code)&&valid()){
          entry.client.onclose=undefined;await entry.client.close().catch(()=>{});
          entry.transport=new SSEClientTransport(new URL(config.url),{requestInit:{headers},authProvider:provider,fetch:transportFetch});entry.client=createClient();
          await entry.client.connect(entry.transport,{timeout:this.connectTimeout});
        }else throw error;
      }
      if(!valid()){await entry.client.close();return;}
      entry.serverName=entry.client.getServerVersion()?.name||'';
      await this.loadTools(config,entry);if(!valid()){await entry.client.close();return this.publicConfig(config);}entry.state='connected';entry.authUrl='';this.changed();return this.publicConfig(config);
    }catch(error){
      if(valid()){
        entry.state=entry.authUrl?'auth_required':'error';entry.error=entry.authUrl?'请在浏览器完成登录':this.needsClient(config)?'该服务不支持自动注册 OAuth 客户端。请填写预注册 Client ID（服务要求时还需 Client Secret），或使用服务提供的 Token。':this.redact(entry.abort.signal.aborted?entry.abort.signal.reason:error,config);
        entry.intentional=true;await entry.client?.close().catch(()=>{});await entry.transport?.close().catch(()=>{});entry.intentional=false;this.changed();
      }
      return this.publicConfig(config);
    }finally{clearTimeout(deadline);entry.temporarySecrets=[];}
  }
  async loadTools(config,entry){
    if(!entry.client.getServerCapabilities()?.tools){entry.tools=[];this.changed();return;}
    const all=[];let cursor;const seen=new Set();
    do{const page=await entry.client.listTools(cursor?{cursor}:{},{timeout:this.connectTimeout});all.push(...page.tools);cursor=page.nextCursor;if(cursor&&seen.has(cursor))throw new Error('服务器返回了重复的工具目录游标');if(cursor)seen.add(cursor);}while(cursor);
    entry.tools=all;this.changed();
  }
  async finishOAuth(callback){
    const url=new URL(callback);const expected=new URL(this.redirectUrl);
    if(url.protocol!==expected.protocol||url.host!==expected.host||url.pathname!==expected.pathname||url.username||url.password||url.hash)throw new Error('登录回调地址不匹配');
    const state=url.searchParams.get('state'),code=url.searchParams.get('code');
    if(url.searchParams.getAll('state').length!==1||url.searchParams.getAll('code').length>1||url.searchParams.getAll('iss').length>1)throw new Error('登录回调参数无效');
    const pending=this.callbacks.get(state);
    if(pending){if(pending.code!==code||pending.issuer!==url.searchParams.get('iss')||url.searchParams.has('error'))throw new Error('回调与正在处理的登录不一致');return pending.operation;}
    const config=[...this.configs.values()].find(config=>state&&config.oauth?.state===state);
    if(!config||!config.enabled||!Number.isFinite(config.oauth.startedAt)||Date.now()-config.oauth.startedAt>10*60_000)throw new Error('登录已过期，请从连接页面重新登录');
    const metadata=config.oauth.discovery?.authorizationServerMetadata;
    if((metadata?.authorization_response_iss_parameter_supported||url.searchParams.has('iss'))&&url.searchParams.get('iss')!==metadata?.issuer)throw new Error('回调的授权服务不匹配，请重新登录');
    if(url.searchParams.has('error')||!code)throw new Error('登录未完成，可重新连接后重试');
    const operation=this.connect(config.id,{authorizationCode:code}).then(result=>{if(result?.state!=='connected')throw new Error(result?.authenticated?'授权已保存，连接尚未完成，请在连接页重试':result?.error||'登录未完成，请重新连接');return result;});
    this.callbacks.set(state,{operation,code,issuer:url.searchParams.get('iss')});try{return await operation;}finally{this.callbacks.delete(state);}
  }
  async restore(){
    const pending=[];
    for(const config of this.configs.values())if(config.enabled){
      if(config.oauth?.state&&Date.now()-config.oauth.startedAt<10*60_000){
        this.entries.set(config.id,{state:'auth_required',tools:[],authUrl:config.oauth.authorizationUrl||'',error:'等待完成浏览器登录'});
      }else pending.push(this.connect(config.id));
    }
    this.changed();await Promise.allSettled(pending);
  }
  async close(){await Promise.allSettled([...this.entries.keys()].map(id=>this.disconnect(id)));}
  search({id,query='',offset=0,limit=20}={}){
    const found=[];const text=query.trim().toLowerCase();
    for(const config of this.configs.values()){
      if(id&&config.id!==id)continue;const entry=this.entries.get(config.id);if(entry?.state!=='connected')continue;
      for(const tool of entry.tools)if(!text||(config.name+' '+tool.name+' '+tool.description).toLowerCase().includes(text))found.push({connectorId:config.id,connector:config.name,...tool});
    }
    const start=Math.max(0,offset|0),size=Math.max(1,Math.min(100,limit|0));
    return {total:found.length,tools:found.slice(start,start+size),nextOffset:start+size<found.length?start+size:null,
      connections:[...this.configs.values()].map(config=>({id:config.id,name:config.name,state:this.entries.get(config.id)?.state||'disconnected'}))};
  }
  redactJSON(value,config){return JSON.stringify(value,(_key,item)=>typeof item==='string'?this.redact(item,config):item);}
  async call(id,name,args={},signal){
    const config=this.configs.get(id),entry=this.entries.get(id);
    if(!config||entry?.state!=='connected')throw new Error('连接尚未就绪，请先在连接页面连接服务');
    if(!entry.tools.some(tool=>tool.name===name))throw new Error('工具不存在，请重新查询连接的工具目录');
    try{
      const result=await entry.client.callTool({name,arguments:args},undefined,{signal,timeout:120000,resetTimeoutOnProgress:true});
      if(result.isError){result.content=(result.content||[]).map(item=>item.type==='text'?{...item,text:this.redact(item.text,config)}:item);if(result.structuredContent)result.structuredContent=JSON.parse(this.redactJSON(result.structuredContent,config));}
      return result;
    }
    catch(error){if(signal?.aborted)throw new Error('已请求取消外部工具；已发生的操作需要核对');throw new Error(this.redact(error,config));}
  }
}
