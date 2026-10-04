export function validateConfig(raw) {
  const api = String(raw.api || 'openai-responses').trim();
  const value = { baseUrl: String(raw.baseUrl || '').trim().replace(/\/+$/, ''), model: String(raw.model || '').trim(), apiKey: String(raw.apiKey || '').trim(), api };
  let url;
  try { url = new URL(value.baseUrl); } catch { throw new Error('请输入完整的模型接口地址'); }
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new Error('模型地址必须为 HTTP(S)，且不能包含密码、查询参数或片段');
  if (!value.model || value.model.length > 200) throw new Error('请输入模型名称');
  if (!['openai-responses', 'openai-completions'].includes(api)) throw new Error('模型消息格式只支持 openai-responses 或 openai-completions');
  return value;
}

export function validateProviderSelection(raw) {
  const provider=String(raw.provider||'custom').trim().toLowerCase();
  const model=String(raw.model||'').trim();
  if(!/^[a-z0-9][a-z0-9_.-]{0,63}$/.test(provider))throw new Error('供应商 ID 只能使用小写字母、数字、点、连字符或下划线');
  if(!model||model.length>300)throw new Error('请输入模型名称');
  const value={provider,model,baseUrl:String(raw.baseUrl||'').trim().replace(/\/+$/,''),apiKey:String(raw.apiKey||'').trim(),api:String(raw.api||'').trim()};
  if(value.baseUrl) {
    let url;
    try{url=new URL(value.baseUrl);}catch{throw new Error('请输入完整的模型接口地址');}
    if(!['https:','http:'].includes(url.protocol)||url.username||url.password||url.search||url.hash)throw new Error('模型地址必须为 HTTP(S)，且不能包含密码、查询参数或片段');
  }
  const supported=['openai-responses','openai-completions','anthropic-messages','google-generative-ai'];
  if(value.api&&!supported.includes(value.api))throw new Error('消息格式只支持 '+supported.join('、'));
  if(value.baseUrl&&!value.api)value.api='openai-responses';
  return value;
}

export function jsonLines(onMessage, onError, maxBytes = 2 * 1024 * 1024) {
  let buffer = '';
  return chunk => {
    buffer += chunk;
    if (Buffer.byteLength(buffer) > maxBytes) { buffer = ''; onError(new Error('消息过大')); return; }
    let i;
    while ((i = buffer.indexOf('\n')) !== -1) {
      const line = buffer.slice(0, i); buffer = buffer.slice(i + 1);
      if (!line.trim()) continue;
      try { onMessage(JSON.parse(line)); } catch (error) { onError(error); }
    }
  };
}
