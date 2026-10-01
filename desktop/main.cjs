'use strict';
const {app,BrowserWindow,ipcMain,dialog,shell,clipboard,Tray,Menu,net}=require('electron');
const path=require('node:path');
const fs=require('node:fs');
const {pathToFileURL}=require('node:url');
const {runWorker,workerFor}=require('./controller.cjs');
const {pairingImage}=require('./qr.cjs');
const {createTray}=require('./tray.cjs');
const {normalize,translate}=require('./i18n.js');
const cloudflared=require('./cloudflared.cjs');
const {Updater,allowedUrl}=require('./updater.cjs');
const {spawn}=require('node:child_process');
const {randomUUID}=require('node:crypto');
let language='zh-CN';
const t=text=>translate(text,language);
const root=path.resolve(__dirname,'..');
// An explicit data directory also keeps test caches inside the project.
if(process.env.CMB_DATA_DIR)app.setPath('userData',path.join(path.resolve(process.env.CMB_DATA_DIR),'desktop-runtime'));
let window,dataDir,tray,quitting=false,snapshotPending,lastSnapshot,updateQuitting=false;
let installPending,installStatus={},updater,workerWrites=0;
const entry=pathToFileURL(path.join(__dirname,'index.html')).href;
function loadDataDir(){
  if(process.env.CMB_UPDATE_DATA_DIR)return path.resolve(process.env.CMB_UPDATE_DATA_DIR);
  if(process.env.CMB_DATA_DIR)return path.resolve(process.env.CMB_DATA_DIR);
  const saved=path.join(app.getPath('userData'),'bridge-location.json');
  if(fs.existsSync(saved))return JSON.parse(fs.readFileSync(saved,'utf8')).dataDir;
  const local=path.join(process.resourcesPath,'bridge-data-dir.txt');
  if(app.isPackaged&&fs.existsSync(local))return fs.readFileSync(local,'utf8').trim();
  return app.isPackaged?path.join(app.getPath('userData'),'gateway'):path.join(root,'.local');
}
function authorize(event){
  if(!window||event.sender!==window.webContents||event.senderFrame!==window.webContents.mainFrame||event.senderFrame.url!==entry)throw Error('不允许的界面来源');
}
function updateResult(){try{return JSON.parse(fs.readFileSync(path.join(dataDir,'desktop-update-result.json'),'utf8'));}catch{return null;}}
let updateHandoffPending=!!process.env.CMB_UPDATE_TRANSACTION;
function updateManaged(){
  if(updateHandoffPending&&fs.existsSync(path.join(process.env.CMB_UPDATE_TRANSACTION,'result.json')))updateHandoffPending=false;
  return updateHandoffPending;
}
function worker(action,payload){
  if(action==='snapshot'&&updateQuitting)return Promise.resolve({...lastSnapshot,update:updater.status()});
  const writes=['save','start','stop'].includes(action);
  if(writes&&updater?.busy)return Promise.reject(Error('正在更新应用，请稍候。'));
  if(writes)workerWrites++;
  if(action==='snapshot'&&snapshotPending)return snapshotPending;
  const result=runWorker(workerFor({packaged:app.isPackaged,resources:process.resourcesPath,root,dataDir}),action,payload).then(value=>action==='snapshot'?{...value,cloudflaredInstall:installStatus,update:updater?.status(),updateResult:updateResult(),updateManaged:updateManaged()}:value).finally(()=>{if(writes)workerWrites--;});
  if(action==='snapshot')snapshotPending=result.then(value=>{lastSnapshot=value;return value;}).finally(()=>{snapshotPending=null;});
  return action==='snapshot'?snapshotPending:result;
}
async function installUpdate(candidate){
  const target=process.platform==='darwin'?path.resolve(process.execPath,'../../..'):path.dirname(process.execPath);
  const token=randomUUID();
  const prepared=await worker('update-prepare',{archive:candidate.archive,sha256:candidate.asset.sha256,version:candidate.version,
    platform:process.platform,arch:process.arch,target,parentPid:process.pid,token});
  // Preserve selected data location even when an old local bundle carried it inside Resources.
  fs.mkdirSync(app.getPath('userData'),{recursive:true});
  fs.writeFileSync(path.join(app.getPath('userData'),'bridge-location.json'),JSON.stringify({dataDir}),{mode:0o600});
  const log=fs.openSync(path.join(dataDir,'desktop-update.log'),'a',0o600);
  const child=spawn(prepared.helper,['update-apply','--data-dir',dataDir,'--plan',prepared.plan],{
    detached:true,windowsHide:true,stdio:['ignore',log,log],env:{...process.env,PYINSTALLER_RESET_ENVIRONMENT:'1'}});
  fs.closeSync(log);
  let spawnError;child.on('error',error=>{spawnError=error;});child.unref();
  const ready=path.join(path.dirname(prepared.plan),'ready.json'),end=Date.now()+15000;
  while(Date.now()<end){
    if(spawnError)throw spawnError;
    if(child.exitCode!==null)throw Error('无法启动应用更新进程。');
    try{if(JSON.parse(fs.readFileSync(ready,'utf8')).token===token){updateQuitting=true;if(snapshotPending)await snapshotPending;setTimeout(()=>app.quit(),300);return;}}catch{}
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  throw Error('无法启动应用更新进程。');
}
function setupUpdater(){
  updater=new Updater({current:app.getVersion(),key:fs.readFileSync(path.join(__dirname,'update-public-key.pem')),
    fetch:cloudflared.electronFetch(net,allowedUrl),directory:path.join(app.getPath('userData'),'updates'),
    supported:app.isPackaged&&['darwin','win32'].includes(process.platform),install:installUpdate});
  setTimeout(()=>updater.check(),5000).unref();
  setInterval(()=>updater.check(),6*60*60*1000).unref();
}
function showWindow(){
  if(!window)createWindow();
  if(window.isMinimized())window.restore();
  window.show();window.focus();
}
function register(){
  ipcMain.handle('bridge:check-update',event=>{authorize(event);return updater.check();});
  ipcMain.handle('bridge:install-update',event=>{
    authorize(event);
    if(installPending||workerWrites||updateManaged())throw Error('请等待当前操作完成后再更新。');
    return updater.install();
  });
  ipcMain.handle('bridge:language',event=>{authorize(event);return language;});
  ipcMain.handle('bridge:set-language',(event,value)=>{
    authorize(event);
    if(!['zh-CN','en'].includes(value))throw Error('Unsupported language');
    const directory=app.getPath('userData');fs.mkdirSync(directory,{recursive:true});
    fs.writeFileSync(path.join(directory,'language.json'),JSON.stringify({language:value}));
    language=value;window.setTitle(t('Codex 手机网关'));tray?.relabel();return language;
  });
  for(const action of ['snapshot','save','start','stop','logs','test-notification','check-entry'])ipcMain.handle('bridge:'+action,(event,payload)=>{authorize(event);return worker(action,payload);});
  ipcMain.handle('bridge:install-cloudflared',async event=>{
    authorize(event);if(updater.busy)throw Error('正在更新应用，请稍候。');if(installPending)return installPending;
    installPending=(async()=>{
      if((await worker('snapshot')).runtime.running)throw Error('请先停止网关再安装 cloudflared。');
      return cloudflared.install({dataDir,fetch:cloudflared.electronFetch(net),onProgress:value=>{installStatus=value;}});
    })().catch(error=>{installStatus={state:'error',message:error.message};throw error;}).finally(()=>{installPending=null;});
    return installPending;
  });
  ipcMain.handle('bridge:check-cloudflared',async(event,value)=>{
    authorize(event);const snapshot=await worker('snapshot');
    const executable=typeof value==='string'&&value.trim()?value.trim():snapshot.preferences.cloudflared;
    if(!executable||!path.isAbsolute(executable))throw Error('未找到 cloudflared，请点击一键安装，或选择已下载的程序。');
    return {path:executable,version:await cloudflared.probe(executable)};
  });
  ipcMain.handle('bridge:pairing',async(event,payload)=>{
    authorize(event);
    const grant=await worker('pairing',payload);
    if(payload.action!=='create')return grant;
    try{return await pairingImage(grant);}
    catch(error){await worker('pairing',{action:'revoke',id:grant.id});throw error;}
  });
  ipcMain.handle('bridge:export-deployment',async(event,payload)=>{
    authorize(event);
    const result=await dialog.showSaveDialog(window,{defaultPath:'Codex-deployment.zip',filters:[{name:payload?.language==='en'?'Deployment ZIP':'ZIP 部署包',extensions:['zip']}]});
    if(result.canceled)return null;
    const bundle=await worker('export-deployment',payload);
    fs.copyFileSync(bundle.path,result.filePath);
    return {message:payload?.language==='en'?'Deployment ZIP exported: '+result.filePath:'部署包已导出：'+result.filePath};
  });
  ipcMain.handle('bridge:copy-deployment',async(event,payload)=>{
    authorize(event);const bundle=await worker('deployment',payload);
    const english=payload?.language==='en';
    const intro=english?'Deploy Codex Mobile Bridge using the configuration below. Check existing services first; do not overwrite existing sites.':'请按以下配置帮我部署 Codex 手机网关的固定入口。先检查已有服务，不要覆盖已有站点。';
    const text=intro+'\n\n'+Object.entries(bundle.files).filter(([name])=>name!==(english?'部署说明.md':'DEPLOYMENT_EN.md')).map(([name,content])=>'--- '+name+' ---\n'+content).join('\n');
    clipboard.writeText(text);return {message:english?'Deployment instructions and configuration copied. Paste them into your server or NAS Agent.':'部署说明与配置已复制，可粘贴给服务器或 NAS 上的 Agent。'};
  });
  ipcMain.handle('bridge:choose',async(event,kind)=>{
    authorize(event);
    if(!['folder','file','data'].includes(kind))throw Error('未知路径类型');
    const result=await dialog.showOpenDialog(window,{properties:[kind==='file'?'openFile':'openDirectory']});
    if(result.canceled)return null;
    const selected=result.filePaths[0];
    if(kind==='data'){
      if(updater.busy)throw Error('正在更新应用，请稍候。');
      if(installPending)throw Error('正在安装 cloudflared，请完成后再切换数据目录。');
      installStatus={};
      dataDir=selected;fs.mkdirSync(app.getPath('userData'),{recursive:true});
      fs.writeFileSync(path.join(app.getPath('userData'),'bridge-location.json'),JSON.stringify({dataDir}),{mode:0o600});
    }
    return selected;
  });
  ipcMain.handle('bridge:open',async(event,target)=>{
    authorize(event);
    if(target==='releases')return shell.openExternal('https://github.com/try2love/codex-mobile-bridge/releases');
    if(target==='credentials')return shell.openPath(path.join(dataDir,'首次登录.txt'));
    if(target==='data')return shell.openPath(dataDir);
    if(target==='cloudflare-help')return shell.openExternal('https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/downloads/');
    if(target==='ntfy-help')return shell.openExternal('https://docs.ntfy.sh/subscribe/phone/');
    const snapshot=await worker('snapshot');
    if(!snapshot.urls.includes(target)||!/^https?:\/\//.test(target))throw Error('地址不可用');
    await shell.openExternal(target);
  });
  ipcMain.handle('bridge:copy',async(event,target)=>{
    authorize(event);const snapshot=await worker('snapshot');
    if(!snapshot.urls.includes(target))throw Error('地址不可用');
    clipboard.writeText(target);
  });
}
function createWindow(){
  window=new BrowserWindow({width:1100,height:850,minWidth:820,minHeight:640,title:'Codex 手机网关',icon:path.join(__dirname,'assets/icon.png'),backgroundColor:'#f6f7f9',webPreferences:{preload:path.join(__dirname,'preload.cjs'),nodeIntegration:false,contextIsolation:true,sandbox:true}});
  window.webContents.setWindowOpenHandler(()=>({action:'deny'}));
  window.webContents.on('will-navigate',event=>event.preventDefault());
  window.webContents.once('did-finish-load',async()=>{
    if(!process.env.CMB_UPDATE_TRANSACTION)return;
    try{
      await worker('snapshot');
      fs.writeFileSync(path.join(process.env.CMB_UPDATE_TRANSACTION,'ack.json'),JSON.stringify({token:process.env.CMB_UPDATE_TOKEN,version:app.getVersion(),dataDir}),{mode:0o600});
    }catch{} // Missing acknowledgement makes the independent helper restore the old app.
  });
  window.loadFile(path.join(__dirname,'index.html'));
  window.on('close',event=>{if(tray&&!quitting){event.preventDefault();window.hide();}});
  window.on('closed',()=>{window=null;});
  return window;
}
if(!app.requestSingleInstanceLock())app.quit();
else{
  app.on('second-instance',showWindow);
  app.whenReady().then(()=>{
    language=normalize(app.getLocale());
    try{language=normalize(JSON.parse(fs.readFileSync(path.join(app.getPath('userData'),'language.json'),'utf8')).language);}catch{}
    dataDir=loadDataDir();setupUpdater();register();
    if(process.platform==='win32'){
      app.setAppUserModelId('io.github.try2love.codexmobilebridge');
      tray=createTray({Tray,Menu,icon:path.join(__dirname,'assets/icon.ico'),show:showWindow,worker,t,
        open:url=>shell.openExternal(url),copy:url=>clipboard.writeText(url),quit:()=>app.quit(),
        onError:error=>{showWindow();dialog.showErrorBox(t('网关操作未完成'),t(error.message));}});
    }
    createWindow();
  });
  app.on('before-quit',()=>{quitting=true;tray?.dispose();tray=null;});
  app.on('activate',showWindow);
  // Closing the controller leaves the gateway running for the phone.
  app.on('window-all-closed',()=>app.quit());
}
