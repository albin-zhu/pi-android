import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const source=name=>readFileSync(new URL('../android/src/dev/zfold/assistant/'+name,import.meta.url),'utf8');
test('image viewer has explicit user save action and external option',()=>{const s=source('ChatImages.java');assert.match(s,/save.setOnClickListener/);assert.match(s,/ImageDownloads.save/);assert.match(s,/正在保存/);assert.match(s,/外部打开/);assert.match(s,/save.setEnabled\(false\)/);});
test('save preserves original file or attachment bytes, not sampled preview',()=>{const s=source('ImageDownloads.java');assert.match(s,/new FileInputStream\(file\)/);assert.match(s,/Base64.decode\(encoded/);assert.match(s,/new ByteArrayInputStream\(bytes\)/);assert.doesNotMatch(s,/\.compress\(/);assert.match(s,/MAX_BYTES/);assert.match(s,/outMimeType/);});
test('save uses Pictures MediaStore pending commit and cleans partial failures',()=>{const s=source('ImageDownloads.java');assert.match(s,/DIRECTORY_PICTURES\+"\/橙橙"/);assert.match(s,/IS_PENDING,1/);assert.match(s,/IS_PENDING,0/);assert.match(s,/resolver.delete\(uri/);assert.match(s,/UUID.randomUUID/);assert.doesNotMatch(s,/requestPermissions/);});
