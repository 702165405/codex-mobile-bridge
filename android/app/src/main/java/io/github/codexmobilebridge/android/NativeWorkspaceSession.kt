package io.github.codexmobilebridge.android

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

fun nativeWorkspaceTabs(provider: String) = listOf("files" to ("文件" to "Files"),"git" to ("Git" to "Git"),"terminal" to ("终端" to "Terminal")) +
    if(provider=="codex")listOf("side" to ("侧边聊天" to "Side chat"),"agents" to ("子智能体" to "Agents")) else emptyList()

/** Bound to one captured connection and chat. Changing a page cancels only its reads. */
class NativeWorkspaceSession(val gateway: Gateway,val chat: ChatTarget,val store: AppStore,val app: Application,initial: String) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var reads: Job?=null;private var poll:Job?=null;private var generation=0
    var tab by mutableStateOf(initial);private set
    var busy by mutableStateOf(false);private set
    var loading by mutableStateOf(false);private set
    var error by mutableStateOf("");private set
    var notice by mutableStateOf("");private set
    var directory by mutableStateOf(store.get("workspace-directory:${chat.key}") ?: "");private set
    var search by mutableStateOf("")
    var hidden by mutableStateOf(false)
    var listing by mutableStateOf(payload());private set
    var preview by mutableStateOf<JsonObject?>(null);private set
    var git by mutableStateOf(payload());private set
    var gitView by mutableStateOf("changes");private set
    var gitList by mutableStateOf(payload());private set
    var gitDetail by mutableStateOf<JsonObject?>(null);private set
    var diff by mutableStateOf<JsonObject?>(null);private set
    var historyLimit by mutableIntStateOf(40);private set
    var terminal by mutableStateOf(payload());private set
    var terminalText by mutableStateOf("");private set
    private val buffer=NativeTerminalBuffer()
    private var terminalCursor=0L
    private var terminalId=store.get("workspace-terminal:${chat.key}") ?: newId()
    private var pendingInput by mutableStateOf<JsonObject?>(store.get("terminal-input:${chat.key}")?.let{runCatching{json.parseToJsonElement(it) as JsonObject}.getOrNull()})
    var side by mutableStateOf(payload());private set
    var sideCatalog by mutableStateOf(payload());private set
    var sideSkills by mutableStateOf(payload());private set
    var sideFiles by mutableStateOf(emptyList<Attachment>());private set
    var agents by mutableStateOf(payload());private set
    var agentDetail by mutableStateOf<JsonObject?>(null);private set
    var downloadProgress by mutableStateOf("");private set
    private var downloadRow:JsonObject?=null
    private var downloadJob:Job?=null
    var downloadPath by mutableStateOf("");private set
    private var creationId=store.get("side-create:${chat.key}") ?: newId().also{store.put("side-create:${chat.key}",it)}
    private fun url(action: String="",params:Map<String,String> = emptyMap())=chat.route("workspace"+if(action.isEmpty())"" else "/$action",params)
    private fun read(block:suspend ()->Unit) {
        reads?.cancel();loading=true;error="";val stamp=++generation
        reads=scope.launch {try{block()}catch(e:CancellationException){throw e}catch(e:Exception){if(stamp==generation)error=e.message ?: "Request failed"}finally{if(stamp==generation)loading=false}}
    }
    private fun write(block:suspend ()->Unit) {
        if(busy)return;busy=true;error="";notice=""
        scope.launch {try{block()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message ?: "Operation failed"}finally{busy=false}}
    }
    fun choose(value:String) {if(busy)return;require(nativeWorkspaceTabs(chat.provider).any{it.first==value});tab=value;preview=null;diff=null;gitDetail=null;refresh()}
    fun refresh() {
        poll?.cancel()
        when(tab){"files"->files(directory);"git"->loadGit();"terminal"->loadTerminal();"side"->loadSide();"agents"->loadAgents()}
    }
    fun pause(){reads?.cancel();poll?.cancel();generation++;loading=false}
    fun dispose(){scope.cancel()}
    fun clearError(){error="";notice=""}
    fun files(path:String=directory,more:Boolean=false)=read {
        val next=if(more)listing.num("nextOffset") else 0
        val page=gateway.api(url(params=mapOf("path" to path,"search" to search,"hidden" to hidden.toString(),"offset" to next.toString())))
        listing=if(more)JsonObject(page+payload("entries" to JsonArray((listing.rows("entries")+page.rows("entries")).distinctBy{it.str("path")}))) else page
        directory=path;store.put("workspace-directory:${chat.key}",path);preview=null
    }
    fun preview(row:JsonObject)=read {preview=gateway.api(url("preview",mapOf("path" to row.str("path"))))}
    fun closePreview(){preview=null;diff=null;gitDetail=null;agentDetail=null}
    fun prepareDownload(row:JsonObject){downloadRow=row}
    fun download(uri:Uri) {
        val row=downloadRow ?: return;downloadJob?.cancel()
        val path=url("download",mapOf("path" to row.str("path"),"explicit" to "1","archive" to if(row.str("kind")=="directory")"1" else "0"))
        val folder=File(app.cacheDir,"workspace-downloads").apply{mkdirs()}
        val spool=File(folder,java.security.MessageDigest.getInstance("SHA-256").digest((chat.key+path).toByteArray()).joinToString(""){"%02x".format(it)})
        downloadPath=row.str("path");downloadProgress="0";error=""
        downloadJob=scope.launch {try {
            gateway.downloadTo(path,spool){done,total->downloadProgress=if(total>0)"${done*100/total}%" else "${done/1024} KiB"}
            withContext(Dispatchers.IO){app.contentResolver.openOutputStream(uri,"wt")!!.use{output->spool.inputStream().use{it.copyTo(output)}};spool.delete()}
            downloadProgress="";notice="✓ "+row.str("name")
        }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message ?: "Download failed";downloadProgress=""}}
    }
    fun cancelDownload(){downloadJob?.cancel();downloadProgress=""}
    fun upload(uri:Uri)=write {
        val path=directory
        val file=withContext(Dispatchers.IO){
            var name="upload";app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())name=it.getString(0)}
            require(name.isNotBlank() && name !in listOf(".","..") && !name.contains('/') && !name.contains('\\'))
            val temporary=File.createTempFile("workspace-upload-",".bin",app.cacheDir)
            try{app.contentResolver.openInputStream(uri)!!.use{input->temporary.outputStream().use{out->val data=ByteArray(65536);var total=0L;var count:Int;while(input.read(data).also{count=it}>=0){total+=count;require(total<=20L*1024*1024){"每个上传文件不能超过 20 MB / Maximum upload: 20 MB"};out.write(data,0,count)}}};name to temporary}catch(e:Exception){temporary.delete();throw e}
        }
        try{gateway.api(url("upload",mapOf("path" to listOf(path,file.first).filter{it.isNotEmpty()}.joinToString("/"))),raw=file.second.asRequestBody("application/octet-stream".toMediaType()));files(path);notice="✓ "+file.first}finally{file.second.delete()}
    }
    fun loadGit(view:String=gitView)=read {
        gitView=view;git=gateway.api(url("git-status"));diff=null
        if(git.bool("available"))gitList=when(view){"history"->gateway.api(url("git-history",mapOf("limit" to historyLimit.toString(),"ref" to "all")));"branches"->gateway.api(url("git-branches"));else->payload()}
    }
    fun olderCommits(){historyLimit=(historyLimit+40).coerceAtMost(1000);loadGit("history")}
    fun gitDiff(row:JsonObject,section:String)=read {diff=gateway.api(url("git-diff",mapOf("path" to row.str("path"),"section" to section)))}
    fun commitDetail(row:JsonObject)=read {gitDetail=gateway.api(url("git-commit",mapOf("revision" to row.str("id"))))}
    fun historicalDiff(path:String,revision:String)=read {diff=gateway.api(url("git-history-diff",mapOf("path" to path,"revision" to revision)))}
    fun gitAction(fields:JsonObject)=write {
        val result=gateway.api(url("git-action"),fields)
        git=result.obj("state");notice=result.str("message",result.str("outcome"));loadGit()
    }
    fun terminalAction(action:String,data:String="")=write {
        if(action=="input"){
            if(pendingInput==null)pendingInput=pendingAttempt(null,payload("action" to "input","id" to terminalId,"data" to data),"inputId")
            store.put("terminal-input:${chat.key}",pendingInput!!.toString())
            val result=gateway.api(chat.route("terminal"),pendingInput!!)
            pendingInput=null;store.put("terminal-input:${chat.key}",null);applyTerminal(result)
        }else {
            if(action=="open"){require(pendingInput==null){"请先核验原输入 / Confirm the previous input first"};terminalId=newId();terminalCursor=0;buffer.clear();terminalText="";store.put("workspace-terminal:${chat.key}",terminalId)}
            if(action=="close"){pendingInput=null;store.put("terminal-input:${chat.key}",null)}
            val fields=payload("action" to action,"id" to terminalId)
            applyTerminal(gateway.api(chat.route("terminal"),if(action=="open")JsonObject(fields+payload("cols" to 100,"rows" to 30)) else fields))
        }
        scheduleTerminal()
    }
    private fun loadTerminal()=read {
        terminal=gateway.api(chat.route("terminal"))
        if(store.get("workspace-terminal:${chat.key}")!=null){applyTerminal(gateway.api(chat.route("terminal",mapOf("mode" to "pty","id" to terminalId,"after" to terminalCursor.toString()))));scheduleTerminal()}
    }
    private fun applyTerminal(result:JsonObject){terminal=JsonObject(terminal+result);if(result.bool("reset"))buffer.clear();buffer.append(result.str("output"));terminalText=buffer.text();terminalCursor=result.num("cursor",terminalCursor)}
    private fun scheduleTerminal(){poll?.cancel();poll=scope.launch {while(isActive && tab=="terminal" && terminal.bool("running")){delay(250);try{applyTerminal(gateway.api(chat.route("terminal",mapOf("mode" to "pty","id" to terminalId,"after" to terminalCursor.toString()))))}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message ?: "Terminal disconnected";break}}}}
    fun clearTerminal(){buffer.clear();terminalText=""}
    fun retryTerminalInput(){if(pendingInput!=null)terminalAction("input")}
    fun discardTerminalInput(){if(busy)return;pendingInput=null;store.put("terminal-input:${chat.key}",null)}
    val inputUnconfirmed get()=pendingInput!=null
    private fun loadSide()=read {
        side=gateway.api(chat.route("side-chat"))
        if(sideFiles.isEmpty() && side.str("id").isNotBlank())sideFiles=store.get("side-files:${chat.key}:${side.str("id")}")?.let{runCatching{json.decodeFromString<List<Attachment>>(it)}.getOrNull()}.orEmpty().map{if(it.status!="ready")it.copy(status="failed") else it}
        scheduleSide()
    }
    private fun saveSideFiles(){if(side.str("id").isNotBlank())store.put("side-files:${chat.key}:${side.str("id")}",json.encodeToString(sideFiles))}
    fun removeSideFile(id:String){if(busy)return;sideFiles.find{it.id==id}?.let{File(it.path).delete()};sideFiles=sideFiles.filter{it.id!=id};saveSideFiles()}
    fun uploadSide(uri:Uri)=write {
        val child=side.str("id");require(child.isNotBlank())
        var name="attachment";app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())name=it.getString(0)}
        val file=withContext(Dispatchers.IO){val out=File.createTempFile("side-upload-",".bin",app.cacheDir);try{app.contentResolver.openInputStream(uri)!!.use{input->out.outputStream().use{output->val buf=ByteArray(65536);var total=0L;var count:Int;while(input.read(buf).also{count=it}>=0){total+=count;require(total<=20L*1024*1024);output.write(buf,0,count)}}};out}catch(e:Exception){out.delete();throw e}}
        val row=Attachment(name=name,size=file.length(),path=file.path,status="uploading")
        try{validateAttachments(sideFiles,listOf(row));sideFiles=sideFiles+row;saveSideFiles();sendSideFile(row,child)}catch(e:Exception){if(sideFiles.none{it.id==row.id})file.delete();else{sideFiles=sideFiles.map{if(it.id==row.id)it.copy(status="failed",error=e.message ?: "Upload failed") else it};saveSideFiles()};throw e}
    }
    private suspend fun sendSideFile(row:Attachment,child:String){
        val result=gateway.api(chat.route("uploads",mapOf("side" to child,"id" to row.id,"name" to row.name)),raw=File(row.path).asRequestBody("application/octet-stream".toMediaType()))
        sideFiles=sideFiles.map{if(it.id==row.id)it.copy(status="ready",error="",image=result.bool("image")) else it};saveSideFiles()
    }
    fun retrySideFile(id:String)=write {sideFiles.find{it.id==id}?.let{sendSideFile(it,side.str("id"))}}

    private fun scheduleSide(){poll?.cancel();poll=scope.launch {while(isActive && tab=="side" && side.str("id").isNotBlank()){delay(1000);try{side=gateway.api(chat.route("side-chat"))}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message ?: "Side chat disconnected";break}}}}
    fun sideAction(action:String,fields:JsonObject=payload())=write {
        var body=JsonObject(fields+payload("action" to action))
        if(action=="create")body=JsonObject(body+payload("creationId" to creationId)) else body=JsonObject(body+payload("id" to side.str("id")))
        val key="side-attempt:${chat.key}:$action"
        if(action=="send"){require(sideFiles.all{it.status=="ready"});body=JsonObject(body+payload("attachments" to sideFiles.map{it.id}));body=pendingAttempt(store.get(key)?.let{json.parseToJsonElement(it) as JsonObject},body,"submissionId");store.put(key,body.toString())}
        val result=gateway.api(chat.route("side-chat"),body)
        check(result.str("status")!="unknown"){"结果尚未确认；请检查记录后重试原请求 / Result unconfirmed; retry keeps the original request"}
        if(action=="send"){store.put(key,null);store.put("side-draft:${chat.key}",null);sideFiles.forEach{File(it.path).delete()};sideFiles=emptyList();saveSideFiles()}
        if(action=="close"){sideFiles.forEach{File(it.path).delete()};sideFiles=emptyList();saveSideFiles();store.put("side-draft:${chat.key}",null);side=payload();creationId=newId();store.put("side-create:${chat.key}",creationId)}else if(action=="catalog")sideCatalog=result else if(action=="skills")sideSkills=result else if(action=="create")side=result
        loadSide()
    }
    fun loadAgents()=read {agents=gateway.api(chat.route("subagents"))}
    fun agent(row:JsonObject)=read {agentDetail=gateway.api(chat.route("subagents",mapOf("id" to row.str("id"))))}
}
