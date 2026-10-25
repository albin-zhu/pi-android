import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
import {execFileSync} from 'node:child_process';
const modules=path.resolve(process.env.ZFOLD_NODE_MODULES||'node_modules');
const {build}=createRequire(path.join(modules,'../package.json'))('esbuild');
const project=JSON.parse(fs.readFileSync('package.json','utf8'));
const piPackages={};
for(const name of ['@earendil-works/pi-ai','@earendil-works/pi-coding-agent']){
  const file=fs.realpathSync(path.join(modules,name,'package.json'));
  const installed=JSON.parse(fs.readFileSync(file,'utf8')),expected=project.dependencies[name];
  if(installed.version!==expected)throw new Error(`${name}: installed ${installed.version}, required ${expected}; restore the locked dependencies before building`);
  piPackages[name]={file,version:installed.version};
}
const agentAiFile=path.join(path.dirname(piPackages['@earendil-works/pi-coding-agent'].file),'node_modules/@earendil-works/pi-ai/package.json');
if(fs.existsSync(agentAiFile)&&JSON.parse(fs.readFileSync(agentAiFile,'utf8')).version!==project.dependencies['@earendil-works/pi-ai'])throw new Error('Pi agent resolves a different pi-ai version; restore the locked dependencies before building');
const out='android/assets/runtime';
fs.mkdirSync(out,{recursive:true});
await build({nodePaths:[modules],entryPoints:['runtime/agent.mjs'],outfile:`${out}/agent.cjs`,bundle:true,platform:'node',format:'cjs',target:'node24',
  plugins:[{name:'runtime-defaults',setup(builder){builder.onResolve({filter:/^\.\/defaults\.local\.mjs$/},()=>({
    path:path.resolve(process.env.ZFOLD_DEFAULTS_FILE||'runtime/defaults.example.mjs')
  }));}},{name:'shared-dependencies',setup(builder){
    builder.onResolve({filter:/^\.\.\/node_modules\//},args=>({path:path.join(modules,args.path.slice('../node_modules/'.length))}));
  }}],
  external:['@mariozechner/clipboard'],define:{'import.meta.url':'__zfoldModuleUrl','__ZFOLD_PI_VERSION__':JSON.stringify(piPackages['@earendil-works/pi-coding-agent'].version)},
  banner:{js:'const __zfoldModuleUrl = require("node:url").pathToFileURL(__filename).href;'},
  logLevel:'info'});
fs.copyFileSync(piPackages['@earendil-works/pi-coding-agent'].file,`${out}/package.json`);
fs.mkdirSync(`${out}/subagent-roles`,{recursive:true});
for(const role of ['scout','planner','reviewer','worker'])fs.copyFileSync(`runtime/vendor/pi-subagent-v1.1.0/agents/${role}.md`,`${out}/subagent-roles/${role}.md`);
fs.copyFileSync('runtime/skills/phone-control/SKILL.md',`${out}/phone-control.SKILL.md`);
fs.copyFileSync('runtime/skills/skill-creator/SKILL.md',`${out}/skill-creator.SKILL.md`);
// Only this dependency-free directory is exposed inside the Ubuntu guest.
const phoneOut=`${out}/phone-tools`;
fs.mkdirSync(phoneOut,{recursive:true});
for(const name of ['phone-cli','phone-open','phone-tools','phone-socket-client','phone-frames','phone-paths','phone-tool-definitions','phone-turn-guard','native-tools'])fs.copyFileSync(`runtime/${name}.mjs`,`${phoneOut}/${name}.mjs`);
fs.copyFileSync('runtime/windows.py',`${phoneOut}/windows.py`);
fs.copyFileSync('runtime/gamepad_wine.py',`${phoneOut}/gamepad_wine.py`);
fs.copyFileSync('runtime/gamepad.sh',`${phoneOut}/gamepad.sh`);
// A standalone bundle must never ship stale native guest adapters. The helper
// reuses only source/compiler/output-hash-verified products from canonical builds.
execFileSync(process.env.PYTHON||'python3',['scripts/input_controls.py'],{stdio:'inherit'});
fs.rmSync(`${phoneOut}/input-controls`,{recursive:true,force:true});
fs.cpSync('.build/input-controls/package',`${phoneOut}/input-controls`,{recursive:true});
fs.copyFileSync('runtime/x11-mirror.py',`${phoneOut}/x11-mirror.py`);
fs.mkdirSync(`${phoneOut}/bin`,{recursive:true});
fs.copyFileSync('runtime/windows.sh',`${phoneOut}/bin/windows`);
await build({nodePaths:[modules],entryPoints:['runtime/omp-phone-extension.mjs'],outfile:`${phoneOut}/omp-phone-extension.mjs`,
  bundle:true,platform:'node',format:'esm',target:'node24',external:['@oh-my-pi/*'],logLevel:'info'});
fs.copyFileSync('runtime/ubuntu.sh',`${out}/ubuntu.sh`);
const previewOut='android/assets/preview';
fs.mkdirSync(previewOut,{recursive:true});
await build({nodePaths:[modules],entryPoints:['runtime/markdown-preview.mjs'],outfile:`${previewOut}/markdown.js`,
  bundle:true,platform:'browser',format:'iife',target:'chrome80',logLevel:'info'});
for(const extension of ['html','css'])fs.copyFileSync(`runtime/markdown-preview.${extension}`,`${previewOut}/markdown.${extension}`);
const licenses='android/assets/licenses';fs.mkdirSync(licenses,{recursive:true});
fs.copyFileSync('LICENSE',`${licenses}/Project-GPL-3.0.txt`);
fs.copyFileSync('THIRD_PARTY_NOTICES.md',`${licenses}/THIRD_PARTY_NOTICES.md`);
for(const name of fs.readdirSync(licenses))if(name.startsWith('node_modules_'))fs.rmSync(`${licenses}/${name}`,{force:true});
const lock=JSON.parse(fs.readFileSync('package-lock.json','utf8'));
for(const directory of Object.keys(lock.packages)){
  if(!directory)continue;
  const installed=path.join(modules,directory.slice('node_modules/'.length));
  if(!fs.existsSync(installed))continue;
  const license=fs.readdirSync(installed).find(name=>/^licen[sc]e(?:[.-].*)?$/i.test(name));
  if(license&&fs.statSync(path.join(installed,license)).isFile())fs.copyFileSync(path.join(installed,license),`${licenses}/${directory.replaceAll('/','_')}.txt`);
}
for(const name of fs.readdirSync('third_party'))if(fs.statSync(`third_party/${name}`).isFile())fs.copyFileSync(`third_party/${name}`,`${licenses}/${name}`);
