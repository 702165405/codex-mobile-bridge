'use strict';
const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('bridgeDesktop',{
  snapshot:()=>ipcRenderer.invoke('bridge:snapshot'),
  save:value=>ipcRenderer.invoke('bridge:save',value),
  start:()=>ipcRenderer.invoke('bridge:start'),
  stop:()=>ipcRenderer.invoke('bridge:stop'),
  logs:()=>ipcRenderer.invoke('bridge:logs'),
  testNotification:()=>ipcRenderer.invoke('bridge:test-notification'),
  exportDeployment:()=>ipcRenderer.invoke('bridge:export-deployment'),
  copyDeployment:()=>ipcRenderer.invoke('bridge:copy-deployment'),
  checkEntry:()=>ipcRenderer.invoke('bridge:check-entry'),
  choose:kind=>ipcRenderer.invoke('bridge:choose',kind),
  open:target=>ipcRenderer.invoke('bridge:open',target),
  copy:target=>ipcRenderer.invoke('bridge:copy',target)
});
