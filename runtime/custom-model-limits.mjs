// Verified public gateway metadata; keep native-provider catalogs unchanged.
export const auditedLimits = Object.freeze({
  'glm-5.3-flashx':[1000000,128000],
  'gpt-6-astra':[922000,128000],
  'qwen3.8-max':[991000,65536], // min(max_tokens, max_output_tokens) until gateway semantics are clarified.
  'claude-fable-5-1':[1000000,128000],
  'claude-sonnet-5':[1000000,128000],
  'claude-opus-5-5':[1000000,128000],
  'deepseek-flash':[1048576,393216],
});
export function gatewayLimits(baseUrl,id){
  let url;try{url=new URL(baseUrl);}catch{return {};}
  if(url.protocol!=='https:'||url.hostname!=='llm.moontontech.net')return {};
  const limits=auditedLimits[id];return limits?{contextWindow:limits[0],maxTokens:limits[1]}:{};
}
export function selectedModelEntry(selection,existing){
  return {id:selection.model,name:selection.model,reasoning:false,input:['text','image'],contextWindow:128000,maxTokens:4096,cost:{input:0,output:0,cacheRead:0,cacheWrite:0},
    ...existing,...gatewayLimits(selection.baseUrl,selection.model),id:selection.model};
}
