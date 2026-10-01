'use strict';
const {app,BrowserWindow,ipcMain,dialog,shell,clipboard,Tray,Menu}=require('electron');
const path=require('node:path');
const fs=require('node:fs');
const {pathToFileURL}=require('node:url');
const {runWorker,workerFor}=require('./controller.cjs');
const {createTray}=require('./tray.cjs');
const {normalize,translate}=require('./i18n.js');
let language='zh-CN';
const t=text=>translate(text,language);
const root=path.resolve(__dirname,'..');
// An explicit data directory also keeps test caches inside the project.
if(process.env.CMB_DATA_DIR)app.setPath('userData',path.join(path.resolve(process.env.CMB_DATA_DIR),'desktop-runtime'));
let window,dataDir,tray,quitting=false,snapshotPending;
const entry=pathToFileURL(path.join(__dirname,'index.html')).href;
function loadDataDir(){
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
function worker(action,payload){
  if(action==='snapshot'&&snapshotPending)return snapshotPending;
  const result=runWorker(workerFor({packaged:app.isPackaged,resources:process.resourcesPath,root,dataDir}),action,payload);
  if(action==='snapshot')snapshotPending=result.finally(()=>{snapshotPending=null;});
  return action==='snapshot'?snapshotPending:result;
}
function showWindow(){
  if(!window)createWindow();
  if(window.isMinimized())window.restore();
  window.show();window.focus();
}
function register(){
  ipcMain.handle('bridge:language',event=>{authorize(event);return language;});
  ipcMain.handle('bridge:set-language',(event,value)=>{
    authorize(event);
    if(!['zh-CN','en'].includes(value))throw Error('Unsupported language');
    const directory=app.getPath('userData');fs.mkdirSync(directory,{recursive:true});
    fs.writeFileSync(path.join(directory,'language.json'),JSON.stringify({language:value}));
    language=value;window.setTitle(t('Codex 手机网关'));tray?.relabel();return language;
  });
  for(const action of ['snapshot','save','start','stop','logs','test-notification'])ipcMain.handle('bridge:'+action,(event,payload)=>{authorize(event);return worker(action,payload);});
  ipcMain.handle('bridge:choose',async(event,kind)=>{
    authorize(event);
    if(!['folder','file','data'].includes(kind))throw Error('未知路径类型');
    const result=await dialog.showOpenDialog(window,{properties:[kind==='file'?'openFile':'openDirectory']});
    if(result.canceled)return null;
    const selected=result.filePaths[0];
    if(kind==='data'){
      dataDir=selected;fs.mkdirSync(app.getPath('userData'),{recursive:true});
      fs.writeFileSync(path.join(app.getPath('userData'),'bridge-location.json'),JSON.stringify({dataDir}),{mode:0o600});
    }
    return selected;
  });
  ipcMain.handle('bridge:open',async(event,target)=>{
    authorize(event);
    if(target==='credentials')return shell.openPath(path.join(dataDir,'首次登录.txt'));
    if(target==='data')return shell.openPath(dataDir);
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
    dataDir=loadDataDir();register();
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
