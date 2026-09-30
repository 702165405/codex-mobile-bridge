// Run with Electron, against a separate CMB_DATA_DIR and unused test port.
'use strict';
const {app}=require('electron');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
if(!process.env.CMB_DATA_DIR)throw Error('CMB_DATA_DIR is required for isolated UI tests');
app.on('browser-window-created',(_,win)=>{
  win.webContents.once('did-finish-load',async()=>{
    const evaluate=code=>win.webContents.executeJavaScript(code);
    async function until(code){const deadline=Date.now()+15000;while(Date.now()<deadline){if(await evaluate(code))return;await new Promise(r=>setTimeout(r,200));}throw Error('UI timeout: '+code);}
    try{
      await until('Boolean(snapshot)');
      const data=await evaluate('({port:snapshot.preferences.port,dir:snapshot.dataDir,node:typeof require,ntfy:snapshot.notifications.enabled})');
      assert.notEqual(data.port,8787);assert.equal(data.node,'undefined');assert.equal(data.ntfy,false);
      await evaluate("document.getElementById('start').click()");
      await until('snapshot.runtime.running');
      assert.equal(await evaluate("document.getElementById('port').disabled"),true);
      await evaluate("document.querySelector('[data-tab=notifications]').click()");
      assert.equal(await evaluate("document.querySelector('[data-panel=notifications]').hidden"),false);
      await evaluate("document.getElementById('ntfy-topic').value='smoke-topic';document.getElementById('ntfy-topic').dispatchEvent(new Event('input',{bubbles:true}));document.getElementById('settings').requestSubmit()");
      await until("!dirty&&snapshot.notifications.topic==='smoke-topic'");
      const image=await win.webContents.capturePage();fs.writeFileSync(path.join(process.env.CMB_DATA_DIR,'desktop-notifications.png'),image.toPNG());
      await evaluate("document.querySelector('[data-tab=overview]').click()");
      fs.writeFileSync(path.join(process.env.CMB_DATA_DIR,'desktop-overview.png'),(await win.webContents.capturePage()).toPNG());
      await evaluate("document.getElementById('stop').click()");
      await until('!snapshot.runtime.running');
      console.log(JSON.stringify({ok:true,checks:['isolated port','preload isolation','one-click start','network locked while running','settings saved','ntfy stays disabled','one-click stop','real window screenshots']}));
      app.exit(0);
    }catch(error){console.error(error);try{await evaluate('window.bridgeDesktop.stop()');}catch{}app.exit(1);}
  });
});
require('../desktop/main.cjs');
