'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
function fixture(saved=new Map()){
  function node(){return {value:'',textContent:'',disabled:false,hidden:false,children:[],get firstElementChild(){return this.children[0];},append(...v){this.children.push(...v);},replaceChildren(...v){this.children=v;},setAttribute(key,value){this[key]=value;},querySelector(selector){return this.children.find(child=>'.'+child.className===selector);},focus(){this.focused=true;}};}
  const select=node(),hint=node(),goalRoot=node(),goalOption=node(),goalToggle=node();
  select.querySelector=()=>goalOption;
  const storage={getItem:key=>saved.get(key)||null,setItem:(key,value)=>saved.set(key,value),removeItem:key=>saved.delete(key)};
  const ctx=vm.createContext({select,hint,goalRoot,goalToggle,storage,localStorage:storage,window:{},document:{createElement:node}});
  for(const name of ['i18n.js','modes.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,'../web',name),'utf8'),ctx);
  const panel=vm.runInContext('new WorkModes({select,hint,goalRoot,goalToggle,storage})',ctx);
  const all=n=>[n,...n.children.flatMap(all)];
  const text=()=>all(goalRoot).map(n=>n.textContent).join('\n');
  return {panel,select,goalOption,hint,goalRoot,goalToggle,text,saved,language:()=>vm.runInContext("BridgeI18n.setLanguage('en')",ctx)};
}
const idle={status:'idle',collaborationMode:'default',connected:true};
test('mode follows desktop until explicitly chosen and is isolated by host/thread',()=>{
  const ui=fixture();ui.panel.open('local|a');ui.panel.render({...idle,collaborationMode:'plan'},'send',false);
  assert.equal(ui.select.value,'plan');ui.select.value='goal';ui.select.onchange();
  ui.panel.render(idle);assert.equal(ui.select.value,'goal');
  ui.panel.open('remote|a');ui.panel.render(idle);assert.equal(ui.select.value,'default');
  ui.panel.open('local|a');ui.panel.render(idle);assert.equal(ui.select.value,'goal');
});
test('steering cannot switch mode and unfinished goals prevent another goal',()=>{
  const ui=fixture();ui.panel.open('local|a');ui.panel.render({...idle,status:'active'},'steer',false);
  assert.equal(ui.select.disabled,true);assert.equal(ui.panel.value('steer'),null);assert.equal(ui.goalOption.disabled,true);
  ui.panel.render({...idle,goal:{objective:'Work',status:'paused'}},'send',false);assert.equal(ui.goalOption.disabled,true);
  ui.panel.render({...idle,goal:{objective:'Work',status:'complete'}});assert.equal(ui.goalOption.disabled,false);
});
test('submitted request stays pending until a real goal appears; failure is explicit',()=>{
  const ui=fixture();ui.panel.open('local|a');ui.panel.render({...idle,goalSubmission:{status:'pending'}},'send',false);
  assert.match(ui.text(),/等待桌面确认/);assert.doesNotMatch(ui.text(),/目标进行中/);
  ui.panel.render({...idle,goalSubmission:{status:'unconfirmed'}});assert.match(ui.text(),/尚未确认/);
  ui.panel.render({...idle,goal:{objective:'Original goal',status:'active',tokensUsed:120,tokenBudget:500}});
  assert.match(ui.text(),/Original goal/);assert.match(ui.text(),/120 \/ 500/);assert.match(ui.text(),/目标进行中/);
  ui.goalRoot.firstElementChild.open=true;
  ui.language();ui.panel.render();assert.match(ui.text(),/Goal active/);assert.match(ui.text(),/Original goal/);
  assert.equal(ui.goalRoot.firstElementChild.open,true);
});
test('goal selection resets only after accepted submission and never alters another chat',()=>{
  const ui=fixture();ui.panel.open('local|a');ui.select.value='goal';ui.select.onchange();ui.panel.render(idle,'send',true);
  assert.equal(ui.select.disabled,true);assert.equal(ui.panel.value('send'),'goal');
  ui.panel.open('local|b');ui.select.value='plan';ui.select.onchange();
  ui.panel.submitted('local|a','goal');assert.equal(ui.select.value,'plan');
  ui.panel.open('local|a');ui.panel.render({...idle,collaborationMode:'plan'},'send',false);assert.equal(ui.select.value,'default');
});
test('reopening while state loads clears previous goal and keeps controls disabled',()=>{
  const ui=fixture();ui.panel.open('local|a');ui.panel.render({...idle,goal:{objective:'Private A',status:'active'}},'send',false);
  ui.panel.open('local|b');assert.equal(ui.select.disabled,true);assert.equal(ui.goalRoot.hidden,true);assert.doesNotMatch(ui.text(),/Private A/);
});
test('hiding persists for the same goal across refresh and status updates, and restoring keeps the goal intact',()=>{
  const goal={createdAt:100,objective:'Keep working',status:'active'},view={...idle,goal};
  const ui=fixture();ui.panel.open('local|a');ui.panel.render(view,'send',false);
  ui.goalRoot.querySelector('.goal-close').onclick();
  assert.equal(ui.goalRoot.hidden,true);assert.equal(ui.goalToggle.hidden,false);assert.equal(goal.status,'active');
  const refreshed=fixture(ui.saved);refreshed.panel.open('local|a');refreshed.panel.render({...view,goal:{...goal,status:'complete',tokensUsed:123}});
  assert.equal(refreshed.goalRoot.hidden,true);assert.equal(refreshed.goalToggle.hidden,false);
  refreshed.goalToggle.onclick();assert.equal(refreshed.goalRoot.hidden,false);assert.equal(refreshed.goalToggle.hidden,true);
  assert.equal(refreshed.goalRoot.querySelector('.goal-close').focused,true);
});
test('a new goal is visible, and hidden preferences do not leak across hosts or chats',()=>{
  const ui=fixture(),goal={createdAt:100,objective:'Same objective',status:'active'};
  ui.panel.open('local|a');ui.panel.render({...idle,goal});ui.goalRoot.querySelector('.goal-close').onclick();
  for(const key of ['remote|a','local|b']){ui.panel.open(key);ui.panel.render({...idle,goal});assert.equal(ui.goalRoot.hidden,false);}
  ui.panel.open('local|a');ui.panel.render({...idle,goal:{...goal,createdAt:200}});assert.equal(ui.goalRoot.hidden,false);
  ui.panel.render({...idle,goal:null});assert.equal(ui.goalToggle.hidden,true);assert.equal(ui.goalRoot.hidden,true);
});
