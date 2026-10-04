// Direct HTTP contract: https://docs.typesafe.ai/api
export const JEV_ENDPOINT = 'https://api.typesafe.ai/v1/systemone';
export const JEV_MODEL = 'jev-1.13.0';

export function validateJevConfig(raw, previous = {}) {
  const apiKey = String(raw.apiKey ?? previous.apiKey ?? '').trim();
  const model = String(raw.model || previous.model || JEV_MODEL).trim();
  const enabled = raw.enabled !== false;
  if (!/^jev-[a-zA-Z0-9.-]{1,80}$/.test(model)) throw new Error('请填写有效的 Jev 模型名称');
  if (enabled && (!apiKey || apiKey.length > 4096 || /\s/.test(apiKey))) throw new Error('请填写有效的 Jev API Key');
  return {apiKey, model, enabled};
}

export function validateChoice(answer, criteria) {
  const ids = Object.keys(criteria), distribution = answer?.probabilities;
  if (answer?.type !== 'choice' || !Object.hasOwn(criteria, answer.choice) || !distribution || Array.isArray(distribution) ||
      Object.keys(distribution).length !== ids.length || !ids.every(id => Object.hasOwn(distribution, id)) ||
      ![answer.confidence, ...Object.values(distribution)].every(n => Number.isFinite(n) && n >= 0 && n <= 1)) {
    throw new Error('Jev 返回了无效的候选选择');
  }
  const values = Object.values(distribution).sort((a,b) => b-a);
  if (Math.abs(values.reduce((a,b) => a+b, 0)-1) > 0.025 || distribution[answer.choice]+1e-6 < values[0]) throw new Error('Jev 返回的概率分布无效');
  return {...answer, margin:values[0]-(values[1] || 0)};
}

export async function jevChoice({config, state, criteria, instructions, signal, fetchImpl = fetch, timeoutMs = 15000}) {
  if (!config?.enabled || !config?.apiKey) throw new Error('请先在手机能力中配置 Jev');
  const body = JSON.stringify({model:config.model || JEV_MODEL, state, questions:{next_action:{type:'choice', instructions, criteria}}});
  if (Object.keys(criteria).length < 2 || Object.keys(criteria).length > 255 || Buffer.byteLength(body) > 120000) throw new Error('界面候选过多，请缩小手机操作范围');
  const started = performance.now();
  const timeout = AbortSignal.timeout(timeoutMs);
  const combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
  try {
    const response = await fetchImpl(JEV_ENDPOINT, {method:'POST', redirect:'error', headers:{Authorization:`Bearer ${config.apiKey}`, 'Content-Type':'application/json'}, body, signal:combined});
    if (!response.ok) {
      await response.body?.cancel();
      const message = response.status === 401 || response.status === 403 ? 'Jev 密钥无效或无访问权限' : response.status === 429 || response.status === 529 ? 'Jev 服务繁忙，请稍后重试' : `Jev 请求失败（HTTP ${response.status}）`;
      throw new Error(message);
    }
    const text = await response.text();
    if (Buffer.byteLength(text) > 512000) throw new Error('Jev 响应过大');
    let result;try {result = JSON.parse(text);} catch {throw new Error('Jev 返回的 JSON 无效');}
    const answer = validateChoice(result.answers?.next_action, criteria);
    return {...answer, model:typeof result.model === 'string' ? result.model.slice(0,100) : config.model, latencyMs:Math.round(performance.now()-started)};
  } catch (error) {
    if (signal?.aborted) throw new Error('操作已停止');
    if (timeout.aborted) throw new Error('Jev 请求超时，未执行手机动作');
    // Never echo provider response bodies, headers, URLs, or request state.
    if (error.message?.startsWith('Jev')) throw error;
    throw new Error('无法连接 Jev，未执行手机动作');
  }
}
