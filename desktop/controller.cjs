'use strict';
const {spawn}=require('node:child_process');
const path=require('node:path');
function runWorker({executable,prefix=[],dataDir},action,payload){
  const allowed=new Set(['snapshot','save','start','stop','logs','test-notification','deployment','export-deployment','check-entry','devices','pairing','account','notification-watches','update-prepare']);
  if(!allowed.has(action))return Promise.reject(Error('未知操作'));
  return new Promise((resolve,reject)=>{
    const child=spawn(executable,[...prefix,action,'--data-dir',dataDir],{stdio:['pipe','pipe','pipe'],windowsHide:true});
    let output='',error='';
    const timer=setTimeout(()=>{child.kill();reject(Error('本地操作超时，请检查运行日志'));},action==='update-prepare'?180000:action==='account'?110000:action==='notification-watches'?45000:25000);
    child.stdout.setEncoding('utf8');child.stdout.on('data',data=>{output+=data;});
    child.stderr.setEncoding('utf8');child.stderr.on('data',data=>{error+=data;});
    child.on('error',err=>{clearTimeout(timer);reject(Error('无法启动网关运行时：'+err.message));});
    child.on('close',()=>{clearTimeout(timer);try{const value=JSON.parse(output.trim());if(!value.ok)throw Error(value.error);resolve(value.result);}catch(e){reject(Error(output.trim()?e.message:'网关运行时未返回结果，请检查日志或重新安装应用'));}});
    child.stdin.on('error',()=>{});child.stdin.end(payload===undefined?'':JSON.stringify(payload));
  });
}
function workerFor({packaged,resources,root,dataDir}){
  return packaged?{executable:path.join(resources,'gateway',process.platform==='win32'?'codex-mobile-gateway.exe':'codex-mobile-gateway'),dataDir}:
    {executable:process.env.CMB_PYTHON||(process.platform==='win32'?'python':'python3'),prefix:['-B',path.join(root,'desktop.py')],dataDir};
}
module.exports={runWorker,workerFor};
