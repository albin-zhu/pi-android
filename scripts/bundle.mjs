import {build} from 'esbuild';
import fs from 'node:fs';
import path from 'node:path';
const out='android/assets/runtime';
fs.mkdirSync(out,{recursive:true});
await build({entryPoints:['runtime/agent.mjs'],outfile:`${out}/agent.cjs`,bundle:true,platform:'node',format:'cjs',target:'node24',
  plugins:[{name:'runtime-defaults',setup(builder){builder.onResolve({filter:/^\.\/defaults\.local\.mjs$/},()=>({
    path:path.resolve(process.env.ZFOLD_DEFAULTS_FILE||'runtime/defaults.example.mjs')
  }));}}],
  external:['@mariozechner/clipboard'],define:{'import.meta.url':'__zfoldModuleUrl'},
  banner:{js:'const __zfoldModuleUrl = require("node:url").pathToFileURL(__filename).href;'},
  logLevel:'info'});
fs.copyFileSync('node_modules/@earendil-works/pi-coding-agent/package.json',`${out}/package.json`);
fs.mkdirSync(`${out}/subagent-roles`,{recursive:true});
for(const role of ['scout','planner','reviewer','worker'])fs.copyFileSync(`runtime/vendor/pi-subagent-v1.1.0/agents/${role}.md`,`${out}/subagent-roles/${role}.md`);
fs.copyFileSync('runtime/skills/phone-control/SKILL.md',`${out}/phone-control.SKILL.md`);
fs.copyFileSync('runtime/skills/skill-creator/SKILL.md',`${out}/skill-creator.SKILL.md`);
const licenses='android/assets/licenses';fs.mkdirSync(licenses,{recursive:true});
for(const name of fs.readdirSync(licenses))if(name.startsWith('node_modules_'))fs.rmSync(`${licenses}/${name}`,{force:true});
const lock=JSON.parse(fs.readFileSync('package-lock.json','utf8'));
for(const directory of Object.keys(lock.packages)){
  if(!directory||!fs.existsSync(directory))continue;
  const license=fs.readdirSync(directory).find(name=>/^licen[sc]e(?:[.-].*)?$/i.test(name));
  if(license&&fs.statSync(`${directory}/${license}`).isFile())fs.copyFileSync(`${directory}/${license}`,`${licenses}/${directory.replaceAll('/','_')}.txt`);
}
for(const name of fs.readdirSync('third_party'))if(fs.statSync(`third_party/${name}`).isFile())fs.copyFileSync(`third_party/${name}`,`${licenses}/${name}`);
