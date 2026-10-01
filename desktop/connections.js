'use strict';
function connectionLabel(mode){return t({quick:t('临时 HTTPS · Cloudflare'),server:t('固定域名 · 自有服务器 + SSH'),nas:t('固定域名 · NAS / 已有反代 / Docker')}[mode]);}
function renderConnections(){
  $('connections').replaceChildren();
  if(!connectionDraft.length){const empty=document.createElement('p');empty.className='hint';empty.textContent=t('尚未添加外网连接。局域网可独立使用。');$('connections').append(empty);}
  for(const row of connectionDraft){
    const card=document.createElement('section');card.className='connection-card';
    const head=document.createElement('div');head.className='section-head';
    const heading=document.createElement('h3');heading.textContent=connectionLabel(row.accessMode);
    const remove=document.createElement('button');remove.type='button';remove.textContent=t('删除');remove.dataset.removeConnection=row.id;remove.disabled=!!snapshot?.runtime.running;
    remove.onclick=()=>{connectionDraft=connectionDraft.filter(c=>c.id!==row.id);renderConnections();updateDirty();};head.append(heading,remove);card.append(head);
    function field(key,label,type='text',placeholder=''){
      const wrap=document.createElement('label'),text=document.createElement('span'),node=document.createElement('input');text.textContent=t(label);node.type=type;node.dataset.connectionField=key;
      node.id='connection-'+row.id+'-'+key;node.placeholder=t(placeholder);node.disabled=!!snapshot?.runtime.running;
      if(type==='checkbox'){wrap.className='check';node.checked=row[key];}else node.value=row[key]??'';
      if(type==='number'){node.min=1024;node.max=65535;}
      if(key==='name')node.maxLength=100;
      node.oninput=()=>{row[key]=type==='checkbox'?node.checked:type==='number'?Number(node.value):node.value;updateDirty();};
      node.onchange=()=>{node.oninput();if(key==='enabled')renderConnections();};
      if(type==='checkbox')wrap.append(node,text);else wrap.append(text,node);card.append(wrap);
    }
    field('enabled',t('启用此连接'),'checkbox');field('name',t('连接名称'),'text',t('可选，例如家中 NAS'));
    const hint=document.createElement('p');hint.className='hint';
    if(row.accessMode==='quick')hint.textContent=t('启动网关时自动建立临时 HTTPS 隧道，需要在运行配置中选择 cloudflared。临时地址可能变化；同一网关只需一个临时入口，可与固定入口同时启用。');
    else{
      field('publicUrl',t('手机访问地址'),'text','https://codex.example.com');
      if(row.accessMode==='server'){
        field('sshTarget',t('服务器 SSH 目标'),'text',t('已有 SSH 别名，或 user@server.example.com'));field('sshRemotePort',t('服务器回环端口'),'number');
        hint.textContent=t('电脑主动通过 SSH 连接服务器，适合校园网。使用现有 SSH 密钥或 ssh-agent；首次连接须在终端核对主机指纹。同一服务器的不同配置使用不同回环端口。');
      }else{
        field('proxyUpstream',t('NAS 可访问的电脑地址'),'text','http://192.168.1.10:'+($('port').value||8787));
        hint.textContent=t('填写 NAS 可达的电脑 HTTP 地址，端口与网关一致。需要开启局域网访问；两端网络不通时，先建立路由或使用自有服务器 + SSH。');
      }
      const guide=document.createElement('p');guide.className='hint';guide.textContent=t('首次使用：保存配置 → 导出部署包或复制给部署 Agent → 在服务器 / NAS 完成部署 → 启动网关并检测入口。');card.append(guide);
      const actions=document.createElement('div');actions.className='actions';
      for(const [label,method] of [[t('导出部署包…'),'exportDeployment'],[t('复制给部署 Agent'),'copyDeployment'],[t('检测固定入口'),'checkEntry']]){
        const button=document.createElement('button');button.type='button';button.textContent=t(label);button.dataset.connectionAction=method;button.dataset.connection=row.id;button.dataset.key=row.id+':'+method;
        button.onclick=async()=>{
          const key=button.dataset.key;if(dirty||busyActions.has(key))return;busyActions.add(key);updateDirty();
          try{const result=await api[method]({id:row.id,language:BridgeI18n.language()});if(result?.message)feedback(result.message);}catch(e){feedback(e.message,true);}finally{busyActions.delete(key);updateDirty();}
        };actions.append(button);
      }card.append(actions);
      const status=document.createElement('p');status.className='hint';status.dataset.connectionStatus=row.id;status.textContent=t(snapshot?.externalStatus?.[row.id]?.message||t('保存配置后可检测入口。'));card.append(status);
    }
    card.append(hint);$('connections').append(card);
  }
  BridgeI18n.apply();
}
function addConnection(){
  if(snapshot?.runtime.running)return;
  connectionDraft.push({id:crypto.randomUUID(),name:'',enabled:true,accessMode:$('connection-kind').value,publicUrl:'',sshTarget:'',sshRemotePort:18787,proxyUpstream:''});
  renderConnections();updateDirty();
}
