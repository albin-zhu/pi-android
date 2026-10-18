import {test} from 'node:test';
import assert from 'node:assert/strict';
import {classifyUrl,fragmentId,headingId,renderMarkdown} from '../runtime/markdown-preview.mjs';

const count=(html,pattern)=>Array.from(html.matchAll(pattern)).length;

test('GFM tables retain cell boundaries, escaped pipes, inline markup and column alignment',()=>{
  const html=renderMarkdown('| 左列 | 中列 | 右列 |\n| :--- | :---: | ---: |\n| 中文\\|文本 | `a\\|b` | **粗体** 和 *强调* |\n| 长内容'.padEnd(350,'长')+' | 短 | 42 |\n\nAfter table.');
  assert.equal(count(html,/<table>/g),1);
  assert.equal(count(html,/<th\b/g),3);
  assert.equal(count(html,/<td\b/g),6);
  assert.match(html,/<th scope="col" class="align-left">左列<\/th>/);
  assert.match(html,/<th scope="col" class="align-center">中列<\/th>/);
  assert.match(html,/<td class="align-right"><strong>粗体<\/strong> 和 <em>强调<\/em><\/td>/);
  assert.match(html,/<td class="align-left">中文\|文本<\/td>/);
  assert.match(html,/<code>a\|b<\/code>/);
  assert.match(html,/<div class="table-scroll"[^>]*><table>/);
  assert.match(html,/<\/table>\s*<\/div>\s*<p>After table\.<\/p>/);
});

test('GFM rejects mismatched table separators and does not parse fenced tables',()=>{
  assert.doesNotMatch(renderMarkdown('a | b\n--- | --- | ---\nc | d'),/<table>/);
  const html=renderMarkdown('```md\n| a | b |\n| --- | --- |\n| c | d |\n```');
  assert.doesNotMatch(html,/<table>/);
  assert.match(html,/<pre><code class="language-md">/);
  assert.match(html,/\| a \| b \|/);
});

test('headings, blockquotes, lists and fenced code remain semantic Markdown',()=>{
  const html=renderMarkdown('# 标题\n\n> 引用 **粗体**\n\n1. 第一\n2. 第二\n\n- [x] 完成\n\n```js\nconst x = "<script>";\n```');
  assert.match(html,/<h1 id="标题">标题<\/h1>/);
  assert.match(html,/<blockquote>\s*<p>引用 <strong>粗体<\/strong>/);
  assert.match(html,/<ol>\s*<li>第一<\/li>\s*<li>第二<\/li>/);
  assert.match(html,/<input [^>]*checked[^>]*disabled[^>]*type="checkbox">/);
  assert.match(html,/const x = &quot;&lt;script&gt;&quot;;/);
});

test('relative and reference URLs preserve Unicode, spaces, encoded segments and fragments',()=>{
  const html=renderMarkdown('[下一章](<../资料/下一 章.md#中文标题>)\n\n[encoded][chapter]\n\n![示意 **图片**][picture]\n\n[chapter]: ../a%20b/%E4%B8%AD.md#section\n[picture]: <../图片/示意 图.png> "说明"');
  assert.match(html,/href="\.\.\/资料\/下一 章.md#中文标题"/);
  assert.match(html,/href="\.\.\/a%20b\/%E4%B8%AD.md#section"/);
  assert.match(html,/<img src="\.\.\/图片\/示意 图.png" alt="示意 图片" title="说明">/);
  assert.equal(classifyUrl('../a%2520b.md').url,'../a%2520b.md');
  assert.equal(classifyUrl('https://example.com/a?q=1&amp;b=2').url,'https://example.com/a?q=1&b=2');
  assert.match(renderMarkdown('[external](https://example.com/path)'),/href="https:\/\/example.com\/path" rel="noreferrer noopener"/);
});

