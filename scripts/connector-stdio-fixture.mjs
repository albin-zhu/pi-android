import readline from 'node:readline';
const input=readline.createInterface({input:process.stdin});
input.on('line',line=>{
  const request=JSON.parse(line);if(request.id===undefined)return;
  let result;
  if(request.method==='initialize')result={protocolVersion:request.params.protocolVersion,serverInfo:{name:'Stdio fixture',version:'1'},capabilities:{tools:{}}};
  else if(request.method==='tools/list')result={tools:[{name:'echo',description:'Echo from a local process',inputSchema:{type:'object',properties:{value:{type:'string'}},required:['value']}}]};
  else if(request.method==='tools/call')result={content:[{type:'text',text:'STDIO_RESULT:'+request.params.arguments.value}],structuredContent:{cwd:process.cwd(),argument:process.argv[2]}};
  else result={};
  process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:request.id,result})+'\n');
});
