'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const official={visible:true,loginType:'chatgpt',accountKey:'a'.repeat(64),limits:[],resetCredits:null,canReset:false,updatedAt:1900000000};

async function fixture(read){
  const nodes=new Map(),timers=new Map(),requests=[];let timerId=0;
  const node=(tag='div')=>({tagName:tag.toUpperCase(),value:'',checked:false,disabled:false,hidden:false,open:false,textContent:'',dataset:{},options:[],children:[],
    classList:{add(){},remove(){},toggle(){}},addEventListener(){},setAttribute(){},
    replaceChildren(...children){this.children=children;},append(...children){this.children.push(...children);},querySelectorAll(){return [];},querySelector(){return null;},
    showModal(){this.open=true;},close(){this.open=false;}});
  const html=fs.readFileSync(path.join(__dirname,'../web/index.html'),'utf8');
  for(const match of html.matchAll(/<([a-z]+)\b([^>]*\bid="([^"]+)"[^>]*)>/g)){
    const n=node(match[1]);n.hidden=/\bhidden\b/.test(match[2]);nodes.set(match[3],n);
  }
  const storage=()=>({getItem(){return null;},setItem(){},clear(){}});
  const response=(data,status=200)=>({ok:status===200,status,json:async()=>data});
  const context=vm.createContext({Date,document:{addEventListener(){},documentElement:{},getElementById:id=>nodes.get(id),querySelectorAll:()=>[],createElement:node},
    window:{addEventListener(){}},localStorage:storage(),sessionStorage:storage(),
    location:{hash:'',pathname:'/',search:''},history:{replaceState(){}},
    setTimeout(fn,delay){timers.set(++timerId,{fn,delay});return timerId;},clearTimeout(id){timers.delete(id);},setInterval(){},
    fetch:async(url,options={})=>{
      requests.push(url);
      if(url==='/api/auth')return response({authenticated:true,csrf:'fixture'});
      if(url.startsWith('/api/sessions?'))return response({sessions:[]});
      if(url==='/api/account')return read(response);
      throw Error('Unexpected request '+url);
    }});
  for(const file of ['web/i18n.js','web/account.js','web/modes.js','web/attachments.js','web/activity.js','web/fast-mode.js','web/app.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,'..',file),'utf8'),context);
  await new Promise(setImmediate);
  return {nodes,requests,timers,run:code=>vm.runInContext(code,context),
    async retry(){const entry=[...timers.entries()].find(([,t])=>t.delay<=5000);assert.ok(entry,'Account read should retry promptly without user input');timers.delete(entry[0]);await entry[1].fn();await new Promise(setImmediate);}};
}

test('direct entry shows loading state and automatically reveals official account after a delayed read',async()=>{
  let finish;
  const ui=await fixture(response=>new Promise(resolve=>{finish=()=>resolve(response(official));}));
  assert.equal(ui.nodes.get('app').hidden,false);
  assert.equal(ui.nodes.get('account-status')?.hidden,false);
  assert.match(ui.nodes.get('account-status').textContent,/正在读取/);
  finish();await new Promise(setImmediate);
  assert.equal(ui.nodes.get('account-button').hidden,false);
  assert.equal(ui.nodes.get('account-status').hidden,true);
  assert.equal(ui.requests.filter(url=>url==='/api/account').length,1);
});

test('a transient first read failure recovers automatically without clicking refresh',async()=>{
  let calls=0;
  const ui=await fixture(response=>++calls===1?response({error:'暂时无法读取'},409):response(official));
  assert.equal(ui.nodes.get('account-button').hidden,true);
  await ui.retry();
  assert.equal(ui.nodes.get('account-button').hidden,false);assert.equal(calls,2);
  assert.equal(ui.requests.filter(url=>url.startsWith('/api/sessions?')).length,1);
});

test('API mode displays non-clickable login status and never exposes the quota dialog',async()=>{
  const ui=await fixture(response=>response({visible:false,loginType:'api'}));
  assert.equal(ui.nodes.get('account-button').hidden,true);
  const status=ui.nodes.get('account-status');assert.equal(status?.hidden,false);
  assert.equal(status.tagName,'SPAN');assert.equal(status.onclick,undefined);
  assert.match(status.textContent,/API/);assert.equal(ui.nodes.get('account-dialog').open,false);
  ui.nodes.get('phone-language').value='en';ui.nodes.get('phone-language').onchange();
  assert.equal(status.textContent,'API connection');
});

test('persistent failure remains visible, retries are bounded, and logout cancels retry',async()=>{
  const ui=await fixture(response=>response({error:'offline'},409));
  await ui.retry();await ui.retry();
  assert.equal(ui.timers.size,0);assert.match(ui.nodes.get('account-status').textContent,/暂不可用/);
  const again=await fixture(response=>response({error:'offline'},409));
  again.run('showLogin()');
  assert.equal(again.timers.size,0);assert.equal(again.nodes.get('account-status').hidden,true);
});
