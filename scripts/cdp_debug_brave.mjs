#!/usr/bin/env node
const P=9222,H='127.0.0.1',BASE='https://store.weixin.qq.com/talent/pool/home?from=platform&keyword='
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
async function fj(u){const r=await fetch(u);return r.json()}
class C{constructor(w){this.w=w;this.i=0;this.p=new Map();w.onmessage=e=>{const m=JSON.parse(e.data);if(m.id&&this.p.has(m.id)){const{resolve:rs,reject:rj}=this.p.get(m.id);this.p.delete(m.id);m.error?rj(new Error(m.error.message)):rs(m.result)}}}
static async c(){const ts=await fj(`http://${H}:${P}/json`);let t=ts.find(x=>x.type==='page'&&x.url.includes('store.weixin'))||ts.find(x=>x.type==='page');if(!t)throw new Error('no target');console.log('[CDP]',t.id,t.url.slice(0,80));const w=new WebSocket(t.webSocketDebuggerUrl);await new Promise((r,j)=>{w.onopen=r;w.onerror=j});return new C(w)}
async s(m,p={}){const id=++this.i;return new Promise((r,j)=>{this.p.set(id,{resolve:r,reject:j});this.w.send(JSON.stringify({id,method:m,params:p}))})}
async ev(e){const r=await this.s('Runtime.evaluate',{expression:e,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw new Error(r.exceptionDetails.text);return r.result.value}
close(){this.w.close()}}

async function main(){
  const kw=process.argv[2]||'零食',url=BASE+encodeURIComponent(kw)
  console.log('[Main]',url)
  const c=await C.c()
  await c.s('Page.enable');await c.s('Runtime.enable')
  console.log('[Nav] navigating...')
  await c.s('Page.navigate',{url})
  console.log('[Wait] polling 40s...')
  for(let i=0;i<40;i++){
    const r=await c.ev(`(function(){
      var fr=document.querySelector('.filters-row'),tags=document.querySelectorAll('.tag'),dd=document.querySelectorAll('.weui-desktop-form__dropdown')
      var ifr=document.querySelectorAll('iframe')
      return JSON.stringify({rs:document.readyState,fr:!!fr,tc:tags.length,dc:dd.length,ic:ifr.length,bl:(document.body.innerText||'').length,url:location.href})
    })()`)
    const o=JSON.parse(r)
    console.log(`  [${i}s] fr=${o.fr} tags=${o.tc} dd=${o.dc} ifr=${o.ic} bl=${o.bl} url=${o.url.slice(0,70)}`)
    if(o.tc>0||o.dc>0||o.fr){console.log('[OK] content loaded!');break}
    if(o.ic>0&&i===12){console.log('[Info] iframes found, checking...');const ii=await c.ev(`(function(){var r=[];document.querySelectorAll('iframe').forEach(function(f,i){r.push({i:i,src:(f.src||'').slice(0,120),id:f.id||'',cls:f.className||''})});return JSON.stringify(r,null,1)})()`);console.log('  iframes:',ii)}
    await sleep(1000)
  }
  // body HTML
  console.log('\n=== Body HTML (3000) ===')
  console.log(await c.ev('document.body?document.body.innerHTML.slice(0,3000):"none"'))
  // login check
  console.log('\n=== Login Check ===')
  console.log(await c.ev(`(function(){var b=document.body.innerText||'';return JSON.stringify({login:b.indexOf('登录')>=0,qr:b.indexOf('扫码')>=0,preview:b.slice(0,300)})})()`))
  // full dump if content found
  const has=await c.ev('document.querySelectorAll(".tag, .weui-desktop-form__dropdown, .filters-row").length')
  if(has>0){
    console.log('\n=== Tags ===')
    console.log(await c.ev(`(function(){var r=[];document.querySelectorAll('.tag').forEach(function(t){r.push({t:t.innerText.trim(),a:t.classList.contains('actived')})});return JSON.stringify(r,null,1)})()`))
    console.log('\n=== Dropdowns ===')
    console.log(await c.ev(`(function(){var r=[];document.querySelectorAll('.weui-desktop-form__dropdown').forEach(function(d,i){var l=d.querySelector('.prepend-in'),v=d.querySelector('.weui-desktop-form__dropdown__value');r.push({i:i,l:l?l.innerText.trim():''  ,v:v?v.innerText.trim():''  ,m:d.classList.contains('weui-desktop-form__dropdown__multiple'),c:!!d.closest('.composition-input')})});return JSON.stringify(r,null,1)})()`))
    console.log('\n=== Contact Btns ===')
    console.log(await c.ev(`(function(){var r=0;document.querySelectorAll('button,a,[role=button]').forEach(function(e){if((e.innerText||'').indexOf('联系')>=0&&e.offsetParent)r++});return String(r)})()`))
  }
  c.close()
  console.log('\n[Done]')
}
main().catch(e=>console.error('[ERR]',e.message))
