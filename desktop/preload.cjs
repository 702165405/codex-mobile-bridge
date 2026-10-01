'use strict';
const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('bridgeDesktop',{
  language:()=>ipcRenderer.invoke('bridge:language'),
  setLanguage:value=>ipcRenderer.invoke('bridge:set-language',value),
  snapshot:()=>ipcRenderer.invoke('bridge:snapshot'),
  save:value=>ipcRenderer.invoke('bridge:save',value),
  start:()=>ipcRenderer.invoke('bridge:start'),
  stop:()=>ipcRenderer.invoke('bridge:stop'),
  logs:()=>ipcRenderer.invoke('bridge:logs'),
  testNotification:()=>ipcRenderer.invoke('bridge:test-notification'),
  choose:kind=>ipcRenderer.invoke('bridge:choose',kind),
  open:target=>ipcRenderer.invoke('bridge:open',target),
  copy:target=>ipcRenderer.invoke('bridge:copy',target)
});
