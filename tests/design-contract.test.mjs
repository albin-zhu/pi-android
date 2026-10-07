import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const source=fs.readFileSync('android/src/dev/zfold/assistant/Ui.java','utf8');
function color(token,dark){const m=source.match(new RegExp(token+'=dark\\?0x([0-9a-f]+):0x([0-9a-f]+)'));assert.ok(m,token);return parseInt(m[dark?1:2],16)&0xffffff;}
function luminance(rgb){return [16,8,0].map(shift=>((rgb>>shift)&255)/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4).reduce((n,v,i)=>n+v*[.2126,.7152,.0722][i],0);}
function contrast(a,b){const x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
for(const dark of [false,true])test(`${dark?'dark':'light'} primary, secondary and action text meet contrast baseline`,()=>{
 for(const [ink,bg] of [['ink','page'],['ink','surface'],['muted','surface'],['muted','field'],['onAccent','accent'],['accent','accentSoft']])assert.ok(contrast(color(ink,dark),color(bg,dark))>=4.5,`${ink}/${bg}: ${contrast(color(ink,dark),color(bg,dark))}`);
});
test('Markdown body uses passed theme color, native dialogs share brand palette',()=>{
 assert.match(source,/richText\(parent.getContext\(\),value,color\)/);
 assert.match(source,/TextView view=text\(c,"",16,color\)/);
 for(const dir of ['values','values-night'])assert.doesNotMatch(fs.readFileSync(`android/res/${dir}/styles.xml`,'utf8'),/#2F6FED|#65A2FF/);
});
