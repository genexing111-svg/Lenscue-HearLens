package com.hearlens.app

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.*

/** Authenticated, bounded local-Wi-Fi HTTP. No camera data on cellular/VPN interfaces. */
class WifiVideoServer(private val context: Context, private val engine: LocalStereoEngine, private val camera: UsbVideoServer) {
    private val prefs = context.getSharedPreferences("wifi_access", Context.MODE_PRIVATE)
    private val token = prefs.getString("token", null) ?: UUID.randomUUID().toString().replace("-", "").also { prefs.edit { putString("token",it) } }
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var address = ""
    @Volatile var error: String? = null; private set
    @Volatile var enabled = true
    @Volatile var onViewRequest: ((Boolean)->Unit)? = null
    @Volatile var onRemoteCalibration: ((Boolean)->Unit)?=null
    @Volatile var onControlSnapshot:(()->JSONObject)?=null
    @Volatile var onControlCommand:((JSONObject)->JSONObject)?=null
    private var workers: ThreadPoolExecutor? = null
    private var deadlines: ScheduledExecutorService? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    val url: String get() = if (listener == null) "" else "http://$address:$PORT/#$token"
    val connected: Int get() = clients.size

    @Synchronized fun reconcile() {
        if (!enabled) { stop(); return }
        // Keep a working interface when vivo's dual-Wi-Fi interface order changes.
        val addresses = wifiAddresses()
        val ip = addresses.firstOrNull { it == address } ?: addresses.firstOrNull()
        if (ip == address && listener?.isClosed == false) return
        stop()
        if (ip == null) { error = "请连接 Wi-Fi 或打开手机热点；本地双眼观看仍可使用"; return }
        try {
            val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(ip,PORT), 8) }
            address = ip; listener = server; error = null
            val pool = ThreadPoolExecutor(0,8,10,TimeUnit.SECONDS,SynchronousQueue(), { r -> Thread(r,"HearLens-WiFi").apply { isDaemon=true } })
            val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"HearLens-WiFi-deadline").apply { isDaemon=true } }
            workers = pool; deadlines = timer
            Thread({
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    clients.add(socket)
                    try { pool.execute {
                        var deadline: ScheduledFuture<*>? = null
                        try {
                            deadline = timer.schedule({ runCatching { socket.close() } },4,TimeUnit.SECONDS)
                            socket.soTimeout=2000; socket.tcpNoDelay=true; socket.sendBufferSize=64*1024
                            serve(socket,ip)
                        } catch (_: Exception) { /* Peer loss or invalid input cannot stop local rendering. */ }
                        finally { deadline?.cancel(false); clients.remove(socket); runCatching { socket.close() } }
                    } } catch (_: RejectedExecutionException) { clients.remove(socket); runCatching { socket.close() } }
                }
            },"HearLens-WiFi-accept").apply { isDaemon=true }.start()
        } catch (e: Exception) { error="无线监听启动失败：${e.message}"; stop() }
    }
    @Synchronized fun stop() {
        runCatching { listener?.close() }; listener=null; address=""
        clients.toList().forEach { runCatching { it.close() } }; clients.clear()
        workers?.shutdownNow(); deadlines?.shutdownNow(); workers=null; deadlines=null
    }

    private fun serve(socket: Socket, ip: String) {
        val input = BufferedInputStream(socket.getInputStream()); val header=ByteArrayOutputStream()
        var tail=0
        while (header.size()<8192) {
            val b=input.read(); if(b<0)return
            header.write(b); tail=(tail shl 8) or b; if(tail==0x0d0a0d0a)break
        }
        if(tail!=0x0d0a0d0a) { respond(socket,431,"请求头过大");return }
        val lines=header.toString("ISO-8859-1").split("\r\n")
        val request=lines[0].split(' ')
        if(request.size!=3 || request[0] !in setOf("GET","POST")) { respond(socket,405,"无效方法");return }
        val headers=mutableMapOf<String,String>()
        for(line in lines.drop(1).filter { it.isNotEmpty() }) {
            val colon=line.indexOf(':'); if(colon<1) { respond(socket,400,"无效头部");return }
            if(headers.put(line.substring(0,colon).lowercase(),line.substring(colon+1).trim())!=null) { respond(socket,400,"重复头部");return }
        }
        if(headers["host"]!="$ip:$PORT" || headers.containsKey("transfer-encoding")) { respond(socket,400,"无效地址或传输方式");return }
        val path=request[1].substringBefore('?')
        if(request[0]=="GET" && path=="/") {
            response(socket,200,"text/html; charset=utf-8",context.assets.open("wifi-viewer.html").use { it.readBytes() });return
        }
        if(!MessageDigest.isEqual((headers["x-hearlens-key"] ?: "").toByteArray(),token.toByteArray())) { respond(socket,403,"请使用手机显示的完整观看链接");return }
        if(request[0]=="GET") when(path) {
            "/status" -> {
                val active = engine.current() != null
                val state = camera.statusJson().put("stereo",engine.status()).put("transport","phone-local-wifi").put("wifiClients",connected)
                    .put("appVersion",BuildConfig.VERSION_NAME).put("controlVersion",1)
                    .put("state",if(active) "streaming" else "waiting").put("message",if(active) "手机本地高清显示与 Wi-Fi 共享" else "等待手机相机画面")
                    .put("isRunning",listener != null)
                response(socket,200,"application/json; charset=utf-8",state.toString().toByteArray())
            }
            "/control" -> {
                try {response(socket,200,"application/json; charset=utf-8",(onControlSnapshot?.invoke() ?: error("控制接口未就绪")).toString().toByteArray())}
                catch(e:Exception) {respond(socket,409,e.cause?.message ?: e.message ?: "手机状态暂不可用")}
            }
            "/frame.jpg" -> {
                val after = request[1].substringAfter("?after=","-1").toLongOrNull() ?: -1L
                var frame=engine.networkFrame(after); val until=SystemClock.elapsedRealtime()+600
                while(frame==null && SystemClock.elapsedRealtime()<until && !socket.isClosed) { Thread.sleep(15);frame=engine.networkFrame(after) }
                if(frame==null) response(socket,204,"text/plain",byteArrayOf())
                else response(socket,200,"image/jpeg",frame.jpeg,"X-Frame-Id: ${frame.id}\r\nX-Frame-Age-Ms: ${SystemClock.elapsedRealtime()-frame.time}\r\nX-Wait-Ms: ${SystemClock.elapsedRealtime()-(until-600)}\r\n")
            }
            else -> respond(socket,404,"不存在")
        } else {
            if(headers["origin"] != null && headers["origin"] != "http://$ip:$PORT") { respond(socket,403,"来源不符");return }
            val length=headers["content-length"]?.toIntOrNull()
            if(length==null || length !in 2..16384) { respond(socket,413,"内容长度无效");return }
            val body=ByteArray(length); var offset=0
            while(offset<length) { val n=input.read(body,offset,length-offset); if(n<0)return; offset+=n }
            try {
                val json=JSONObject(body.toString(Charsets.UTF_8))
                when(path) {
                    "/control" -> {
                        DesktopControlProtocol.validate(json)
                        val result=onControlCommand?.invoke(json) ?: error("控制接口未就绪")
                        response(socket,200,"application/json; charset=utf-8",result.toString().toByteArray());return
                    }
                    "/remote-calibration" -> {
                        val start=json.opt("start");require(json.length()==1 && start is Boolean)
                        onRemoteCalibration?.invoke(start)
                    }
                    "/view" -> {
                        val enabled=json.opt("stereo");require(json.length()==1 && enabled is Boolean)
                        onViewRequest?.invoke(enabled)
                    }
                    "/network-feedback" -> {
                        val cost=json.opt("transportMs");require(json.length()==1 && cost is Number && cost.toDouble().isFinite() && cost.toDouble() in 0.0..10000.0)
                        engine.networkFeedback(cost.toDouble())
                    }
                    "/subtitle" -> {
                        require(json.length()==1 && json.opt("text") is String) { "使用 {text: 字幕内容}" }
                        engine.configure(engine.settings.copy(caption=json.getString("text")))
                    }
                    "/layout" -> engine.configure(LocalStereoEngine.decode(json,engine.settings))
                    "/camera" -> {
                        require(json.keys().asSequence().all { it in setOf("cameraId","fps","quality","maxWidth","zoomRatio","exposureIndex","torch") })
                        val before=camera.currentSettings()
                        val id=json.optString("cameraId",before.cameraId)
                        if(id!=before.cameraId) { require(json.length()==1 && camera.requestCamera(id)) { "镜头切换请单独提交" } }
                        else {
                            fun n(k:String,d:Int):Int { val v=json.opt(k) ?: d; require(v is Int);return v }
                            val z=json.opt("zoomRatio") ?: before.zoomRatio; require(z is Number)
                            val t=json.opt("torch") ?: before.torch;require(t is Boolean)
                            camera.applyPhoneSettings(before.copy(fps=n("fps",before.fps),quality=n("quality",before.quality),maxWidth=n("maxWidth",before.maxWidth),
                                zoomRatio=z.toFloat(),exposureIndex=n("exposureIndex",before.exposureIndex),torch=t))
                        }
                    }
                    "/focus" -> { require(json.length()==1 && json.opt("action")=="center");camera.focusFromPhone() }
                    else -> { respond(socket,404,"不存在");return }
                }
                response(socket,200,"application/json", "{\"ok\":true}".toByteArray())
            } catch(e: Exception) { respond(socket,400,e.message ?: "参数无效") }
        }
    }
    private fun respond(s:Socket,code:Int,message:String)=response(s,code,"text/plain; charset=utf-8",message.toByteArray())
    private fun response(s:Socket,code:Int,type:String,body:ByteArray,extra:String="") {
        val reason=when(code) { 200->"OK";204->"No Content";403->"Forbidden";404->"Not Found";else->"Error" }
        val headers="HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n$extra\r\n"
        s.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII));write(body);flush() }
    }
    companion object {
        const val PORT=18766
        fun wifiAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }.mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}
