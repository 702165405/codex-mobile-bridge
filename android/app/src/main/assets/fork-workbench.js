(() => {
  if (window.__cmbReceive) return;
  const token='__CMB_TOKEN__', bridge=window.CmbNative, pending=new Map(), originalFetch=window.fetch.bind(window);
  let state={};try {state=JSON.parse(bridge.state(token));}catch(_){}
  const persist=()=>bridge.saveState(token,JSON.stringify(state));
  const storage=Object.create(null);
  for(const [key,value] of Object.entries(state))storage[key]=String(value);
  const methods={getItem:key=>Object.prototype.hasOwnProperty.call(state,key)?String(state[key]):null,setItem:(key,value)=>{state[String(key)]=String(value);storage[String(key)]=String(value);persist();},removeItem:key=>{delete state[key];delete storage[key];persist();},clear:()=>{for(const key of Object.keys(state))delete storage[key];state={};persist();},key:index=>Object.keys(state)[index]??null};
  for(const [key,value]of Object.entries(methods))Object.defineProperty(storage,key,{value,enumerable:false});
  Object.defineProperty(storage,'length',{get:()=>Object.keys(state).length,enumerable:false});
  Object.defineProperty(window,'localStorage',{value:storage});
  window.__cmbReceive=(provided,id,status,headers,body,error)=>{if(provided!==token)return;const item=pending.get(id);if(!item)return;pending.delete(id);item.cleanup();if(error)item.reject(new TypeError('请求结果未确认，请检查连接后刷新，不要重复发送'));else {const bytes=Uint8Array.from(atob(body),c=>c.charCodeAt(0));item.resolve(new Response([204,205,304].includes(status)?null:bytes,{status,headers}));}};
  const encode=async value=>{const bytes=new Uint8Array(await new Blob([value??'']).arrayBuffer());if(bytes.length>20*1024*1024)throw Error('单个上传不能超过 20 MiB');let text='';for(let i=0;i<bytes.length;i+=16384)text+=String.fromCharCode(...bytes.subarray(i,i+16384));return btoa(text);};
  window.fetch=async(input,options={})=>{
    const request=new Request(input,options),url=new URL(request.url,location.href);
    if(request.method!=='POST')return originalFetch(request);
    if(url.origin!==location.origin||!url.pathname.startsWith('/api/'))throw Error('不允许的请求来源');
    if(request.signal.aborted)throw new DOMException('Aborted','AbortError');
    const body=await encode(await request.blob());if(request.signal.aborted)throw new DOMException('Aborted','AbortError');
    return new Promise((resolve,reject)=>{const id=crypto.randomUUID?crypto.randomUUID():Array.from(crypto.getRandomValues(new Uint8Array(16)),b=>b.toString(16).padStart(2,'0')).join(''),abort=()=>{bridge.cancel(token,id);pending.delete(id);reject(new DOMException('请求结果未确认','AbortError'));};request.signal.addEventListener('abort',abort,{once:true});pending.set(id,{resolve,reject,cleanup:()=>request.signal.removeEventListener('abort',abort)});bridge.post(token,id,url.href,JSON.stringify(Object.fromEntries(request.headers)),body);});
  };
  // Preserve the workbench upload API. Progress completes only after the gateway confirms saving.
  const OriginalXHR=window.XMLHttpRequest;
  window.XMLHttpRequest=class extends OriginalXHR {
    open(method,url,...rest){this.cmbMethod=method;this.cmbURL=new URL(url,location.href);this.cmbHeaders={};return super.open(method,url,...rest);}
    setRequestHeader(key,value){this.cmbHeaders[key]=value;return super.setRequestHeader(key,value);}
    send(body){if(this.cmbMethod?.toUpperCase()!=='POST'||this.cmbURL.origin!==location.origin||!this.cmbURL.pathname.startsWith('/api/'))return super.send(body);this.cmbController=new AbortController();const timer=setTimeout(()=>this.cmbController.abort(),this.timeout||120000);window.fetch(this.cmbURL,{method:'POST',headers:this.cmbHeaders,body,signal:this.cmbController.signal}).then(async response=>{const text=await response.text();Object.defineProperties(this,{status:{value:response.status,configurable:true},responseText:{value:text,configurable:true},readyState:{value:4,configurable:true}});this.upload.onprogress?.({lengthComputable:true,loaded:body?.size??0,total:body?.size??0});this.onload?.(new Event('load'));}).catch(error=>{(error.name==='AbortError'?this.onabort:this.onerror)?.(new Event('error'));}).finally(()=>clearTimeout(timer));}
    abort(){if(this.cmbController)this.cmbController.abort();else super.abort();}
  };
})();
