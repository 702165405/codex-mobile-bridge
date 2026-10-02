'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
const {SessionActivity}=require('../web/activity.js');
const storage=()=>{const values=new Map();return {getItem:k=>values.get(k),setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k)};};
const status=(id,host,state,turn='one',result=state==='active'?'inProgress':'completed')=>({id,host,connected:true,status:state,turnId:turn,turnStatus:result});

test('concurrent host-qualified runs become unread completion badges and persist until viewed',()=>{
 const saved=storage(),a=new SessionActivity({storage:saved});
 a.update([status('a','local','active'),status('a','remote','active')]);
 assert.equal(a.indicator('local|a').kind,'running');
 a.update([status('a','local','idle'),status('a','remote','active')]);
 assert.equal(a.indicator('local|a').kind,'completed');assert.equal(a.indicator('remote|a').kind,'running');
 const restored=new SessionActivity({storage:saved});assert.equal(restored.indicator('local|a').kind,'completed');
 restored.read('local|a');restored.update([status('a','local','idle')]);assert.equal(restored.indicator('local|a'),null);
 restored.update([status('a','local','idle','two')]);assert.equal(restored.indicator('local|a').kind,'completed');
});
test('initial history does not flood unread; offline is unknown and stopped/failed are not successes',()=>{
 const a=new SessionActivity({storage:storage()});
 a.update([status('a','local','idle')]);assert.equal(a.indicator('local|a'),null);
 a.update([status('a','local','active','two')]);a.update([{id:'a',host:'local',connected:false,status:'unknown'}]);assert.equal(a.indicator('local|a').kind,'unknown');
 a.update([status('a','local','idle','two','failed')]);assert.equal(a.indicator('local|a').kind,'ended');
 a.update([status('a','local','idle','three','interrupted')],'local|a');assert.equal(a.indicator('local|a'),null);
});
test('temporarily missing turn history does not resurrect a read completion',()=>{
 const a=new SessionActivity({storage:storage()});a.update([status('a','local','idle')]);
 a.update([{id:'a',host:'local',connected:true,status:'idle',turnId:null,turnStatus:null}]);
 a.update([status('a','local','idle')]);assert.equal(a.indicator('local|a'),null);
});
function fixture(){
 let count=0,upload=async(key,id,file)=>({id,name:file.name,size:file.size,image:null});
 const calls=[],saved=storage();
 const node=tag=>({tag,children:[],dataset:{},append(...values){this.children.push(...values);},replaceChildren(){this.children=[];},setAttribute(){}});
 const root=node('div'),button=node('button'),input=node('input');
 const ctx=vm.createContext({document:{createElement:node},sessionStorage:saved,root,button,input,uuid:()=>String(++count),BridgeI18n:{t:v=>v},upload:(...args)=>{calls.push(args);return upload(...args);}});
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../web/attachments.js'),'utf8'),ctx);
 const panel=vm.runInContext('new ChatAttachments({root,button,input,upload})',ctx);panel.open('local|one');
 return {panel,calls,saved,root,button,input,setUpload:f=>upload=f};
}
const tick=()=>new Promise(setImmediate),file=(name,size=12)=>({name,size});
test('multi-select uploads independently and switching chats preserves host-scoped drafts',async()=>{
 const ui=fixture();ui.panel.add([file('a.txt'),file('photo.png')]);await tick();
 assert.equal(ui.calls.length,2);assert.equal(ui.panel.ready(),true);assert.equal(ui.panel.ids().length,2);
 ui.panel.open('remote|one');assert.equal(ui.panel.ids().length,0);ui.panel.add([file('remote.txt')]);await tick();
 ui.panel.open('local|one');assert.equal(ui.panel.rows[0].name,'a.txt');assert.equal(ui.panel.rows[1].name,'photo.png');
 assert.equal(ui.calls[2][0],'remote|one');
});
test('failed uploads block send and retry keeps the same attachment id',async()=>{
 const ui=fixture();ui.setUpload(()=>{throw Error('offline');});ui.panel.add([file('a.txt')]);await tick();
 assert.equal(ui.panel.ready(),false);assert.equal(ui.panel.rows[0].status,'failed');
 ui.setUpload(async(key,id,file)=>({id,name:file.name,size:file.size}));
 const card=ui.root.children[0];card.children.find(n=>n.tag==='button'&&n.textContent==='重试').onclick();await tick();
 assert.equal(ui.calls[0][1],ui.calls[1][1]);assert.equal(ui.panel.ready(),true);
});
test('removing a pending upload or logging out prevents delayed results restoring the draft',async()=>{
 const ui=fixture();let finish;ui.setUpload(()=>new Promise(r=>finish=r));ui.panel.add([file('a.txt')]);
 const id=ui.panel.ids()[0];ui.root.children[0].children.at(-1).onclick();finish({id,name:'a.txt',size:12});await tick();assert.equal(ui.panel.rows.length,0);
 ui.panel.add([file('b.txt')]);ui.panel.reset();finish({id:'2',name:'b.txt',size:12});await tick();assert.equal(ui.panel.rows.length,0);
 assert.equal(ui.saved.getItem('attachments:local|one'),'[]');
});
test('limits reject the selection before upload, and send lock prevents changing attachments',async()=>{
 const ui=fixture();ui.panel.add([file('large',21*1024*1024)]);assert.equal(ui.calls.length,0);
 ui.panel.add(Array.from({length:11},(_,i)=>file(String(i))));assert.equal(ui.calls.length,0);
 ui.panel.add([file('ok')]);await tick();ui.panel.setLocked(true);ui.panel.add([file('other')]);assert.equal(ui.calls.length,1);assert.equal(ui.button.disabled,true);
 ui.panel.clear('local|one',ui.panel.ids());assert.equal(ui.panel.rows.length,0);
});
