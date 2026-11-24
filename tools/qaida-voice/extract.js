const {JSDOM}=require('jsdom');const fs=require('fs');
const html=fs.readFileSync(process.argv[2],'utf8');
const {VirtualConsole}=require('jsdom');const vc=new VirtualConsole();vc.on('jsdomError',e=>console.error('JSERR',e.message));
const dom=new JSDOM(html,{virtualConsole:vc,runScripts:'dangerously',url:'https://x.test/index.html',pretendToBeVisual:true});
const w=dom.window;
const norm=s=>s.replace(/^[\s,.;:।?!—–-]+|[\s,.;:।?!—–-]+$/g,'').replace(/\s+/g,' ');
const out={bn:new Map(),en:new Map()};
function add(text,lang){ const t=w.clean(text,lang); for(const s of t.split(/(?<=[।.?!])\s+/)){ const k=norm(s); if(k.replace(/[\s,.;:]/g,'').length) { if(!out[lang].has(k)) out[lang].set(k,s.trim()); } } }
add('আসসালামু আলাইকুম। এটি তালিমুল কোরআন কায়দা।','bn'); add('Assalamu alaikum. This is the TQII Qaida.','en');
const sc=html.slice(html.indexOf('<script>')+8);const LL=JSON.parse(sc.slice(sc.indexOf('const L = ')+10, sc.indexOf('];')+1));
for(const l of LL){
  w.lesson(l.id);
  const app=w.document.getElementById('app');
  const els=[...app.querySelectorAll('[data-say], .qa .bnq, .qa .enq, .tip')];
  for(const el of els) for(const lang of ['bn','en']) add(w.textOf(el,lang),lang);
  const root=app.querySelector('.paper');
  if(root) for(const lang of ['bn','en']){ const bits=[root.querySelector('.cart'), ...root.querySelectorAll(lang==='en'?'.qa .enq':'.qa .bnq'), ...root.querySelectorAll('.tip')].filter(Boolean); add(bits.map(b=>w.textOf(b,lang)).join('. '),lang); }
}
const res={bn:[...out.bn.entries()],en:[...out.en.entries()]};
fs.writeFileSync('sentences.json',JSON.stringify(res,null,0));
for(const k of ['bn','en']) console.log(k,res[k].length,'sentences',res[k].reduce((a,[x])=>a+x.length,0),'chars');
