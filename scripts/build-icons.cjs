// Render the existing product SVG; use the same artwork for Windows shell icons.
'use strict';
const {app,BrowserWindow}=require('electron');
const fs=require('node:fs'),path=require('node:path');
app.whenReady().then(async()=>{
  const window=new BrowserWindow({show:false,width:256,height:256,useContentSize:true,
    transparent:true,webPreferences:{offscreen:true,contextIsolation:true,nodeIntegration:false}});
  try{
    const svg=fs.readFileSync(path.join(__dirname,'../web/icon.svg'),'utf8');
    await window.loadURL('data:text/html;charset=utf-8,'+encodeURIComponent('<style>html,body{margin:0;width:256px;height:256px;background:transparent}svg{width:256px;height:256px}</style>'+svg));
    const png=(await window.webContents.capturePage()).resize({width:256,height:256}).toPNG();
    const header=Buffer.alloc(22);
    header.writeUInt16LE(1,2);header.writeUInt16LE(1,4);
    header.writeUInt16LE(1,10);header.writeUInt16LE(32,12);
    header.writeUInt32LE(png.length,14);header.writeUInt32LE(22,18);
    const output=path.join(__dirname,'../desktop/assets');
    fs.mkdirSync(output,{recursive:true});
    fs.writeFileSync(path.join(output,'icon.png'),png);
    fs.writeFileSync(path.join(output,'icon.ico'),Buffer.concat([header,png]));
    app.exit(0);
  }catch(error){console.error(error);app.exit(1);}
});
