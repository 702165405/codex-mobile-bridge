'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');

// Run the actual phone handlers with an isolated DOM and notification API.
async function fixture(){
  const nodes=new Map(),saved=new Map(),writes=[];
  const node=()=>({value:'',checked:false,disabled:false,hidden:false,open:false,textContent:'',dataset:{},options:[],
    classList:{add(){},remove(){},toggle(){}},addEventListener(){},replaceChildren(){},append(){},querySelectorAll(){return [];},querySelector(){return null;},
    showModal(){this.open=true;},close(){this.open=false;}});
  const html=fs.readFileSync(path.join(__dirname,'../web/index.html'),'utf8');
  for(const match of html.matchAll(/\bid="([^"]+)"/g))nodes.set(match[1],node());
  const storage=()=>({getItem(){return null;},setItem(){}});
  let nextPost;
  const response=(data,status=200)=>({ok:status===200,status,json:async()=>data});
  const context=vm.createContext({document:{documentElement:{},getElementById:id=>nodes.get(id),querySelectorAll:()=>[],createElement:node},
    window:{addEventListener(){}},localStorage:storage(),sessionStorage:storage(),
    location:{hash:'',pathname:'/',search:''},history:{replaceState(){}},setTimeout(){},clearTimeout(){},
    ChatTimeline:class{constructor(){this.abort=new AbortController();}async start(){}dispose(){this.abort.abort();}relabel(){}},
    fetch:async(url,options={})=>{
      if(url==='/api/auth')return response({authenticated:false});
      if(url.includes('/reconnect'))return response({ok:true});
      assert.match(url,/\/notifications\?/);
      if(options.method==='POST'){
        const body=JSON.parse(options.body);writes.push({url,body});
        if(nextPost){const handle=nextPost;nextPost=null;return handle(body);}
        saved.set(url,{available:true,watching:body.enabled,notifyOnCompletion:body.notifyOnCompletion});
      }
      return response(saved.get(url)||{available:true,watching:false,notifyOnCompletion:false});
    }});
  for(const file of ['web/i18n.js','web/app.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,'..',file),'utf8'),context);
  await new Promise(setImmediate);
  const run=code=>vm.runInContext(code,context);
  return {nodes,writes,saved,run,html,response,setPost:handle=>{nextPost=handle;},
    async open(id='11111111-1111-4111-8111-111111111111',host='local'){
      await run(`openChat(${JSON.stringify(id)},${JSON.stringify(host)})`);await new Promise(setImmediate);
    },
    save:()=>nodes.get('notify-form').onsubmit({preventDefault(){}}),
    settings:()=>nodes.get('notify-button').onclick()};
}

test('chat can opt into completion and retain its choice after reopening',async()=>{
  const ui=await fixture();await ui.open();ui.settings();
  assert.equal(ui.nodes.get('notify-completion').checked,false);
  assert.equal(ui.nodes.get('notify-completion').disabled,true);
  ui.nodes.get('notify-enabled').checked=true;ui.nodes.get('notify-enabled').onchange();
  assert.equal(ui.nodes.get('notify-completion').disabled,false);
  ui.nodes.get('notify-completion').checked=true;await ui.save();
  assert.deepEqual(ui.writes[0].body,{enabled:true,notifyOnCompletion:true});
  assert.equal(ui.nodes.get('notify-dialog').open,false);
  assert.equal(ui.nodes.get('notify-button').textContent,'提醒已开');
  await ui.open();ui.settings();assert.equal(ui.nodes.get('notify-completion').checked,true);
  ui.nodes.get('notify-completion').checked=false;await ui.save();
  assert.deepEqual(ui.writes[1].body,{enabled:true,notifyOnCompletion:false});
  ui.settings();ui.nodes.get('notify-enabled').checked=false;ui.nodes.get('notify-enabled').onchange();await ui.save();
  assert.deepEqual(ui.writes[2].body,{enabled:false,notifyOnCompletion:false});
});

test('completion choices are isolated by chat and host; cancelling sends nothing',async()=>{
  const ui=await fixture();await ui.open();ui.settings();
  ui.nodes.get('notify-enabled').checked=true;ui.nodes.get('notify-completion').checked=true;await ui.save();
  await ui.open(undefined,'remote:test');ui.settings();
  assert.equal(ui.nodes.get('notify-completion').checked,false);
  ui.nodes.get('notify-enabled').checked=true;await ui.save();
  assert.match(ui.writes[1].url,/host=remote%3Atest/);
  assert.equal(ui.writes[1].body.notifyOnCompletion,false);
  await ui.open('22222222-2222-4222-8222-222222222222');ui.settings();
  assert.equal(ui.nodes.get('notify-enabled').checked,false);
  ui.nodes.get('notify-enabled').checked=true;ui.nodes.get('notify-completion').checked=true;ui.nodes.get('notify-dialog').close();
  ui.settings();assert.equal(ui.nodes.get('notify-enabled').checked,false);assert.equal(ui.writes.length,2);
});

test('failed saves keep the dialog and draft choice for retry',async()=>{
  const ui=await fixture();await ui.open();ui.settings();
  ui.nodes.get('notify-enabled').checked=true;ui.nodes.get('notify-completion').checked=true;
  ui.setPost(()=>ui.response({error:'Cannot save'},409));await ui.save();
  assert.equal(ui.nodes.get('notify-dialog').open,true);
  assert.equal(ui.nodes.get('notify-error').textContent,'Cannot save');
  assert.equal(ui.nodes.get('notify-save').disabled,false);
  assert.equal(ui.nodes.get('notify-completion').checked,true);
  assert.equal(ui.nodes.get('notify-button').textContent,'提醒');
  await ui.save();assert.equal(ui.nodes.get('notify-dialog').open,false);
});

test('late save responses cannot change or close another chat notification dialog',async()=>{
  const ui=await fixture();await ui.open();ui.settings();
  ui.nodes.get('notify-enabled').checked=true;ui.nodes.get('notify-completion').checked=true;
  let finish;ui.setPost(()=>new Promise(resolve=>{finish=resolve;}));const saving=ui.save();
  await ui.open('22222222-2222-4222-8222-222222222222','remote:test');
  assert.equal(ui.nodes.get('notify-dialog').open,false);ui.settings();
  finish(ui.response({available:true,watching:true,notifyOnCompletion:true}));await saving;
  assert.equal(ui.nodes.get('notify-dialog').open,true);
  assert.equal(ui.nodes.get('notify-enabled').checked,false);
  assert.equal(ui.nodes.get('notify-button').textContent,'提醒');
});

test('notification controls have English translations and language changes preserve choices',async()=>{
  const ui=await fixture();await ui.open();ui.settings();
  ui.nodes.get('notify-enabled').checked=true;ui.nodes.get('notify-completion').checked=true;
  ui.nodes.get('phone-language').value='en';ui.nodes.get('phone-language').onchange();
  const dialog=ui.html.match(/<dialog id="notify-dialog"[\s\S]*?<\/dialog>/)[0];
  for(const [,text] of dialog.matchAll(/data-i18n(?:-aria-label)?="([^"]+)"/g)){
    assert.notEqual(ui.run(`BridgeI18n.t(${JSON.stringify(text)})`),text);
  }
  assert.equal(ui.nodes.get('notify-completion').checked,true);
  assert.equal(ui.writes.length,0);
});
