'use strict';

class WorkModes {
  constructor({select,hint,goalRoot,goalToggle,storage=sessionStorage}) {
    this.select=select;this.hint=hint;this.goalRoot=goalRoot;this.goalToggle=goalToggle;this.storage=storage;
    this.key=null;this.selection=null;this.view=null;
    select.onchange=()=>{this.selection=select.value;this.storage.setItem('work-mode:'+this.key,this.selection);this.render();};
    goalToggle.onclick=()=>{this.storage.removeItem('hidden-goal:'+this.key);this.render();this.goalRoot.querySelector('.goal-close')?.focus();};
  }
  open(key) {
    this.key=key;const saved=this.storage.getItem('work-mode:'+key);
    this.selection=['default','plan','goal'].includes(saved)?saved:null;
    this.view=null;this.render();
  }
  value(sendMode) {return sendMode==='steer'?null:this.select.value;}
  submitted(key,mode) {
    if(mode!=='goal')return;
    this.storage.setItem('work-mode:'+key,'default');
    if(key===this.key){this.selection='default';this.render();}
  }
  render(view=this.view,sendMode=this.sendMode,busy=this.busy) {
    this.view=view;this.sendMode=sendMode;this.busy=busy;
    const t=BridgeI18n.t;
    this.select.value=this.selection||(view?.collaborationMode==='plan'?'plan':'default');
    this.select.disabled=!view||view.loadingHistory||view.activating||busy||sendMode==='steer';
    const goal=view?.goal,request=view?.goalSubmission;
    this.select.querySelector('[value=goal]').disabled=!!(view?.status==='active'||(goal&&goal.status!=='complete')||['pending','unknown'].includes(request?.status));
    this.hint.textContent=t(sendMode==='steer'?'补充内容沿用当前任务模式':this.select.value==='plan'?'先讨论并制定计划，确认后执行。':this.select.value==='goal'?'设定目标后持续执行，直到完成或暂停。':'');
    this.hint.hidden=!this.hint.textContent;
    this.select.title=this.hint.textContent;
    const goalIdentity=request?JSON.stringify(['request',request.id]):goal?JSON.stringify(['goal',goal.createdAt,goal.objective]):null;
    const dismissed=!!goalIdentity&&this.storage.getItem('hidden-goal:'+this.key)===goalIdentity;
    this.goalToggle.hidden=!dismissed;
    const expanded=this.goalRoot.firstElementChild?.open||false;
    this.goalRoot.replaceChildren();this.goalRoot.hidden=!goalIdentity||dismissed;
    const node=(tag,text,cls)=>{const el=document.createElement(tag);el.textContent=text;if(cls)el.className=cls;return el;};
    if(goal){
      const labels={active:'目标进行中',paused:'目标已暂停',blocked:'目标等待处理',usageLimited:'目标已达到用量限制',budgetLimited:'目标已达到预算限制',complete:'目标已完成'};
      const details=document.createElement('details');details.open=expanded;
      details.append(node('summary',t(labels[goal.status]||'目标状态')));
      details.append(node('p',goal.objective));
      if(Number.isFinite(goal.tokensUsed))details.append(node('p',t('已用 Token：')+goal.tokensUsed.toLocaleString()+(Number.isFinite(goal.tokenBudget)?' / '+goal.tokenBudget.toLocaleString():''),'muted'));
      this.goalRoot.append(details);
    }
    if(request){
      const labels={pending:'正在开启目标，等待桌面确认…',unknown:'目标请求送达结果尚未确认，请查看会话。',unconfirmed:'尚未确认目标已开启，请查看 Codex 的回复。'};
      this.goalRoot.append(node('p',t(labels[request.status]||labels.unknown),'goal-pending'));
    }
    if(goalIdentity){
      const close=node('button','×','goal-close');close.type='button';close.title=t('隐藏目标栏');close.setAttribute('aria-label',close.title);
      close.onclick=()=>{this.storage.setItem('hidden-goal:'+this.key,goalIdentity);this.render();this.goalToggle.focus();};
      this.goalRoot.append(close);
    }
  }
}

if(typeof module!=='undefined')module.exports={WorkModes};
