'use strict';
const timelineText=source=>typeof BridgeI18n==='undefined'?source:BridgeI18n.t(source);

// History and live changes share stable keys, but never share a request queue.
class ChatTimeline {
  constructor({url, request, renderMeta, renderText, status}) {
    Object.assign(this,{url,request,renderMeta,renderText,status});
    this.viewport=document.getElementById('timeline');
    this.container=document.getElementById('messages');
    this.older=document.getElementById('older');
    this.newer=document.getElementById('newer');
    this.abort=new AbortController();this.rows=new Map();this.nodes=new Map();this.details=new Map();
    this.files=new Map();this.sequence=-1;this.epoch='';this.before=null;this.hasMore=false;
    this.busy=false;this.generation=0;this.windowRevision=0;this.following=true;this.lastTop=0;this.retryCount=0;
    this.onScroll=()=>{
      if(this.suppress)return;
      const upward=this.viewport.scrollTop<this.lastTop;
      this.lastTop=this.viewport.scrollTop;this.following=this.atBottom();this.anchor=this.capture();
      if(this.following)this.newer.hidden=true;
      if(upward&&this.nearTop()&&!this.retryCount)this.loadOlder(100);
    };
    this.viewport.addEventListener('scroll',this.onScroll,{passive:true});
    this.older.onclick=()=>this.sequence<0?this.start():this.loadOlder(this.retryCount||100);
    this.newer.onclick=()=>{this.jumpBottom();this.anchor=this.capture();};
    this.resize=new ResizeObserver(()=>this.restore(this.anchor,this.following));
    this.resize.observe(this.container);
  }
  dispose(){this.abort.abort();clearTimeout(this.startRetry);this.resize.disconnect();this.viewport.removeEventListener('scroll',this.onScroll);this.newer.hidden=true;}
  atBottom(){return this.viewport.scrollHeight-this.viewport.scrollTop-this.viewport.clientHeight<110;}
  nearTop(){const nodes=[...this.container.children];return nodes.length>0&&nodes.slice(0,11).some(n=>n.getBoundingClientRect().bottom>=this.viewport.getBoundingClientRect().top);}
  capture(){const top=this.viewport.getBoundingClientRect().top;const node=[...this.container.children].find(n=>n.getBoundingClientRect().bottom>top);return node?{key:node.dataset.key,offset:node.getBoundingClientRect().top-top}:null;}
  jumpBottom(){this.viewport.scrollTop=this.viewport.scrollHeight;this.following=true;this.newer.hidden=true;}
  restore(anchor,bottom=false){
    if(this.abort.signal.aborted)return;
    this.suppress=true;
    const node=anchor&&this.nodes.get(anchor.key)?.node;
    if(bottom)this.jumpBottom();
    else if(node)this.viewport.scrollTop+=node.getBoundingClientRect().top-this.viewport.getBoundingClientRect().top-anchor.offset;
    this.lastTop=this.viewport.scrollTop;
    this.anchor=this.capture();
    requestAnimationFrame(()=>this.suppress=false);
  }
  read(action,params={}){return this.request(this.url(action)+'&'+new URLSearchParams(params),undefined,this.abort.signal);}
  async start(){
    if(this.starting||this.abort.signal.aborted)return;
    clearTimeout(this.startRetry);this.starting=true;
    this.older.hidden=false;this.older.disabled=true;this.older.textContent=timelineText('正在读取最近内容…');
    try{
      const page=await this.read('timeline',{limit:20});
      if(this.abort.signal.aborted)return;
      this.apply(page,true);this.updates();
      // Give the first 20 records a paint before quietly filling the 100-row window.
      requestAnimationFrame(()=>requestAnimationFrame(()=>this.fillRecent()));
    }catch(e){if(this.abort.signal.aborted)return;this.older.disabled=false;this.older.textContent=timelineText('读取失败，点击重试');this.status(e.message);this.startRetry=setTimeout(()=>this.start(),3000);}
    finally{this.starting=false;}
  }
  fillRecent(){if(!this.abort.signal.aborted&&this.rows.size>0&&this.rows.size<100&&!this.retryCount)this.loadOlder(100-this.rows.size);}
  historyStatus(){this.older.hidden=!this.hasMore;this.older.disabled=this.busy;this.older.textContent=this.busy?timelineText('正在读取更早内容…'):this.retryCount?timelineText('历史加载失败，点击重试'):timelineText('查看更早内容');}
  async loadOlder(count){
    if(this.busy||!this.hasMore||this.abort.signal.aborted)return;
    const generation=this.generation,before=this.before;
    this.busy=true;this.retryCount=0;this.historyStatus();
    try{
      const page=await this.read('timeline',{before,limit:count});
      if(this.abort.signal.aborted||generation!==this.generation)return;
      if(page.reset||page.epoch!==this.epoch){this.apply(page,true);return;}
      this.apply(page,false,true);
    }catch(e){if(this.abort.signal.aborted||generation!==this.generation)return;this.retryCount=count;}
    finally{this.busy=false;if(!this.abort.signal.aborted){this.historyStatus();this.fillRecent();}}
  }
  apply(page,reset=false,history=false){
    const anchor=this.capture(),bottom=this.following;
    if(reset){this.generation++;this.rows.clear();this.nodes.clear();this.details.clear();this.files.clear();this.container.replaceChildren();this.epoch=page.epoch;this.retryCount=0;}
    for(const file of page.files||[])this.files.set(file.id,file);
    if((!history&&page.sequence>=this.sequence)||reset){this.sequence=page.sequence;this.renderMeta({...page.meta,files:[...this.files.values()]});}
    if(!history)this.pendingRequests=!!page.meta.requests?.length;
    let changed=false;
    for(const row of page.rows){
      const previous=this.rows.get(row.key);
      if(previous&&previous.sequence>page.sequence)continue;
      this.rows.set(row.key,{row,sequence:page.sequence});
      const stamp=JSON.stringify(row),old=this.nodes.get(row.key);
      if(old?.stamp===stamp)continue;
      changed=true;
      const open=old?.node.tagName==='DETAILS'&&old.node.open;
      const node=this.renderRow(row,open);node.dataset.key=row.key;
      if(old)old.node.replaceWith(node);
      this.nodes.set(row.key,{node,stamp});
    }
    const ordered=[...this.rows.values()].map(x=>x.row).sort((a,b)=>a.order-b.order);
    let position=this.container.firstChild;
    for(const row of ordered){const node=this.nodes.get(row.key).node;if(node===position)position=position.nextSibling;else this.container.insertBefore(node,position);}
    if(reset||history){this.before=page.before;this.hasMore=page.hasMore;this.windowRevision++;}
    this.historyStatus();this.restore(anchor,bottom);
    if(!history&&!bottom&&(changed||page.meta.requests?.length)){this.newer.textContent=page.meta.requests?.length?timelineText('有待确认请求 ↓'):timelineText('有新内容 ↓');this.newer.hidden=false;}
    if(reset)requestAnimationFrame(()=>this.fillRecent());
  }
  renderRow(row,open){
    const activity=row.role==='activity',node=document.createElement(activity?'details':'div');
    node.className=activity?'activity timeline-row':'message timeline-row '+row.role;
    const heading=document.createElement(activity?'summary':'span');heading.className=activity?'':'who';
    heading.textContent=activity?(row.title||row.kind)+(row.status==='inProgress'?timelineText(' · 进行中'):''):row.role==='user'?timelineText('你'):row.role==='error'?timelineText('执行错误'):'CODEX';node.append(heading);
    const body=document.createElement(activity?'pre':'div');body.className=activity?'activity-body':'message-body';
    const more=document.createElement('button');more.className='plain detail-more';more.type='button';
    const cached=this.details.get(row.key);
    if(cached&&cached.version!==row.version)this.details.delete(row.key);
    const paint=()=>{
      const detail=this.details.get(row.key),text=detail?.text??row.text;
      body.replaceChildren();if(activity)body.textContent=text;else this.renderText(body,text,[...this.files.values()]);
      more.hidden=detail?detail.next===null:!row.truncated;
      more.textContent=detail?timelineText('继续加载正文'):timelineText('展开完整内容');
    };
    const load=async()=>{
      if(more.disabled||this.abort.signal.aborted)return;
      const prior=this.details.get(row.key);if(prior?.next===null)return;
      more.disabled=true;more.textContent=timelineText('正在读取…');
      try{
        const detail=await this.read('detail',{key:this.epoch+'.'+row.key,offset:prior?.next||0,version:prior?.version||row.version});
        if(this.abort.signal.aborted||!node.isConnected||this.rows.get(row.key)?.row.version!==detail.version)return;
        for(const file of detail.files||[])this.files.set(file.id,file);
        this.details.set(row.key,{version:detail.version,text:(detail.offset?prior?.text||'':'')+detail.text,next:detail.next});paint();
      }catch(e){if(!this.abort.signal.aborted){more.hidden=false;more.textContent=timelineText('读取失败，点击重试');}}
      finally{more.disabled=false;}
    };
    more.onclick=load;node.append(body,more);paint();
    if(row.attachments?.length){const attachment=document.createElement('small');attachment.className='muted';attachment.textContent=timelineText('附件：')+row.attachments.map(a=>a.name||a.path||a.type).join('、');node.append(attachment);}
    if(activity){node.open=!!open;node.ontoggle=()=>{if(node.open&&!this.details.has(row.key))load();};}
    return node;
  }
  relabel(){
    this.historyStatus();
    for(const [key,entry] of this.nodes){
      const row=this.rows.get(key)?.row;if(!row)continue;
      const node=entry.node,heading=node.firstElementChild;
      if(heading)heading.textContent=row.role==='activity'?(row.title||row.kind)+(row.status==='inProgress'?timelineText(' · 进行中'):''):row.role==='user'?timelineText('你'):row.role==='error'?timelineText('执行错误'):'CODEX';
      const more=node.querySelector('.detail-more');
      if(more&&!more.disabled)more.textContent=timelineText(this.details.has(key)?'继续加载正文':'展开完整内容');
      const attachment=node.querySelector('small.muted');
      if(attachment)attachment.textContent=timelineText('附件：')+row.attachments.map(a=>a.name||a.path||a.type).join('、');
    }
    this.newer.textContent=timelineText(this.pendingRequests?'有待确认请求 ↓':'有新内容 ↓');
  }
  async updates(){
    while(!this.abort.signal.aborted){
      try{
        const generation=this.generation,windowRevision=this.windowRevision;
        const page=await this.read('changes',{after:this.sequence,epoch:this.epoch,start:this.before||''});
        if(this.abort.signal.aborted)return;
        if(generation!==this.generation||windowRevision!==this.windowRevision)continue;
        // History may have expanded the window while this poll was in flight.
        // Polls never replace the oldest loaded cursor.
        this.apply(page,!!page.reset);this.fillRecent();
        await this.pause(200);
      }catch(e){if(this.abort.signal.aborted)return;this.status(timelineText('重新连接中'));await this.pause(3000);}
    }
  }
  pause(ms){return new Promise(resolve=>{const done=()=>{clearTimeout(timer);this.abort.signal.removeEventListener('abort',done);resolve();};const timer=setTimeout(done,ms);this.abort.signal.addEventListener('abort',done,{once:true});});}
}
