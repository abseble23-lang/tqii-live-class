const {JSDOM,VirtualConsole}=require('jsdom');const fs=require('fs');
const html=fs.readFileSync(process.argv[2],'utf8'); const man=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));
const vc=new VirtualConsole();vc.on('jsdomError',e=>console.error('JSERR',e.message,String(e.detail&&e.detail.stack||'').slice(0,300)));
const dom=new JSDOM(html,{runScripts:'dangerously',url:'https://x.test/index.html',virtualConsole:vc});
const w=dom.window; w.scrollTo=()=>{};
let hit={bn:0,en:0},miss={bn:0,en:0},ex=[];
// intercept: call the page's own speak pipeline pieces
const vl=html.split('\n').find(x=>x.startsWith('const vkey='));const vkey=eval(vl.slice(11,-1)); 
function chk(text,lang){ for(const p of w.clean(text,lang).split(/(?<=[।.?!])\s+/)){ const k=vkey(p); if(!k.replace(/[\s,.;:]/g,'').length) continue; if(man[lang][k]) hit[lang]++; else {miss[lang]++; if(ex.length<5) ex.push(lang+': '+k);} } }
const sc=html.slice(html.indexOf('<script>')+8);const LL=JSON.parse(sc.slice(sc.indexOf('const L = ')+10, sc.indexOf('];')+1));
for(const l of LL){ w.lesson(l.id); const app=w.document.getElementById('app');
  for(const el of app.querySelectorAll('[data-say], .qa .bnq, .qa .enq, .tip')) for(const lang of ['bn','en']) chk(w.textOf(el,lang),lang); }
console.log('hit',hit,'miss',miss); console.log(ex.join('\n'));
