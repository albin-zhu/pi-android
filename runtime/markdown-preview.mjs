import {Marked,Renderer} from 'marked';

const escapeHtml=value=>String(value).replace(/[&<>"']/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
// Decode URL-significant references before classification; all output is escaped again.
// Unknown named references remain literal, never a second HTML decoding opportunity.
function decodeReferences(value){
  const named={amp:'&',lt:'<',gt:'>',quot:'"',apos:"'",colon:':',Tab:'\t',NewLine:'\n'};
  return String(value).replace(/&(#(?:x[0-9a-f]+|[0-9]+)|[a-z]+);/gi,(whole,name)=>{
    if(name[0]!=='#')return named[name]??whole;
    const point=name[1].toLowerCase()==='x'?parseInt(name.slice(2),16):Number(name.slice(1));
    return point>0&&point<=0x10ffff&&!(point>=0xd800&&point<=0xdfff)?String.fromCodePoint(point):'\ufffd';
  });
}

export function classifyUrl(value,{image=false}={}){
  const url=decodeReferences(value).trim();
  const blocked=reason=>({kind:'blocked',url:null,reason});
  if(/[\u0000-\u001f\u007f\\]/.test(url))return blocked('已拦截不安全的地址。');
  if(/^data:/i.test(url)){
    if(image&&/^data:image\/(?:png|jpeg|gif|webp|avif|bmp|x-icon);base64,[a-z0-9+/]*={0,2}$/i.test(url))return {kind:'data',url};
    return blocked('内嵌图片仅支持栅格图片数据。');
  }
  const scheme=/^([a-z][a-z0-9+.-]*):/i.exec(url);
  if(scheme&&!/^https?$/i.test(scheme[1]))return blocked('不支持此链接协议。');
  try{
    const parsed=new URL(url,'https://markdown-preview.invalid/files/document.md');
    if(!['http:','https:'].includes(parsed.protocol))return blocked('不支持此链接协议。');
    if(scheme||url.startsWith('//'))return image?blocked('离线预览不自动加载网络图片。'):{kind:'remote',url};
    return {kind:'local',url};
  }catch{
    return blocked('链接地址无效。');
  }
}

function inlineText(tokens){
  return tokens.map(token=>token.tokens?inlineText(token.tokens):token.type==='br'?' ':token.text??'').join('');
}

export function headingId(text,used=new Set()){
  const base=decodeReferences(text).normalize('NFC').toLowerCase()
    .replace(/[^\p{L}\p{N}\p{M}\s_-]/gu,'').trim().replace(/\s+/g,'-')||'section';
  let id=base,suffix=1;
  while(used.has(id))id=`${base}-${suffix++}`;
  used.add(id);
  return id;
}

export function fragmentId(hash){
  const fragment=String(hash).replace(/^#/,'');
  try{return decodeURIComponent(fragment);}catch{return fragment;}
}

const accessMessage='相对链接和图片需要文件夹读取权限。请点击上方「授权文件夹」，选择包含本文档及关联文件的文件夹。';
const imageMessage=(alt,reason)=>`${alt?`图片「${alt}」`:'图片'}：${reason}`;
function imagePlaceholder(alt,reason){
  return `<span class="image-error" role="note">${escapeHtml(imageMessage(alt,reason))}</span>`;
}

export function renderMarkdown(markdown,{resourceAccess=true}={}){
  const used=new Set();
  const renderer=new Renderer();
  renderer.html=({text})=>escapeHtml(text);
  // Marked deliberately passes text inside raw HTML blocks through unescaped by
  // default. Escape it here as well: raw HTML is text, not an alternate renderer.
  renderer.text=function(token){
    return token.escaped?escapeHtml(token.text):Renderer.prototype.text.call(this,token);
  };
  renderer.heading=function({tokens,depth}){
    return `<h${depth} id="${escapeHtml(headingId(inlineText(tokens),used))}">${this.parser.parseInline(tokens)}</h${depth}>\n`;
  };
  renderer.table=function(token){
    return `<div class="table-scroll" role="region" aria-label="表格，可横向滚动查看其他列" tabindex="0">${Renderer.prototype.table.call(this,token)}</div>\n`;
  };
  renderer.tablecell=function({tokens,header,align}){
    const tag=header?'th':'td';
    const alignment=['left','center','right'].includes(align)?` class="align-${align}"`:'';
    return `<${tag}${header?' scope="col"':''}${alignment}>${this.parser.parseInline(tokens)}</${tag}>\n`;
  };
  renderer.link=function({href,title,tokens}){
    const target=classifyUrl(href),label=this.parser.parseInline(tokens);
    if(target.kind==='blocked')return `<span class="blocked-link" title="${escapeHtml(target.reason)}">${label} <small>（链接已拦截）</small></span>`;
    return `<a href="${escapeHtml(target.url)}"${title?` title="${escapeHtml(decodeReferences(title))}"`:''}${target.kind==='remote'?' rel="noreferrer noopener"':''}>${label}</a>`;
  };
  renderer.image=function({href,title,text,tokens}){
    const alt=decodeReferences(tokens?inlineText(tokens):text),target=classifyUrl(href,{image:true});
    if(target.kind==='blocked')return imagePlaceholder(alt,target.reason);
    if(target.kind==='local'&&!resourceAccess)return imagePlaceholder(alt,accessMessage);
    return `<span class="image-frame"><img src="${escapeHtml(target.url)}" alt="${escapeHtml(alt)}"${title?` title="${escapeHtml(decodeReferences(title))}"`:''}></span>`;
  };
  const parser=new Marked({gfm:true,breaks:false,async:false,renderer});
  return (!resourceAccess?`<aside class="access-notice" role="note">${escapeHtml(accessMessage)}</aside>\n`:'')+parser.parse(markdown);
}

async function startPreview(){
  const content=document.getElementById('markdown-content');
  let interacted=false;
  for(const event of ['pointerdown','touchstart','wheel','keydown'])window.addEventListener(event,()=>{interacted=true;},{once:true,passive:true});
  const scrollToFragment=()=>{
    const id=fragmentId(window.location.hash);
    if(id)document.getElementById(id)?.scrollIntoView();
  };
  window.addEventListener('hashchange',scrollToFragment);
  try{
    const response=await fetch('/__preview__/source',{credentials:'omit',cache:'no-store'});
    if(!response.ok)throw new Error('无法读取文档内容。');
    const source=await response.json();
    document.documentElement.dataset.theme=source.dark?'dark':'light';
    content.innerHTML=renderMarkdown(source.markdown,{resourceAccess:source.resourceAccess===true});
    const images=Array.from(content.querySelectorAll('img'),image=>new Promise(resolve=>{
      const settled=()=>resolve();
      const failed=()=>{
        const message=document.createElement('span');
        message.className='image-error';message.setAttribute('role','note');
        message.textContent=imageMessage(image.alt,'无法读取本地图片，请检查文件路径和文件夹授权。');
        image.replaceWith(message);settled();
      };
      image.addEventListener('error',failed,{once:true});
      image.addEventListener('load',settled,{once:true});
      if(image.complete){if(image.naturalWidth)settled();else failed();}
    }));
    scrollToFragment();
    await Promise.all(images);
    if(!interacted)scrollToFragment();
  }catch(error){
    content.textContent=`Markdown 渲染失败：${error.message}`;
    content.classList.add('preview-error');
  }finally{
    document.documentElement.dataset.previewReady='true';
    window.__markdownReady=true;
  }
}

if(typeof document!=='undefined'&&document.getElementById('markdown-content'))void startPreview();
