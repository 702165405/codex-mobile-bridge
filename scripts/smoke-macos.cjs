// Launch the actual signed bundle with isolated data; do not start a gateway.
'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs/promises'),path=require('node:path'),net=require('node:net');
const {spawn}=require('node:child_process');
const root=path.resolve(__dirname,'..'),delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function main(){
  assert.equal(process.platform,'darwin');
  assert.ok(process.argv[2],'Pass the packaged .app path');
  await fs.mkdir(path.join(root,'.tmp'),{recursive:true});
  const data=await fs.mkdtemp(path.join(root,'.tmp','macos-launch-'));
  const server=net.createServer();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const port=server.address().port;await new Promise(resolve=>server.close(resolve));
  const executable=path.join(path.resolve(process.argv[2]),'Contents/MacOS/Codex Mobile Bridge');
  const env={...process.env,CMB_DATA_DIR:data};
  for(const key of ['CMB_PYTHON','PYTHONPATH','PYTHONHOME','ELECTRON_RUN_AS_NODE'])delete env[key];
  const child=spawn(executable,['--remote-debugging-address=127.0.0.1','--remote-debugging-port='+port],{env,stdio:['ignore','ignore','pipe']});
  let errors='',launchError,socket;
  child.stderr.on('data',chunk=>errors+=chunk);child.on('error',error=>launchError=error);
  const exited=new Promise(resolve=>child.once('exit',resolve));
  const deadline=Date.now()+30000;
  try{
    let target;
    while(Date.now()<deadline&&!target){
      if(launchError)throw launchError;
      assert.equal(child.exitCode,null,'Packaged app exited: '+errors);
      try{
        const response=await fetch('http://127.0.0.1:'+port+'/json/list',{signal:AbortSignal.timeout(1000)});
        target=(await response.json()).find(item=>item.type==='page'&&item.url.endsWith('/desktop/index.html'));
      }catch{}
      if(!target)await delay(200);
    }
    assert.ok(target,'Packaged renderer failed to load: '+errors);
    socket=new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
    const state=await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>reject(Error('Packaged UI snapshot timed out')),15000);
      socket.addEventListener('message',event=>{
        const value=JSON.parse(event.data);if(value.id!==1)return;
        clearTimeout(timer);
        if(value.error||value.result.exceptionDetails)reject(Error(JSON.stringify(value)));
        else resolve(value.result.result.value);
      });
      socket.send(JSON.stringify({id:1,method:'Runtime.evaluate',params:{awaitPromise:true,returnByValue:true,expression:`(async()=>{
        const deadline=Date.now()+10000;
        while((typeof snapshot==='undefined'||!snapshot)&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,100));
        if(typeof snapshot==='undefined'||!snapshot)throw Error('Bundled worker did not return a snapshot');
        return {directory:snapshot.dataDir,running:snapshot.runtime.running,notifications:snapshot.notifications.enabled,node:typeof require};
      })()`}}));
    });
    assert.equal(state.directory,data);assert.equal(state.running,false);assert.equal(state.notifications,false);assert.equal(state.node,'undefined');
    console.log('PASS: signed packaged App opens, renderer and bundled worker load, isolated data, gateway remains stopped.');
  }finally{
    socket?.close();child.kill();
    await Promise.race([exited,delay(3000)]);
    if(child.exitCode===null&&!launchError)child.kill('SIGKILL');
  }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
