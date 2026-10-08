// Isolated Electron UI: add/edit/remove synthetic APIs; never switch or sign in.
'use strict';
const {app}=require('electron'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),http=require('node:http');
if(!process.env.CMB_DATA_DIR)throw Error('CMB_DATA_DIR is required');
app.on('browser-window-created',(_,win)=>{
  win.webContents.once('did-finish-load',async()=>{
    const evaluate=code=>win.webContents.executeJavaScript(code);
    async function until(code){const end=Date.now()+20000;while(Date.now()<end){if(await evaluate(code))return;await new Promise(r=>setTimeout(r,100));}throw Error('UI timeout: '+code);}
    let upstream;const requests=[];
    try{
      await until('Boolean(snapshot)');
      await evaluate("applyLanguage('zh')");
      assert.notEqual(await evaluate('snapshot.preferences.port'),8787);
      const home=path.join(process.env.CMB_DATA_DIR,'home');assert.equal(await evaluate('snapshot.preferences.codexHome'),home);
      upstream=http.createServer((req,res)=>{requests.push({url:req.url,key:req.headers.authorization});res.setHeader('Content-Type','application/json');res.end(JSON.stringify({data:[{id:'test-model'},{id:'second-model'}]}));});
      await new Promise(resolve=>upstream.listen(0,'127.0.0.1',resolve));const url='http://127.0.0.1:'+upstream.address().port+'/v1';
      fs.writeFileSync(path.join(home,'config.toml'),`model="test-model"\n[model_providers.fixture]\nname="Fixture"\nbase_url="${url}"\nexperimental_bearer_token="scan-fixture-key"\nwire_api="responses"\n[profiles.other]\nmodel_provider="fixture"\nmodel="second-model"\n`);
      await evaluate('api.start()');await until('snapshot.runtime.running');
      await evaluate("tab('accounts')");await until('accountsPanel.value!==null');
      await evaluate('accountsPanel.scanButton.click()');await until('accountsPanel.value.discovery?.candidates.length===2&&!accountsPanel.busy');
      assert.equal(await evaluate("JSON.stringify(accountsPanel.value).includes('scan-fixture-key')"),false);
      await evaluate("[...accountsPanel.scanRows.querySelectorAll('button')].find(b=>b.textContent==='选择并导入').click();accountsPanel.modelsButton.click()");
      await until('!accountsPanel.modelOptions.hidden&&!accountsPanel.busy');
      await evaluate("accountsPanel.modelOptions.value='second-model';accountsPanel.modelOptions.onchange();accountsPanel.form.requestSubmit()");
      await until('accountsPanel.value.accounts.length===1&&!accountsPanel.busy');
      assert.equal(await evaluate('accountsPanel.value.accounts[0].model'),'second-model');
      const imported=await evaluate('accountsPanel.value.accounts[0].id');await evaluate(`accountsPanel.perform({action:'delete',id:${JSON.stringify(imported)}})`);
      await evaluate("accountsPanel.addDetails.open=true;accountsPanel.name.input.value='测试 API <script>';accountsPanel.kind.value='api';accountsPanel.kind.onchange();accountsPanel.url.input.value='https://fixture.invalid/v1';accountsPanel.key.input.value='synthetic-test-key';accountsPanel.model.input.value='test-model';accountsPanel.form.requestSubmit()");
      await until('accountsPanel.value.accounts.length===1&&!accountsPanel.busy');
      const state=await evaluate("({count:accountsPanel.value.accounts.length,key:accountsPanel.key.input.value,text:document.getElementById('accounts-content').textContent,node:typeof require})");
      assert.equal(state.node,'undefined');assert.equal(state.key,'');assert.ok(!state.text.includes('synthetic-test-key'));
      await evaluate("[...accountsPanel.rows.querySelectorAll('button')].find(b=>b.textContent==='重命名').click();accountsPanel.renameName.input.value='测试 API';accountsPanel.renameForm.requestSubmit()");
      await until("accountsPanel.value.accounts[0].name==='测试 API'&&!accountsPanel.busy");
      await evaluate(`[...accountsPanel.rows.querySelectorAll('button')].find(b=>b.textContent==='修改').click();accountsPanel.url.input.value=${JSON.stringify(url)};accountsPanel.modelsButton.click()`);
      await until('!accountsPanel.modelOptions.hidden&&!accountsPanel.busy');
      assert.deepEqual(requests,[{url:'/v1/models',key:'Bearer scan-fixture-key'},{url:'/v1/models',key:'Bearer synthetic-test-key'}]);
      fs.writeFileSync(path.join(process.env.CMB_DATA_DIR,'accounts-desktop-zh.png'),(await win.webContents.capturePage()).toPNG());
      await evaluate("applyLanguage('en')");
      fs.writeFileSync(path.join(process.env.CMB_DATA_DIR,'accounts-desktop-en.png'),(await win.webContents.capturePage()).toPNG());
      assert.equal(await evaluate('document.documentElement.scrollWidth>innerWidth'),false);
      const row=await evaluate('accountsPanel.value.accounts[0]');
      await evaluate(`accountsPanel.perform({action:'delete',id:${JSON.stringify(row.id)}})`);
      assert.equal(await evaluate('accountsPanel.value.accounts.length'),0);
      await evaluate('api.stop()');
      // Render synthetic metadata only after stopping the isolated gateway.
      await evaluate(`
        render=()=>{};applyLanguage('zh');accountsPanel.clear();accountPanel.clear();accountsPanel.resetForm();accountsPanel.addDetails.open=false;
        const usage={status:'ready',checkedAt:Date.now()/1000,limits:[{name:'Codex',windows:[{windowDurationMins:300,remainingPercent:82},{windowDurationMins:10080,remainingPercent:64}]}],resetCredits:{availableCount:3,credits:null}};
        const fixture={accounts:[{id:'official',name:'日常账号',kind:'chatgpt',email:'demo@example.com',details:{usage,models:{status:'ready',models:[{id:'fixture-model',name:'演示模型'}]}}},{id:'api',name:'开发 API',kind:'api',baseUrl:'https://example.invalid/v1',model:'fixture-model'}],current:{status:'ready',kind:'chatgpt',id:'official',name:'日常账号'},activeId:'official',switch:{phase:'idle'}};
        accountsPanel.read=async()=>fixture;accountsPanel.accept(fixture);clearTimeout(accountsPanel.timer);
        accountPanel.read=async()=>accountPanel.value;
        accountPanel.accept({visible:true,accountKey:'synthetic',email:'demo@example.com',planType:'Plus',...usage,canReset:false,updatedAt:Date.now()/1000});
      `);
      assert.equal(await evaluate("[...accountsPanel.rows.querySelectorAll('button')].find(b=>b.textContent==='当前接入').disabled"),true);
      assert.equal(await evaluate("document.querySelectorAll('[data-tab=account]').length"),0);
      fs.writeFileSync(path.join(process.env.CMB_DATA_DIR,'accounts-compact-zh.png'),(await win.webContents.capturePage()).toPNG());
      await evaluate("document.getElementById('account-details').open=true;document.getElementById('account-details').scrollIntoView({block:'end',behavior:'instant'})");
      await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
      assert.equal(await evaluate("document.getElementById('account-button').hidden"),false);
      assert.equal(await evaluate("document.getElementById('account-details').getBoundingClientRect().top<innerHeight"),true);
      fs.writeFileSync(path.join(process.env.CMB_DATA_DIR,'accounts-expanded-zh.png'),(await win.webContents.capturePage()).toPNG());
      assert.equal(await evaluate('document.documentElement.scrollWidth>innerWidth'),false);
      upstream.close();console.log('PASS: isolated desktop account tab, API enrollment, renaming, secret masking, localization, scanning, importing, provider model selection, deletion; no real account or switch.');app.exit(0);
    }catch(error){upstream?.close();console.error(error);try{await evaluate('api.stop()');}catch{}app.exit(1);}
  });
});
require('../desktop/main.cjs');
