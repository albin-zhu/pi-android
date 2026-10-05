// UI projections only: full tool results remain in Pi's session, never duplicated
// as image/base64 payloads in the native activity cards.
const clip = (value, limit = 12000) => {
  const text = typeof value === 'string' ? value : JSON.stringify(value ?? {});
  return text.length > limit ? text.slice(0, limit) + '\n…（显示已截断）' : text;
};
export function toolCard(call, result, status) {
  const content = result?.content || [];
  const output = content.map(part => part.type === 'text' ? part.text : part.type === 'image' ? '[图片]' : '').filter(Boolean).join('\n');
  let performed = result?.details?.performed;
  let taskStatus = result?.details?.status;
  let verified=result?.details?.verified;
  if (performed === undefined && content.length === 1 && content[0].type === 'text') {
    try { const data=JSON.parse(content[0].text);performed=data.performed;taskStatus??=data.status;verified??=data.verified; } catch {}
  }
  if(taskStatus===undefined&&content.length===1&&content[0].type==='text'){try{taskStatus=JSON.parse(content[0].text).status;}catch{}}
  const taskState=taskStatus==='cancelled'?'cancelled':taskStatus==='blocked'?'blocked':['unverified','unknown','partial','partial_unknown'].includes(taskStatus)?'unverified':['failed','permission_denied','timeout'].includes(taskStatus)?'error':undefined;
  return {role:'tool', id:call.id, name:call.name, args:clip(call.arguments),
    status:status || taskState || (result ? result.isError ? 'error' : performed === false ? 'not_performed' : 'success' : 'interrupted'),
    output:clip(output), hasImage:content.some(part => part.type === 'image'),...(taskStatus?{taskStatus,verified:verified===true}:{})};
}

export function chatHistory(messages, activeTools = new Map()) {
  const results = new Map(messages.filter(m => m.role === 'toolResult').map(m => [m.toolCallId, m]));
  const items = [];
  for (const message of messages) {
    const parts = Array.isArray(message.content) ? message.content : [];
    if (message.role === 'user') {
      const picture = parts.find(part => part.type === 'image');
      items.push({role:'user', text:typeof message.content === 'string' ? message.content : parts.filter(p => p.type === 'text').map(p => p.text).join('\n'), hasImage:Boolean(picture), sourceImage:picture?.data});
    } else if (message.role === 'assistant') {
      if (typeof message.content === 'string' && message.content) items.push({role:'assistant', text:message.content});
      for (const part of parts) {
        if (part.type === 'text' && part.text) items.push({role:'assistant', text:part.text});
        if (part.type === 'toolCall') items.push(activeTools.get(part.id) || toolCard(part, results.get(part.id)));
      }
    }
  }
  // Execution can begin before the SDK adds its assistant message to history.
  for (const [id, card] of activeTools) if (!items.some(item => item.role === 'tool' && item.id === id)) items.push(card);
  const recent = items.slice(-80);
  let imageBudget = 2_000_000;
  for (let i = recent.length - 1; i >= 0; i--) {
    const item = recent[i];
    if (item.text) item.text = clip(item.text, 24000);
    if (item.sourceImage && item.sourceImage.length <= imageBudget) {item.image = item.sourceImage; imageBudget -= item.sourceImage.length;}
    delete item.sourceImage;
  }
  // PiRuntime's frame limit is 4 MiB; Chinese text must be budgeted as UTF-8.
  const sizes = recent.map(item => Buffer.byteLength(JSON.stringify(item)) + 1);
  let bytes = sizes.reduce((sum, size) => sum + size, 0);
  while (bytes > 3_700_000 && recent.length > 1) {bytes -= sizes.shift(); recent.shift();}
  return recent;
}
