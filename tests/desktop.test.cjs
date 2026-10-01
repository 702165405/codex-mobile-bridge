'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const path=require('node:path');
const {workerFor,runWorker}=require('../desktop/controller.cjs');
test('packaged launch uses bundled runtime and literal data directory',()=>{
  const result=workerFor({packaged:true,resources:'/app resources',root:'/source',dataDir:'/my data'});
  assert.equal(result.dataDir,'/my data');assert.equal(result.executable,path.join('/app resources','gateway',process.platform==='win32'?'codex-mobile-gateway.exe':'codex-mobile-gateway'));
});
test('unknown IPC action cannot become a command',async()=>{await assert.rejects(runWorker({executable:'unused',dataDir:'unused'},'shell'),/未知操作/);});
test('development launch keeps script as its own argument',()=>{
  const result=workerFor({packaged:false,root:'/source path',dataDir:'/data'});assert.deepEqual(result.prefix,['-B',path.join('/source path','desktop.py')]);
});

// Exercise the real renderer's start and polling handlers with a controlled
// backend and clock. DOM layout and input editing are outside these checks.
async function renderer(){
  const fs=require('node:fs'),vm=require('node:vm');
  const nodes=new Map();
  function node(){return {closest(){return null;},value:'',checked:false,hidden:false,textContent:'',dataset:{},
    classList:{toggle(){}},append(){},replaceChildren(){},setAttribute(){},querySelectorAll(){return [];}};}
  const html=fs.readFileSync(path.join(__dirname,'../desktop/index.html'),'utf8');
  for(const match of html.matchAll(/\bid="([^"]+)"/g))nodes.set(match[1],node());
  const value={runtime:{running:false,portOccupied:false,supportsNotifications:true},
    preferences:{port:8787,lan:true,tunnel:false,autoStart:false,connections:[]},
    auth:{mode:'password',username:'admin'},notifications:{enabled:false,server:'https://ntfy.sh',topic:''},
    notificationStatus:{},watches:[],origins:[],urls:[],dataDir:'/test',credentialsAvailable:false};
  let now=1000,poll;
  const api={snapshot:async()=>structuredClone(value),start:async()=>({started:true,message:'正在启动网关'}),
    save:async payload=>{value.preferences={...value.preferences,...payload.preferences};return structuredClone(value);},logs:async()=>({text:''})};
  const context=vm.createContext({window:{bridgeDesktop:api},
    localStorage:{getItem(){return null;},setItem(){}},document:{documentElement:{},getElementById:id=>nodes.get(id),createElement:node,querySelectorAll:()=>[]},
    Date:class extends Date{static now(){return now;}},setInterval:callback=>{poll=callback;}});
  for(const name of ['web/i18n.js','desktop/connections.js','desktop/renderer.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,'..',name),'utf8'),context);
  await new Promise(setImmediate);
  return {nodes,value,context,api,run:code=>vm.runInContext(code,context),poll:()=>poll(),advance:ms=>{now+=ms;},start:()=>nodes.get('start').onclick()};
}

test('startup feedback follows readiness, page changes and later shutdown',async()=>{
  const ui=await renderer();await ui.start();
  assert.match(ui.nodes.get('feedback').textContent,/正在启动/);
  assert.equal(ui.nodes.get('status').textContent,'启动中');
  ui.value.runtime.running=true;await ui.poll();
  assert.equal(ui.nodes.get('status').textContent,'运行中');
  assert.match(ui.nodes.get('feedback').textContent,/已启动/);
  for(const page of ['network','notifications','logs']){
    ui.context.tab(page);await ui.poll();
    assert.doesNotMatch(ui.nodes.get('feedback').textContent,/正在启动/);
  }
  ui.value.runtime.running=false;await ui.poll();
  assert.equal(ui.nodes.get('feedback').textContent,'网关已停止');
});

test('startup timeout replaces pending feedback and a late ready state recovers',async()=>{
  const ui=await renderer();await ui.start();ui.advance(71000);await ui.poll();
  assert.equal(ui.nodes.get('feedback').hidden,true);
  assert.equal(ui.nodes.get('error').hidden,false);
  assert.match(ui.nodes.get('error').textContent,/启动超时/);
  await ui.poll();assert.match(ui.nodes.get('error').textContent,/启动超时/);
  ui.value.runtime.running=true;await ui.poll();
  assert.equal(ui.nodes.get('error').hidden,true);
  assert.match(ui.nodes.get('feedback').textContent,/已启动/);
});

test('an occupied port ends the pending startup indication',async()=>{
  const ui=await renderer();await ui.start();ui.value.runtime.portOccupied=true;await ui.poll();
  assert.equal(ui.nodes.get('status').textContent,'端口已占用');
  assert.equal(ui.nodes.get('feedback').hidden,true);
  assert.match(ui.nodes.get('error').textContent,/端口/);
});

test('runtime polling preserves a newer save notification',async()=>{
  const ui=await renderer();await ui.start();
  await ui.nodes.get('settings').onsubmit({preventDefault(){}});
  const saved=ui.nodes.get('feedback').textContent;
  assert.match(saved,/配置已保存/);
  ui.value.runtime.running=true;await ui.poll();
  assert.equal(ui.nodes.get('status').textContent,'运行中');
  assert.equal(ui.nodes.get('feedback').textContent,saved);
});

test('connection additions and removals are unsaved, and language changes preserve edits',async()=>{
  const ui=await renderer();
  ui.run(`connectionDraft=[{id:'example',name:'Home',enabled:true,accessMode:'server',publicUrl:'https://codex.example.com',sshTarget:'my-server',sshRemotePort:18787,proxyUpstream:''}];renderConnections();updateDirty();`);
  assert.equal(ui.nodes.get('dirty-dot').hidden,false);
  ui.nodes.get('language').value='en';ui.nodes.get('language').onchange();
  assert.equal(ui.context.collect().preferences.connections[0].name,'Home');
  assert.equal(ui.nodes.get('dirty-dot').hidden,false);
  assert.equal(ui.nodes.get('status').textContent,'Stopped');
  await ui.nodes.get('settings').onsubmit({preventDefault(){}});
  assert.equal(ui.nodes.get('dirty-dot').hidden,true);
  ui.run('connectionDraft=[];updateDirty();');
  assert.equal(ui.nodes.get('dirty-dot').hidden,false);
  ui.nodes.get('language').value='zh';ui.nodes.get('language').onchange();
  assert.equal(ui.nodes.get('status').textContent,'未启动');
});

test('opening logs starts at the newest records without changing their contents',async()=>{
  const ui=await renderer();ui.api.logs=async()=>({text:'new\nold'});
  ui.nodes.get('log-output').scrollTop=500;
  await ui.context.loadLogs();
  assert.equal(ui.nodes.get('log-output').textContent,'new\nold');
  assert.equal(ui.nodes.get('log-output').scrollTop,0);
});
