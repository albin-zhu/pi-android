// Controlled MCP/OAuth endpoint: no external accounts or credentials are used.
import http from 'node:http';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';

export async function startConnectorFixture(port=0){
  const state={calls:[],authorization:undefined,tokenExchanges:0};const streams=new Map();let origin;
  const server=http.createServer(async(req,res)=>{
    const url=new URL(req.url,origin);let body='';for await(const part of req)body+=part;
    const json=(code,value,headers={})=>{res.writeHead(code,{'content-type':'application/json',...headers});res.end(JSON.stringify(value));};
    if(url.pathname==='/.well-known/oauth-protected-resource'){json(200,{resource:origin+'/oauth',authorization_servers:[origin],scopes_supported:['tools']});return;}
    if(url.pathname==='/.well-known/oauth-authorization-server'){json(200,{issuer:origin,authorization_endpoint:origin+'/authorize',token_endpoint:origin+'/token',registration_endpoint:origin+'/register',response_types_supported:['code'],grant_types_supported:['authorization_code','refresh_token'],code_challenge_methods_supported:['S256'],token_endpoint_auth_methods_supported:['none']});return;}
    if(url.pathname==='/.well-known/oauth-protected-resource/oauth'){json(200,{resource:origin+'/oauth',authorization_servers:[origin]});return;}
    if(url.pathname==='/register'){const input=JSON.parse(body);json(201,{...input,client_id:'fixture-client'});return;}
    if(url.pathname==='/authorize'){
      state.authorization=Object.fromEntries(url.searchParams);const redirect=new URL(url.searchParams.get('redirect_uri'));
      redirect.searchParams.set('code','fixture-code');redirect.searchParams.set('state',url.searchParams.get('state'));
      res.writeHead(302,{location:redirect.href});res.end();return;
    }
    if(url.pathname==='/token'){
      const input=new URLSearchParams(body);state.tokenExchanges++;
      if(input.get('grant_type')!=='refresh_token'){
        const challenge=createHash('sha256').update(input.get('code_verifier')||'').digest('base64url');
        if(input.get('code')!=='fixture-code'||challenge!==state.authorization?.code_challenge){json(400,{error:'invalid_grant'});return;}
      }
      json(200,{access_token:'fixture-access-secret',token_type:'Bearer',expires_in:3600,refresh_token:'fixture-refresh-secret',scope:'tools'});return;
    }
    if(url.pathname==='/sse'){
      if(req.method!=='GET'){res.writeHead(405);res.end();return;}
      const id=String(Date.now());streams.set(id,res);res.writeHead(200,{'content-type':'text/event-stream','cache-control':'no-cache'});res.write('event: endpoint\ndata: '+origin+'/messages?sessionId='+id+'\n\n');req.on('close',()=>streams.delete(id));return;
    }
    if(url.pathname==='/stall')return;
    if(!['/mcp','/bearer','/oauth','/error','/messages'].includes(url.pathname)){json(404,{error:'not found'});return;}
    if(url.pathname==='/bearer'&&req.headers.authorization!=='Bearer fixture-bearer-secret'){json(401,{error:'auth required'});return;}
    if(url.pathname==='/oauth'&&req.headers.authorization!=='Bearer fixture-access-secret'){
      json(401,{error:'auth required'},{'www-authenticate':`Bearer resource_metadata="${origin}/.well-known/oauth-protected-resource"`});return;
    }
    if(url.pathname==='/error'){json(500,{error:'fixture-bearer-secret must never appear in public state'});return;}
    if(req.method==='GET'){res.writeHead(405);res.end();return;}
    if(req.method==='DELETE'){res.writeHead(200);res.end();return;}
    let request;try{request=JSON.parse(body);}catch{json(400,{error:'invalid request'});return;}
    if(request.id===undefined){res.writeHead(202);res.end();return;}
    let result;
    if(request.method==='initialize')result={protocolVersion:request.params.protocolVersion,serverInfo:{name:'Controlled fixture',version:'1'},capabilities:{tools:{listChanged:false}}};
    else if(request.method==='tools/list')result={tools:[{name:'echo',description:'Return the supplied fixture value',inputSchema:{type:'object',properties:{value:{type:'string'}},required:['value']}},{name:'fail',description:'Return a controlled tool error',inputSchema:{type:'object',properties:{}}}]};
    else if(request.method==='tools/call'){
      state.calls.push(request.params);result=request.params.name==='fail'?{isError:true,content:[{type:'text',text:'Controlled tool failure'}]}:{content:[{type:'text',text:'MCP_RESULT:'+request.params.arguments.value}],structuredContent:{echo:request.params.arguments.value}};
    }else{json(200,{jsonrpc:'2.0',id:request.id,error:{code:-32601,message:'Unknown method'}});return;}
    const response={jsonrpc:'2.0',id:request.id,result};
    if(url.pathname==='/messages'){streams.get(url.searchParams.get('sessionId'))?.write('event: message\ndata: '+JSON.stringify(response)+'\n\n');res.writeHead(202);res.end();}
    else json(200,response);
  });
  await new Promise(resolve=>server.listen(port,'127.0.0.1',resolve));origin=`http://127.0.0.1:${server.address().port}`;
  return {origin,state,close:async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}};
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){const fixture=await startConnectorFixture(Number(process.env.CONNECTOR_FIXTURE_PORT||18874));console.log('Connector fixture: '+fixture.origin);}
