// User-specific picker preferences never delete provider credentials or SDK models.
const key=value=>typeof value==='string'?value.trim().toLowerCase():'';
const strings=value=>Array.isArray(value)?value.map(key).filter(Boolean):[];
const family=(id,prefix)=>id===prefix||id.startsWith(prefix+'.')||id.startsWith(prefix+'-');
export function filterModelCatalog(providers,preferences={}){
  const hidden=new Set(strings(preferences.hiddenProviders));
  const excluded=strings(preferences.excludedFamilies);
  const rules=Array.isArray(preferences.modelFamilies)?preferences.modelFamilies.filter(rule=>rule&&key(rule.family)):[];
  const preferred=strings(preferences.preferredProviders);
  const candidates=(Array.isArray(providers)?providers:[]).filter(p=>p&&typeof p.id==='string'&&!hidden.has(key(p.id)));
  const winners=new Map();
  const rank=p=>(p.configured?0:10000)+(preferred.includes(key(p.id))?preferred.indexOf(key(p.id)):1000);
  for(const provider of candidates)for(const model of Array.isArray(provider.models)?provider.models:[]){
    const id=key(model?.id);if(!id||excluded.some(prefix=>family(id,prefix)))continue;
    if(rules.some(rule=>family(id,key(rule.family))&&!strings(rule.allowed).some(prefix=>rule.exact?id===prefix:family(id,prefix))))continue;
    const existing=winners.get(id);
    if(!existing||rank(provider)<rank(existing.provider))winners.set(id,{provider,model});
  }
  return candidates.flatMap(provider=>{
    const used=new Set();
    const models=(Array.isArray(provider.models)?provider.models:[]).filter(model=>{
      const id=key(model?.id),winner=winners.get(id);
      if(!winner||winner.provider!==provider||used.has(id))return false;
      used.add(id);return true;
    });
    return models.length?[{...provider,models}]:[];
  });
}
