'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const path=require('node:path');
const {workerFor,runWorker}=require('../desktop/controller.cjs');
const {createTray,primaryUrl}=require('../desktop/tray.cjs');
const i18n=require('../desktop/i18n.js');

test('language normalization and error translation preserve unknown details',()=>{
  assert.equal(i18n.normalize('en-US'),'en');assert.equal(i18n.normalize('zh-TW'),'zh-CN');
  assert.equal(i18n.normalize(null),'zh-CN');
  assert.equal(i18n.translate('运行中','en'),'Running');
  assert.equal(i18n.translate("Error invoking remote method 'bridge:save': Error: 新密码至少需要 12 个字符",'en'),i18n.english['新密码至少需要 12 个字符']);
  assert.equal(i18n.translate('C:\\private\\未知.log','en'),'C:\\private\\未知.log');
});

test('tray prefers public HTTPS and rejects non-web addresses',()=>{
  assert.equal(primaryUrl(['file:///private','http://127.0.0.1:8787','http://192.168.1.2:8787']),'http://192.168.1.2:8787');
  assert.equal(primaryUrl(['http://192.168.1.2:8787','https://example.com']),'https://example.com');
  assert.equal(primaryUrl(['javascript:alert(1)','https://user:pass@example.com']),undefined);
});

async function trayFixture(t){
  let menu,timer,destroyed=false,cleared=false,quit=false,opened=false,copied,error,failStop=false;
  const state={runtime:{running:true},urls:['http://127.0.0.1:8787','https://example.com']};
  const actions=[];
  const tray=createTray({
    Tray:class{on(){}setToolTip(){}setContextMenu(value){menu=value;}destroy(){destroyed=true;}},
    Menu:{buildFromTemplate:value=>value},icon:'icon',show:()=>{opened=true;},t,
    worker:async action=>{if(action==='snapshot')return state;actions.push(action);if(failStop)throw Error('stop failed');state.runtime.running=false;},
    open:async()=>{opened=true;},copy:async value=>{copied=value;},quit:()=>{quit=true;},onError:value=>{error=value;},
    setTimer:callback=>{timer=callback;return 1;},clearTimer:()=>{cleared=true;},
  });
  await new Promise(setImmediate);
  return {tray,state,actions,find:text=>menu.find(item=>item.label?.includes(text)),poll:()=>timer(),
    fail:()=>{failStop=true;},result:()=>({destroyed,cleared,quit,opened,copied,error})};
}

test('tray can stop then quit and dispose without duplicate operations',async()=>{
  const ui=await trayFixture();await ui.find('复制手机').click();assert.equal(ui.result().copied,'https://example.com');
  await ui.find('停止网关').click();assert.deepEqual(ui.actions,['stop']);assert.equal(ui.result().quit,true);
  ui.tray.dispose();assert.equal(ui.result().destroyed,true);assert.equal(ui.result().cleared,true);
});

test('tray can relabel without changing gateway state',async()=>{
  let language='zh-CN';const ui=await trayFixture(text=>i18n.translate(text,language));
  assert.ok(ui.find('打开控制面板'));language='en';ui.tray.relabel();
  assert.ok(ui.find('Open control panel'));assert.ok(ui.find('Stop gateway and quit'));
  assert.deepEqual(ui.actions,[]);ui.tray.dispose();
});

test('tray never quits after a failed stop or stops an unmanaged port',async()=>{
  const ui=await trayFixture();ui.fail();await ui.find('停止网关').click();assert.equal(ui.result().quit,false);assert.match(ui.result().error.message,/stop failed/);
  ui.state.runtime={running:false,portOccupied:true};await ui.poll();assert.equal(ui.find('停止网关').enabled,false);assert.equal(ui.find('打开手机').enabled,false);ui.tray.dispose();
});