test('heading IDs are Unicode, normalized, deterministic and collision-free',()=>{
  const source='# 中文标题\n\n# 中文标题\n\n# 中文标题-1\n\n# **Hello** `World`\n\n# !!!';
  const html=renderMarkdown(source);
  assert.match(html,/id="中文标题"/);
  assert.match(html,/id="中文标题-1"/);
  assert.match(html,/id="中文标题-1-1"/);
  assert.match(html,/id="hello-world"/);
  assert.match(html,/id="section"/);
  assert.equal(renderMarkdown(source),html);
  assert.equal(headingId('Cafe\u0301 中文'),'café-中文');
  assert.equal(fragmentId('#%E4%B8%AD%E6%96%87%E6%A0%87%E9%A2%98'),'中文标题');
  assert.equal(fragmentId('#中文标题-1'),'中文标题-1');
  assert.equal(fragmentId('#broken%2'),'broken%2');
  assert.match(renderMarkdown('[跳转](#中文标题)\n\n# 中文标题'),/href="#中文标题"/);
});

test('raw HTML is text, including script, iframe and HTML-block content',()=>{
  const html=renderMarkdown('<script>alert("bad")</script>\n\n<iframe src="https://example.com"></iframe>\n\nText <img src=x onerror=alert(1)> end\n\n<style>body{display:none}</style>');
  assert.doesNotMatch(html,/<(?:script|iframe|style|img)\b/i);
  assert.match(html,/&lt;script&gt;/);
  assert.match(html,/&lt;img src=x onerror=alert\(1\)&gt;/);
  assert.match(html,/&lt;style&gt;/);
});

test('unsafe URL schemes and encoded control characters never produce active links',()=>{
  for(const url of ['javascript:alert(1)','java&#x73;cript:alert(1)','javascript&colon;alert(1)','java&Tab;script:alert(1)','content://provider/private','file:///private/data','intent://open','vbscript:evil','data:text/html;base64,PHNjcmlwdD4=','https:\\evil.test']){
    assert.equal(classifyUrl(url).kind,'blocked',url);
    const html=renderMarkdown(`[blocked](<${url}>)`);
    assert.doesNotMatch(html,/<a\b/,url);
  }
  assert.equal(classifyUrl('../javascript%3Afile.md').kind,'local');
  assert.match(renderMarkdown('[query](https://example.com/?x=&quot;onmouseover=evil)'),/href="https:\/\/example.com\/\?x=&quot;onmouseover=evil"/);
});

test('local and raster data images load but remote and unsafe data images explain refusal',()=>{
  const raster='data:image/png;base64,iVBORw0KGgo=';
  assert.equal(classifyUrl(raster,{image:true}).kind,'data');
  assert.match(renderMarkdown(`![pixel](${raster})`),/<img src="data:image\/png;base64,iVBORw0KGgo=" alt="pixel">/);
  assert.match(renderMarkdown('![local](../img/图.svg)'),/<img src="\.\.\/img\/图.svg" alt="local">/);
  for(const url of ['https://example.com/a.png','//example.com/a.png']){
    const html=renderMarkdown(`![remote image](${url})`);
    assert.doesNotMatch(html,/<img\b/);
    assert.match(html,/remote image/);
  }
  for(const url of ['data:image/svg+xml;base64,PHN2Zz4=','data:text/html;base64,PHNjcmlwdD4=','file:///private/picture.png'])assert.equal(classifyUrl(url,{image:true}).kind,'blocked');
});

test('single-document grants explain folder authorization without breaking fragment or external links',()=>{
  const html=renderMarkdown('[sibling](../other.md) [here](#section) [web](https://example.com) ![missing picture](../image.png)',{resourceAccess:false});
  assert.match(html,/class="access-notice"/);
  assert.match(html,/href="\.\.\/other.md"/);
  assert.match(html,/href="#section"/);
  assert.match(html,/href="https:\/\/example.com"/);
  assert.doesNotMatch(html,/<img\b/);
});
