'use strict';
const $=id=>document.getElementById(id),api=window.bridgeDesktop;
let snapshot,dirty=false,loading=false,startingUntil=0;
const titles={overview:'连接与状态',network:'网络与登录',notifications:'手机通知',advanced:'运行配置',logs:'运行日志'};
function feedback(text,error=false){$('error').hidden=!error;$('feedback').hidden=error;$(error?'error':'feedback').textContent=text;}
function tab(name){document.querySelectorAll('[data-panel]').forEach(node=>node.hidden=node.dataset.panel!==name);document.querySelectorAll('[data-tab]').forEach(node=>node.classList.toggle('active',node.dataset.tab===name));$('page-title').textContent=titles[name];$('save-bar').hidden=!['network','notifications','advanced'].includes(name);if(name==='logs')loadLogs();}
document.querySelectorAll('[data-tab]').forEach(button=>button.onclick=()=>tab(button.dataset.tab));
document.querySelectorAll('[data-jump]').forEach(button=>button.onclick=()=>tab(button.dataset.jump));
$('settings').oninput=()=>{dirty=true;$('dirty-label').textContent='有未保存的修改';};
function input(id,value){$(id).value=value??'';}
function render(value){
  snapshot=value;const running=value.runtime.running;
  if(running)startingUntil=0;
  const starting=Date.now()<startingUntil;
  $('status').textContent=running?'运行中':starting?'启动中':value.runtime.portOccupied?'端口已占用':'未启动';
  $('status').classList.toggle('running',running);$('sidebar-status').textContent=$('status').textContent;
  $('start').disabled=running||starting;$('stop').disabled=!running;
  $('login-summary').textContent=value.auth.mode==='none'?'免密访问':'账号：'+value.auth.username;
  $('credentials').disabled=!value.credentialsAvailable;
  $('notification-summary').textContent=value.notifications.enabled?'已开启 · '+value.watches.length+' 个关注聊天':'未开启';
  $('notification-state').textContent=value.runtime.running&&!value.runtime.supportsNotifications?'当前网关版本较旧，重启后启用通知能力。':value.notificationStatus.error||'手机关闭网页后，已关注聊天仍会继续提醒。';
  $('notification-detail').textContent=value.notificationStatus.error||(value.notificationStatus.lastSent?'最近一次发送：'+new Date(value.notificationStatus.lastSent*1000).toLocaleString():'尚无发送记录');
  $('data-dir').textContent=value.dataDir;
  $('addresses').replaceChildren();
  for(const url of value.urls){const card=document.createElement('div');card.className='address';const text=document.createElement('div'),label=document.createElement('small'),address=document.createElement('strong');label.textContent=(url.startsWith('https:')?'外网 HTTPS':url.includes('127.0.0.1')?'此电脑':'局域网')+(running?'':' · 启动后可用');address.textContent=url;text.append(label,address);card.append(text);for(const [name,action] of [['复制',()=>api.copy(url)],['打开',()=>api.open(url)]]){const button=document.createElement('button');button.textContent=name;button.onclick=()=>action().catch(e=>feedback(e.message,true));card.append(button);}$('addresses').append(card);}
  $('watches').replaceChildren();if(!value.watches.length)$('watches').textContent='暂无关注聊天。请在手机打开聊天并开启提醒。';
  for(const watch of value.watches){const row=document.createElement('div');row.textContent=(watch.host==='local'?'此电脑':watch.host)+' · '+watch.id;$('watches').append(row);}
  if(!dirty){
    const p=value.preferences,n=value.notifications;
    for(const [id,key] of [['port','port'],['cloudflared','cloudflared'],['codex-home','codexHome'],['ipc-path','ipcPath'],['codex-bin','codexBin']])input(id,p[key]);
    $('auto-start').checked=p.autoStart;$('lan').checked=p.lan;$('tunnel').checked=p.tunnel;input('origins',value.origins.join('\n'));input('auth-mode',value.auth.mode);input('username',value.auth.username);
    $('ntfy-enabled').checked=n.enabled;input('ntfy-server',n.server);input('ntfy-topic',n.topic);input('click-base',n.clickBase);$('include-title').checked=n.includeTitle;
    $('ntfy-token').placeholder=n.hasToken?'已保存；留空保留，服务地址变化时清除':'如服务需要认证，在这里填写';
  }
  for(const id of ['lan','port','tunnel','cloudflared','codex-home','ipc-path','codex-bin'])$(id).disabled=running;
  document.querySelectorAll('[data-pick]').forEach(button=>button.disabled=running);
}
async function refresh(){if(loading)return;loading=true;try{render(await api.snapshot());}catch(e){feedback(e.message,true);}finally{loading=false;}}
function collect(){return {preferences:{autoStart:$('auto-start').checked,port:Number($('port').value),lan:$('lan').checked,tunnel:$('tunnel').checked,cloudflared:$('cloudflared').value.trim(),codexHome:$('codex-home').value.trim(),ipcPath:$('ipc-path').value.trim(),codexBin:$('codex-bin').value.trim()},auth:{mode:$('auth-mode').value,username:$('username').value.trim(),password:$('password').value},origins:$('origins').value.split('\n').map(s=>s.trim()).filter(Boolean),notifications:{enabled:$('ntfy-enabled').checked,server:$('ntfy-server').value.trim(),topic:$('ntfy-topic').value.trim(),token:$('ntfy-token').value,clearToken:$('clear-token').checked,clickBase:$('click-base').value.trim(),includeTitle:$('include-title').checked}};}
$('settings').onsubmit=async event=>{event.preventDefault();$('save').disabled=true;try{const value=await api.save(collect());dirty=false;input('password','');input('ntfy-token','');$('clear-token').checked=false;$('dirty-label').textContent='配置已保存';render(value);feedback('配置已保存。通知设置由新版网关自动读取，登录设置在下次启动生效。');}catch(e){feedback(e.message,true);}finally{$('save').disabled=false;}};
$('start').onclick=async()=>{if(dirty){feedback('请先保存配置，再启动网关。',true);return;}$('start').disabled=true;try{const result=await api.start();startingUntil=Date.now()+70000;feedback(result.message);await refresh();}catch(e){startingUntil=0;feedback(e.message,true);$('start').disabled=false;}};
$('stop').onclick=async()=>{$('stop').disabled=true;try{feedback((await api.stop()).message);startingUntil=0;await refresh();}catch(e){feedback(e.message,true);$('stop').disabled=false;}};
$('test-notification').onclick=async()=>{if(dirty){feedback('请先保存 ntfy 配置，再发送测试通知。',true);return;}$('test-notification').disabled=true;try{feedback((await api.testNotification()).message);}catch(e){feedback(e.message,true);}finally{$('test-notification').disabled=false;}};
$('credentials').onclick=()=>api.open('credentials').catch(e=>feedback(e.message,true));
$('open-data').onclick=()=>api.open('data').catch(e=>feedback(e.message,true));
$('choose-data').onclick=async()=>{try{if(await api.choose('data')){dirty=false;await refresh();feedback('已切换网关目录，原来的网关进程继续运行。');}}catch(e){feedback(e.message,true);}};
for(const button of document.querySelectorAll('[data-pick]'))button.onclick=async()=>{try{const selected=await api.choose(button.dataset.kind);if(selected){input(button.dataset.pick,selected);dirty=true;$('dirty-label').textContent='有未保存的修改';}}catch(e){feedback(e.message,true);}};
async function loadLogs(){try{$('log-output').textContent=(await api.logs()).text;}catch(e){feedback(e.message,true);}}
$('refresh-logs').onclick=loadLogs;
refresh().then(()=>{if(snapshot?.preferences.autoStart&&!snapshot.runtime.running&&!snapshot.runtime.portOccupied)$('start').click();});setInterval(refresh,3000);
