'use strict';
const $=id=>document.getElementById(id),api=window.bridgeDesktop,t=BridgeI18n.t;
let connectionDraft=[],savedConnections='[]';
let snapshot,dirty=false,loading=false,startingUntil=0,activeTab='overview',savedFields={},feedbackKind='';
const busyActions=new Set();
const titles={overview:t('连接与状态'),network:t('网络与登录'),notifications:t('手机通知'),advanced:t('运行配置'),logs:t('运行日志')};
function fields(){return [...$('settings').querySelectorAll('input,textarea,select')].filter(node=>!node.closest('#connections')&&node.id!=='connection-kind');}
function fieldValues(){return Object.fromEntries(fields().map(node=>[node.id,node.type==='checkbox'?node.checked:node.value]));}
function updateDirty(){
  const changed=new Set();
  for(const node of fields())if((node.type==='checkbox'?node.checked:node.value)!==savedFields[node.id])changed.add(node.closest('[data-panel]').dataset.panel);
  if(JSON.stringify(connectionDraft)!==savedConnections)changed.add('network');
  dirty=changed.size>0;
  for(const button of document.querySelectorAll('[data-tab]')){
    const unsaved=changed.has(button.dataset.tab);
    button.dataset.dirty=String(unsaved);
    button.setAttribute('aria-label',t(titles[button.dataset.tab])+(unsaved?t('，有未保存的修改'):''));
  }
  $('dirty-dot').hidden=!dirty;
  $('dirty-label').textContent=dirty?t('有未保存的修改'):t('配置已保存');
  $('save-bar').hidden=!dirty&&!['network','notifications','advanced'].includes(activeTab);
  document.querySelectorAll('[data-connection-action]').forEach(button=>button.disabled=dirty||busyActions.has(button.dataset.key)||!connectionDraft.find(c=>c.id===button.dataset.connection)?.enabled);
}
function feedback(text,error=false,kind=''){text=t(text);feedbackKind=kind;$('error').hidden=!error;$('feedback').hidden=error;const target=$(error?'error':'feedback');if(target.textContent!==text)target.textContent=text;}
function tab(name){activeTab=name;document.querySelectorAll('[data-panel]').forEach(node=>node.hidden=node.dataset.panel!==name);document.querySelectorAll('[data-tab]').forEach(node=>node.classList.toggle('active',node.dataset.tab===name));$('page-title').textContent=t(titles[name]);if(snapshot)updateDirty();if(name==='logs')loadLogs();}
document.querySelectorAll('[data-tab]').forEach(button=>button.onclick=()=>tab(button.dataset.tab));
document.querySelectorAll('[data-jump]').forEach(button=>button.onclick=()=>tab(button.dataset.jump));
$('settings').oninput=$('settings').onchange=()=>{if(snapshot){updateDirty();}};
function input(id,value){$(id).value=value??'';}
function render(value){
  snapshot=value;const running=value.runtime.running;
  if(running||value.runtime.portOccupied)startingUntil=0;
  const starting=Date.now()<startingUntil;
  if(feedbackKind==='gateway'){
    if(running)feedback(t('网关已启动，正在运行。'),false,'gateway');
    else if(value.runtime.portOccupied)feedback(t('端口已被其他网关占用，请检查运行配置。'),true,'gateway');
    else if(starting)feedback(t('正在启动网关'),false,'gateway');
    else if(startingUntil)feedback(t('网关启动超时，请查看运行日志。'),true,'gateway');
    else feedback(t('网关已停止'),false,'gateway');
  }
  $('status').textContent=running?t('运行中'):starting?t('启动中'):value.runtime.portOccupied?t('端口已占用'):t('未启动');
  $('status').classList.toggle('running',running);$('sidebar-status').textContent=$('status').textContent;
  $('start').disabled=running||starting;$('stop').disabled=!running;
  $('login-summary').textContent=value.auth.mode==='none'?t('免密访问'):t('账号：')+value.auth.username;
  $('credentials').disabled=!value.credentialsAvailable;
  $('notification-summary').textContent=value.notifications.enabled?t('已开启 · ')+value.watches.length+t(' 个关注聊天'):t('未开启');
  $('notification-state').textContent=value.runtime.running&&!value.runtime.supportsNotifications?t('当前网关版本较旧，重启后启用通知能力。'):t(value.notificationStatus.error)||t('手机关闭网页后，已关注聊天仍会继续提醒。');
  $('notification-detail').textContent=t(value.notificationStatus.error)||(value.notificationStatus.lastSent?t('最近一次发送：')+new Date(value.notificationStatus.lastSent*1000).toLocaleString(BridgeI18n.locale()):t('尚无发送记录'));
  $('notification-readiness').textContent=!value.notifications.enabled?t('尚未开启手机通知：填写并保存后，先发送测试通知。'):!running?t('网关尚未启动：可以先测试 ntfy 接收，聊天提醒需要启动网关。'):!value.runtime.supportsNotifications?t('当前网关版本不支持聊天提醒，请在首页停止后重新启动网关，再刷新手机网页。'):t('网关已就绪：在手机打开一个已连接的聊天，点击“提醒”，直到显示“提醒已开”。');
  $('data-dir').textContent=value.dataDir;
  $('addresses').replaceChildren();
  for(const url of value.urls){const card=document.createElement('div');card.className='address';const text=document.createElement('div'),label=document.createElement('small'),address=document.createElement('strong');const fixed=(value.preferences.connections||[]).some(c=>c.enabled&&url===c.publicUrl+'/');label.textContent=(fixed?t('固定 HTTPS · 需完成服务器部署'):url.startsWith('https:')?t('临时外网 HTTPS'):url.includes('127.0.0.1')?t('此电脑'):t('局域网'))+(running?'':t(' · 网关未启动'));address.textContent=url;text.append(label,address);card.append(text);for(const [name,action] of [[t('复制'),()=>api.copy(url)],[t('打开'),()=>api.open(url)]]){const button=document.createElement('button');button.textContent=name;button.onclick=()=>action().catch(e=>feedback(e.message,true));card.append(button);}$('addresses').append(card);}
  $('watches').replaceChildren();if(!value.watches.length)$('watches').textContent=t('暂无关注聊天。请在手机打开聊天并开启提醒。');
  for(const watch of value.watches){const row=document.createElement('div');row.textContent=(watch.host==='local'?t('此电脑'):watch.host)+' · '+watch.id;$('watches').append(row);}
  if(!dirty){
    const p=value.preferences,n=value.notifications;
    for(const [id,key] of [['port','port'],['cloudflared','cloudflared'],['codex-home','codexHome'],['ipc-path','ipcPath'],['codex-bin','codexBin']])input(id,p[key]);
    $('auto-start').checked=p.autoStart;$('lan').checked=p.lan;
    connectionDraft=JSON.parse(JSON.stringify(p.connections||[]));savedConnections=JSON.stringify(connectionDraft);renderConnections();
    const fixed=connectionDraft.filter(c=>c.enabled).map(c=>c.publicUrl);
    input('origins',value.origins.filter(o=>!fixed.includes(o)).join('\n'));input('auth-mode',value.auth.mode);input('username',value.auth.username);
    $('ntfy-enabled').checked=n.enabled;input('ntfy-server',n.server);input('ntfy-topic',n.topic);input('click-base',n.clickBase);$('include-title').checked=n.includeTitle;
    savedFields=fieldValues();
  }
  $('ntfy-token').placeholder=value.notifications.hasToken?t('已保存；留空保留，服务地址变化时清除'):t('如服务需要认证，在这里填写');
  updateDirty();
  $('network-lock').hidden=!running;
  for(const id of ['lan','port','connection-kind','add-connection','origins','cloudflared','codex-home','ipc-path','codex-bin'])$(id).disabled=running;
  document.querySelectorAll('[data-connection-field],[data-remove-connection]').forEach(node=>node.disabled=running);
  for(const node of document.querySelectorAll('[data-connection-status]'))node.textContent=t(value.externalStatus?.[node.dataset.connectionStatus]?.message||(!running?t('网关未启动。可先保存配置并导出部署包。'):t('网关正在运行；固定入口是否可用，请点击检测。')));
  document.querySelectorAll('[data-pick]').forEach(button=>button.disabled=running);
}
async function refresh(){if(loading)return;loading=true;try{render(await api.snapshot());}catch(e){feedback(e.message,true);}finally{loading=false;}}
function collect(){return {preferences:{autoStart:$('auto-start').checked,port:Number($('port').value),lan:$('lan').checked,connections:connectionDraft,cloudflared:$('cloudflared').value.trim(),codexHome:$('codex-home').value.trim(),ipcPath:$('ipc-path').value.trim(),codexBin:$('codex-bin').value.trim()},auth:{mode:$('auth-mode').value,username:$('username').value.trim(),password:$('password').value},origins:$('origins').value.split('\n').map(s=>s.trim()).filter(Boolean),notifications:{enabled:$('ntfy-enabled').checked,server:$('ntfy-server').value.trim(),topic:$('ntfy-topic').value.trim(),token:$('ntfy-token').value,clearToken:$('clear-token').checked,clickBase:$('click-base').value.trim(),includeTitle:$('include-title').checked}};}
$('settings').onsubmit=async event=>{
  event.preventDefault();$('save').disabled=true;const submitted=fieldValues(),submittedConnections=JSON.stringify(connectionDraft);
  try{
    const value=await api.save(collect());savedFields={...submitted};savedConnections=submittedConnections;
    // Edits made while saving must remain visibly unsaved.
    for(const id of ['password','ntfy-token']){if($(id).value===submitted[id])input(id,'');savedFields[id]='';}
    if($('clear-token').checked===submitted['clear-token'])$('clear-token').checked=false;
    savedFields['clear-token']=false;updateDirty();render(value);
    feedback(dirty?t('已保存提交的配置，仍有新修改待保存。'):t('配置已保存。通知设置由新版网关自动读取，登录设置在下次启动生效。'));
  }catch(e){feedback(e.message,true);}finally{$('save').disabled=false;}
};
$('start').onclick=async()=>{if(dirty){feedback(t('请先保存配置，再启动网关。'),true);return;}$('start').disabled=true;try{const result=await api.start();startingUntil=result.started?Date.now()+70000:0;feedback(result.message,false,'gateway');await refresh();}catch(e){startingUntil=0;feedback(e.message,true);$('start').disabled=false;}};
$('stop').onclick=async()=>{$('stop').disabled=true;try{feedback((await api.stop()).message,false,'gateway');startingUntil=0;await refresh();}catch(e){feedback(e.message,true);$('stop').disabled=false;}};
$('test-notification').onclick=async()=>{if(dirty){feedback(t('请先保存 ntfy 配置，再发送测试通知。'),true);return;}$('test-notification').disabled=true;try{feedback((await api.testNotification()).message);}catch(e){feedback(e.message,true);}finally{$('test-notification').disabled=false;}};
$('ntfy-help').onclick=()=>api.open('ntfy-help').catch(e=>feedback(e.message,true));
$('generate-topic').onclick=()=>{input('ntfy-topic','codex-'+crypto.randomUUID().replaceAll('-',''));updateDirty();};
$('credentials').onclick=()=>api.open('credentials').catch(e=>feedback(e.message,true));
$('open-data').onclick=()=>api.open('data').catch(e=>feedback(e.message,true));
$('choose-data').onclick=async()=>{try{if(await api.choose('data')){dirty=false;await refresh();feedback(t('已切换网关目录，原来的网关进程继续运行。'));}}catch(e){feedback(e.message,true);}};
for(const button of document.querySelectorAll('[data-pick]'))button.onclick=async()=>{try{const selected=await api.choose(button.dataset.kind);if(selected){input(button.dataset.pick,selected);updateDirty();}}catch(e){feedback(e.message,true);}};
async function loadLogs(){try{$('log-output').textContent=(await api.logs()).text||t('暂无运行日志');$('log-output').scrollTop=0;}catch(e){feedback(e.message,true);}}
$('refresh-logs').onclick=loadLogs;
refresh().then(()=>{if(snapshot?.preferences.autoStart&&!snapshot.runtime.running&&!snapshot.runtime.portOccupied)$('start').click();});setInterval(refresh,3000);

$('language').value=BridgeI18n.language();
$('language').onchange=()=>{BridgeI18n.setLanguage($('language').value);BridgeI18n.apply();for(const id of ['feedback','error'])$(id).textContent=t($(id).textContent);if(snapshot){render(snapshot);renderConnections();updateDirty();}tab(activeTab);};
BridgeI18n.apply();
$('add-connection').onclick=addConnection;