test('quitting only the controller leaves the gateway untouched',async()=>{
  const ui=await trayFixture();ui.find('保留网关').click();assert.equal(ui.result().quit,true);assert.deepEqual(ui.actions,[]);ui.tray.dispose();
});
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
async function renderer(initialLanguage='zh-CN'){
  const fs=require('node:fs'),vm=require('node:vm');
  const nodes=new Map();
  function node(){return {closest(){return null;},value:'',checked:false,hidden:false,textContent:'',dataset:{},
    classList:{toggle(){}},append(){},replaceChildren(){},setAttribute(){},removeAttribute(){},querySelectorAll(){return [];}};}
  const html=fs.readFileSync(path.join(__dirname,'../desktop/index.html'),'utf8');
  for(const match of html.matchAll(/\bid="([^"]+)"/g))nodes.set(match[1],node());
  const value={runtime:{running:false,portOccupied:false,supportsNotifications:true},
    preferences:{port:8787,lan:true,tunnel:false,autoStart:false,connections:[]},
    auth:{mode:'password',username:'admin'},notifications:{enabled:false,server:'https://ntfy.sh',topic:''},
    notificationStatus:{},watches:[],origins:[],urls:[],dataDir:'/test',credentialsAvailable:false};
  let now=1000,poll;
  const api={language:async()=>initialLanguage,setLanguage:async value=>value,snapshot:async()=>structuredClone(value),start:async()=>({started:true,message:'正在启动网关'}),
    save:async payload=>{value.preferences={...value.preferences,...payload.preferences};return structuredClone(value);},logs:async()=>({text:''})};
  const context=vm.createContext({window:{bridgeDesktop:api},
    localStorage:{getItem(){return null;},setItem(){}},document:{documentElement:{},getElementById:id=>nodes.get(id),createElement:node,querySelectorAll:()=>[]},
    URL,Date:class extends Date{static now(){return now;}},setInterval:(callback,ms)=>{if(callback.name==='refresh')poll=callback;}});
  for(const name of ['web/i18n.js','desktop/connections.js','desktop/pairing.js','desktop/renderer.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,'..',name),'utf8'),context);
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

test('language changes update runtime feedback and preserve entered values',async()=>{
  const ui=await renderer();await ui.start();
  ui.nodes.get('password').value='private draft';ui.context.applyLanguage('en');
  assert.equal(ui.nodes.get('status').textContent,'Starting');
  assert.equal(ui.nodes.get('feedback').textContent,i18n.english['正在启动网关']);
  assert.equal(ui.nodes.get('password').value,'private draft');
  ui.context.applyLanguage('zh-CN');assert.equal(ui.nodes.get('status').textContent,'启动中');
});

test('saved Windows language initializes the shared UI and selector',async()=>{
  const ui=await renderer('en');
  assert.equal(ui.nodes.get('language').value,'en');
  assert.equal(ui.nodes.get('status').textContent,'Stopped');
});

test('connection additions and removals are unsaved, and language changes preserve edits',async()=>{
  const ui=await renderer();
  ui.run(`connectionDraft=[{id:'example',name:'Home',enabled:true,accessMode:'server',publicUrl:'https://codex.example.com',sshTarget:'my-server',sshRemotePort:18787,proxyUpstream:''}];renderConnections();updateDirty();`);
  assert.equal(ui.nodes.get('dirty-dot').hidden,false);
  ui.nodes.get('language').value='en';await ui.nodes.get('language').onchange();
  assert.equal(ui.context.collect().preferences.connections[0].name,'Home');
  assert.equal(ui.nodes.get('dirty-dot').hidden,false);
  assert.equal(ui.nodes.get('status').textContent,'Stopped');
  await ui.nodes.get('settings').onsubmit({preventDefault(){}});
  assert.equal(ui.nodes.get('dirty-dot').hidden,true);
  ui.run('connectionDraft=[];updateDirty();');
  assert.equal(ui.nodes.get('dirty-dot').hidden,false);
  ui.nodes.get('language').value='zh';await ui.nodes.get('language').onchange();
  assert.equal(ui.nodes.get('status').textContent,'未启动');
});

test('opening logs starts at the newest records without changing their contents',async()=>{
  const ui=await renderer();ui.api.logs=async()=>({text:'new\nold'});
  ui.nodes.get('log-output').scrollTop=500;
  await ui.context.loadLogs();
  assert.equal(ui.nodes.get('log-output').textContent,'new\nold');
  assert.equal(ui.nodes.get('log-output').scrollTop,0);
});


test('QR PNG decodes to the exact one-time fragment URL without exposing it in metadata',async()=>{
  const {pairingImage}=require('../desktop/qr.cjs'),{PNG}=require('pngjs'),decode=require('jsqr');
  const url='https://bridge.example.com/#pair='+'x'.repeat(43);
  const result=await pairingImage({id:'test',url,expires:12345,state:'active'});
  assert.equal(result.url,undefined);
  const png=PNG.sync.read(Buffer.from(result.image.split(',')[1],'base64'));
  assert.equal(decode(new Uint8ClampedArray(png.data),png.width,png.height).data,url);
  assert.equal(result.id,'test');assert.equal(result.expires,12345);
});
