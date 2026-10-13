import {test} from 'node:test';
import assert from 'node:assert/strict';
import {filterModelCatalog} from '../runtime/model-picker-preferences.mjs';
const provider=(id,models,configured=true)=>({id,configured,models:models.map(id=>({id,name:id}))});
const preferences={excludedFamilies:['gpt-5'],modelFamilies:[{family:'glm',allowed:['glm-5.3']}],hiddenProviders:['zai-coding-cn'],preferredProviders:['zai','openai-codex','deepseek']};
test('picker excludes GPT5 and old GLM families while preserving GPT6 and GLM5.3 variants',()=>{
 const input=[provider('openai-codex',['gpt-5','gpt-5.6-sol','gpt-6-sol','gpt-6.1-sol']),provider('zai',['glm-4.7','glm-5','glm-5.2','glm-5.3','glm-5.3-flash','glm-5.3-highspeed','glm-5.31']),provider('deepseek',['deepseek-flash'])];
 assert.deepEqual(filterModelCatalog(input,preferences).flatMap(p=>p.models.map(m=>m.id)),['gpt-6-sol','gpt-6.1-sol','glm-5.3','glm-5.3-flash','glm-5.3-highspeed','deepseek-flash']);
 assert.equal(input[0].models.length,4);
});
test('picker removes duplicates in and across providers, prioritizing configured preferred route',()=>{
 const input=[provider('custom',['GPT-6-sol']),provider('openai-codex',['gpt-6-sol','gpt-6-sol']),provider('zai-coding-cn',['glm-5.3']),provider('zai',['glm-5.3']),provider('unconfigured',['deepseek-flash'],false),provider('deepseek',['deepseek-flash'])];
 const result=filterModelCatalog(input,preferences);assert.deepEqual(result.map(p=>p.id),['openai-codex','zai','deepseek']);assert.equal(result.flatMap(p=>p.models).length,3);
});
test('missing preferences do not remove older valid model families and malformed entries are ignored',()=>{
 assert.deepEqual(filterModelCatalog([null,provider('vendor',['gpt-5','glm-5'])],{}).flatMap(p=>p.models.map(m=>m.id)),['gpt-5','glm-5']);
});

test('exact GLM preference keeps only glm-5.3, excluding flash and highspeed variants',()=>{
 const result=filterModelCatalog([provider('zai',['glm-5.3','glm-5.3-flash','glm-5.3-highspeed'])],{modelFamilies:[{family:'glm',allowed:['glm-5.3'],exact:true}]});
 assert.deepEqual(result[0].models.map(m=>m.id),['glm-5.3']);
});
