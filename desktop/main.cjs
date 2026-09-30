'use strict';
const {app,BrowserWindow,ipcMain,dialog,shell,clipboard}=require('electron');
const path=require('node:path');
const fs=require('node:fs');
const {pathToFileURL}=require('node:url');
const {runWorker,workerFor}=require('./controller.cjs');
const root=path.resolve(__dirname,'..');
// An explicit data directory also keeps test caches inside the project.
if(process.env.CMB_DATA_DIR)app.setPath('userData',path.join(path.resolve(process.env.CMB_DATA_DIR),'desktop-runtime'));
let window,dataDir;
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
function worker(action,payload){return runWorker(workerFor({packaged:app.isPackaged,resources:process.resourcesPath,root,dataDir}),action,payload);}
function register(){
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
  window=new BrowserWindow({width:1100,height:850,minWidth:820,minHeight:640,title:'Codex 手机网关',backgroundColor:'#f7f7f2',webPreferences:{preload:path.join(__dirname,'preload.cjs'),nodeIntegration:false,contextIsolation:true,sandbox:true}});
  window.webContents.setWindowOpenHandler(()=>({action:'deny'}));
  window.webContents.on('will-navigate',event=>event.preventDefault());
  window.loadFile(path.join(__dirname,'index.html'));
  window.on('closed',()=>{window=null;});
  return window;
}
if(!app.requestSingleInstanceLock())app.quit();
else{
  app.on('second-instance',()=>{if(window){if(window.isMinimized())window.restore();window.focus();}});
  app.whenReady().then(()=>{dataDir=loadDataDir();register();createWindow();});
  app.on('activate',()=>{if(!window)createWindow();});
  // Closing the controller leaves the gateway running for the phone.
  app.on('window-all-closed',()=>app.quit());
}
