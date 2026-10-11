const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),{webcrypto}=require('node:crypto');
function host({hold=false}={}) {
 const calls=[],saved=[],cancelled=[];let context;
 class XHR {open(){} setRequestHeader(){} send(){} abort(){}}
 const bridge={state:()=>'{"draft":"private draft"}',saveState:(token,value)=>saved.push(JSON.parse(value)),cancel:(token,id)=>cancelled.push(id),post:(token,id,url,headers,body)=>{calls.push({id,url,headers:JSON.parse(headers),body:Buffer.from(body,'base64').toString()});if(!hold)queueMicrotask(()=>context.__cmbReceive(token,id,200,{'Content-Type':'application/json'},Buffer.from('{"ok":true}').toString('base64'),false));}};
 context=vm.createContext({CmbNative:bridge,fetch:async()=>new Response('{"get":true}'),XMLHttpRequest:XHR,location:new URL('https://fixture.invalid/'),Request,Response,Blob,Uint8Array,URL,crypto:webcrypto,atob,btoa,DOMException,Event,setTimeout,clearTimeout});context.window=context;
 vm.runInContext(fs.readFileSync('android/app/src/main/assets/fork-workbench.js','utf8').replace('__CMB_TOKEN__','fixture-token'),context);
 return {context,calls,saved,cancelled};
}
test('workbench POST carries CSRF through the native transport exactly once; local drafts use native storage',async()=>{
 const {context,calls,saved}=host();const result=await context.fetch('https://fixture.invalid/api/login',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':'csrf-fixture'},body:'{"password":"private password"}'});
 assert.equal((await result.json()).ok,true);assert.equal(calls.length,1);assert.equal(calls[0].headers['x-csrf-token'],'csrf-fixture');assert.equal(calls[0].body,'{"password":"private password"}');assert.equal(context.localStorage.getItem('draft'),'private draft');context.localStorage.setItem('draft','next draft');assert.equal(saved.at(-1).draft,'next draft');assert.equal(context.localStorage.length,1);
});
test('workbench blocks cross-origin and non-API writes before reaching native credentials',async()=>{
 const {context,calls}=host();for(const url of ['https://other.invalid/api/send','https://fixture.invalid/admin'])await assert.rejects(context.fetch(url,{method:'POST',body:'x'}),/不允许/);assert.equal(calls.length,0);
});
test('cancelled mutations are cancelled once and never automatically replayed',async()=>{
 const {context,calls,cancelled}=host({hold:true});const abort=new AbortController();const request=context.fetch('https://fixture.invalid/api/send',{method:'POST',body:'x',signal:abort.signal});while(!calls.length)await new Promise(resolve=>setTimeout(resolve,1));abort.abort();await assert.rejects(request,/未确认/);assert.equal(calls.length,1);assert.deepEqual(cancelled,[calls[0].id]);
});
