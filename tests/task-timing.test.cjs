'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const context=vm.createContext({});
const source=fs.readFileSync(path.join(__dirname,'../web/features/chat/timeline.js'),'utf8');
vm.runInContext(source,context);
const text=timing=>context.taskTimingText(timing);
test('native task duration is rendered as a final minutes and seconds footer',()=>{
  assert.equal(text({status:'completed',durationMs:118999}),'用时 1分钟 58秒');
  assert.equal(text({status:'completed',durationMs:3661000}),'用时 1小时 1分钟 1秒');
  assert.equal(text({status:'failed',durationMs:0}),'用时 0秒');
  assert.equal(text({status:'interrupted',durationMs:1200}),'用时 1秒');
});
test('running clock is retained while missing timing has no placeholder',()=>{
  assert.equal(context.taskTimingText({status:'inProgress',startedAtMs:1000},true,100000),'执行中 · 1:39');
  assert.equal(context.taskTimingText({status:'inProgress',startedAtMs:1000},false,100000),'');
  for(const timing of [null,{status:'inProgress',durationMs:118000},{status:'completed',startedAtMs:1000},{status:'completed',durationMs:NaN},{status:'completed',durationMs:-1}])assert.equal(text(timing),'');
});
test('the same footer supports English',()=>{
  const english=vm.createContext({BridgeI18n:{t:value=>({'用时':'Time taken','小时':'h','分钟':'m','秒':'s'}[value]||value)}});
  vm.runInContext(source,english);
  assert.equal(english.taskTimingText({status:'completed',durationMs:118000}),'Time taken 1m 58s');
});
test('running summary stops ticking and disappears at completion and disposal',()=>{
  const scheduled=[],cleared=[];
  const clock=vm.createContext({setTimeout:fn=>{scheduled.push(fn);return scheduled.length;},clearTimeout:id=>cleared.push(id)});
  vm.runInContext(source,clock);
  const Timeline=vm.runInContext('ChatTimeline',clock),summary={remove(){this.removed=true;}};
  const state={timingSummary:summary,nodes:new Map(),meta:{connected:true,taskTiming:{status:'inProgress',startedAtMs:Date.now()-2000}},abort:{signal:{aborted:false}},refreshTimings:Timeline.prototype.refreshTimings};
  state.refreshTimings();assert.match(summary.textContent,/执行中/);assert.equal(scheduled.length,1);
  state.meta={connected:true};state.refreshTimings();assert.equal(summary.textContent,'');assert.equal(summary.hidden,true);assert.equal(scheduled.length,1);assert.ok(cleared.includes(1));
  Object.assign(state,{abort:{abort(){state.abort.signal.aborted=true;},signal:{aborted:false}},resize:{disconnect(){}},viewport:{removeEventListener(){}},newer:{}});
  Timeline.prototype.dispose.call(state);assert.equal(summary.removed,true);
});
