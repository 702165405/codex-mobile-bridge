'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const context=vm.createContext({});
vm.runInContext(fs.readFileSync(path.join(__dirname,'../web/features/chat/timeline.js'),'utf8'),context);
const text=(timing,connected=true,now=100000)=>context.taskTimingText(timing,connected,now);
test('task duration uses source time, formats hours and stops at terminal duration',()=>{
  assert.equal(text({status:'inProgress',startedAtMs:1000}),'执行中 · 1:39');
  assert.equal(text({status:'completed',durationMs:3661000}),'耗时 · 1:01:01');
  assert.equal(text({status:'failed',durationMs:0}),'耗时 · 0:00');
  assert.equal(text({status:'interrupted',durationMs:1200},true,999999),'耗时 · 0:01');
});
test('missing, invalid, future and disconnected timing never starts an invented clock',()=>{
  assert.equal(text(null),'');
  assert.equal(text({status:'inProgress'}),'执行时长暂未提供');
  assert.equal(text({status:'inProgress',startedAtMs:200000}),'执行时长暂未提供');
  assert.equal(text({status:'completed',startedAtMs:1000}),'执行时长暂未提供');
  assert.equal(text({status:'completed',durationMs:NaN}),'执行时长暂未提供');
  assert.equal(text({status:'inProgress',startedAtMs:1000},false),'执行时长待同步');
});
test('summary keeps running time visible without the opening message and timers stop on completion or dispose',()=>{
  const scheduled=[],cleared=[];
  const clock=vm.createContext({setTimeout:fn=>{scheduled.push(fn);return scheduled.length;},clearTimeout:id=>cleared.push(id)});
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../web/features/chat/timeline.js'),'utf8'),clock);
  const Timeline=vm.runInContext('ChatTimeline',clock);
  const summary={remove(){this.removed=true;}},state={timingSummary:summary,nodes:new Map(),meta:{connected:true,taskTiming:{status:'inProgress',startedAtMs:Date.now()-2000}},abort:{signal:{aborted:false}},refreshTimings:Timeline.prototype.refreshTimings};
  state.refreshTimings();assert.match(summary.textContent,/执行中/);assert.equal(scheduled.length,1);
  state.meta.taskTiming={status:'completed',durationMs:62500};state.refreshTimings();
  assert.equal(summary.textContent,'耗时 · 1:02');assert.equal(scheduled.length,1);assert.ok(cleared.includes(1));
  Object.assign(state,{abort:{abort(){state.abort.signal.aborted=true;},signal:{aborted:false}},resize:{disconnect(){}},viewport:{removeEventListener(){}},newer:{}});
  Timeline.prototype.dispose.call(state);assert.equal(summary.removed,true);assert.equal(state.abort.signal.aborted,true);
});
