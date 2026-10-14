package com.hearlens.app

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.core.net.toUri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** HTTP over an Android abstract UNIX socket. No TCP listener or network permission is used. */
class UsbVideoServer(context: Context) {
    data class ReturnFrame(val jpeg: ByteArray, val sessionId: String, val sourceFrameId: Long, val receivedAtMs: Long, internal val displayGeneration: Long = 0L)
    @Volatile var onReturnFrame: ((ReturnFrame) -> Unit)? = null
    @Volatile var onVrModeRequest: ((Boolean, Long) -> Unit)? = null
    @Volatile internal var onSettingsChanged: ((VideoSettings, VideoSettings) -> Unit)? = null
    @Volatile internal var onFocusRequest: (() -> Unit)? = null
    @Volatile internal var onLocalBitmap: ((android.graphics.Bitmap, Long, Int, Boolean) -> Unit)? = null
    @Volatile internal var onLocalClear: (() -> Unit)? = null
    data class Capabilities(val fpsRanges: List<Pair<Int, Int>> = emptyList(), val maxFps: Int = 0, val selectedFpsRange: Pair<Int, Int>? = null)
    data class Snapshot(
        val isRunning: Boolean,
        val clientCount: Int,
        val lastError: String?,
        val frameId: Long,
        val width: Int,
        val height: Int,
        val state: String,
        val message: String,
        val lastFrameAgeMs: Long?,
        val sessionId: String,
        val settings: VideoSettings,
        val capabilities: Capabilities,
        val cameraFps: Double,
        val uplinkFps: Double,
        val returnFps: Double,
        val displayFps: Double,
        val returnAgeMs: Long?,
        val returnFrameId: Long,
        val vrEnabled: Boolean,
        val displayWidth: Int,
        val displayHeight: Int,
        val cameras: List<CameraOption>,
        val selectedCameraId: String,
        val cameraControls: CameraControls,
        val cameraAvailabilityNote: String,
        val activePhysicalCameraId: String?,
        val returnOwnerId: String?,
    )

    private data class Frame(val id: Long, val sessionId: String, val jpeg: ByteArray, val width: Int, val height: Int, val time: Long)

    private class Session(val socket: LocalServerSocket) {
        @Volatile var running = true
        val clients = mutableSetOf<LocalSocket>()
        val streams = mutableSetOf<LocalSocket>()
        val streamClientIds = mutableMapOf<LocalSocket, String?>()
        val workers = ThreadPoolExecutor(0, MAX_CLIENTS, 15L, TimeUnit.SECONDS, SynchronousQueue(), { task ->
            Thread(task, "HearLens-USB-client").apply { isDaemon = true }
        })
    }

    private val appContext = context.applicationContext
    private val settingsPreferences = appContext.getSharedPreferences("usb_video_settings", Context.MODE_PRIVATE)
    private val lock = Object()
    private var session: Session? = null
    private var latest: Frame? = null
    private var sequence = 0L
    private var cameraGeneration = 0L
    private var cameraEnabled = false
    private var cameraState = "paused"
    private var cameraMessage = "相机已暂停"
    private var lastError: String? = null
    private var cameraSessionId = UUID.randomUUID().toString()
    private var settings = runCatching {
        VideoSettings(if (settingsPreferences.getBoolean("native_pipeline_v51", false)) settingsPreferences.getInt("fps", 0) else 0,
            settingsPreferences.getInt("quality", 75), if (settingsPreferences.getBoolean("native_pipeline_v51", false)) settingsPreferences.getInt("maxWidth", 1280) else 1280,
            settingsPreferences.getString("cameraId", "") ?: "")
    }.getOrDefault(VideoSettings())
    private var capabilities = Capabilities()
    private var vrEnabled = false
    private var displayWidth = 0
    private var displayHeight = 0
    private val cameraRate = FrameRateMeter()
    private val uplinkRate = FrameRateMeter()
    private val returnRate = FrameRateMeter()
    private val displayRate = FrameRateMeter()
    private val publishedFrames = LinkedHashMap<Long, Long>()
    private var lastReturn: ReturnFrame? = null
    private var lastDisplayId = -1L
    private var lastSentId = -1L
    private val returnInFlight = AtomicBoolean(false)
    private var cameras = emptyList<CameraOption>()
    private var cameraLimits = emptyMap<String, CameraControls>()
    private var selectedCameraId = ""
    private var cameraControls = CameraControls()
    private var cameraAvailabilityNote = "正在读取系统开放的镜头"
    private var activePhysicalCameraId: String? = null
    private var returnOwnerId: String? = null
    private var displayGeneration = 0L
    private var displayRequestGeneration = 0L

    fun cameraOptions(): List<CameraOption> = synchronized(lock) { cameras.toList() }

    fun requestCamera(id: String): Boolean {
        val change = synchronized(lock) {
            val limits = cameraLimits[id] ?: return false
            val before = settings
            val after = CameraSettingsRules.resetForCamera(before, id, limits)
            settings = after
            saveSettings()
            before to after
        }
        if (change.first != change.second) onSettingsChanged?.invoke(change.first, change.second)
        return true
    }

    internal fun setCameraCatalog(options: List<CameraOption>, limits: Map<String, CameraControls>, note: String) = synchronized(lock) {
        val verified = cameras.filter { it.verified }.map { it.id }.toSet()
        cameras = options.map { if (it.id in verified) it.copy(verified = true) else it }
        cameraLimits = limits
        cameraAvailabilityNote = note
    }

    internal fun selectInitialCamera(id: String): VideoSettings = synchronized(lock) {
        val limits = cameraLimits[id] ?: CameraControls()
        if (settings.cameraId != id) settings = CameraSettingsRules.resetForCamera(settings, id, limits)
        else settings = settings.copy(zoomRatio = settings.zoomRatio.coerceIn(limits.zoomMin, limits.zoomMax),
            exposureIndex = settings.exposureIndex.coerceIn(limits.exposureMin, limits.exposureMax), torch = settings.torch && limits.torchSupported)
        saveSettings()
        settings
    }

    internal fun markCameraVerified(generation: Long, id: String) = synchronized(lock) {
        if (generation == cameraGeneration) cameras = cameras.map { if (it.id == id) it.copy(verified = true) else it }
    }
    internal fun updateCameraControls(generation: Long, id: String, controls: CameraControls) = synchronized(lock) {
        if (generation == cameraGeneration && cameraEnabled) {
            selectedCameraId = id
            cameraControls = controls
            cameraLimits = cameraLimits + (id to controls)
        }
    }
    internal fun updateActivePhysicalId(generation: Long, id: String?) = synchronized(lock) {
        if (generation == cameraGeneration) activePhysicalCameraId = id
    }
    private fun saveSettings() = settingsPreferences.edit {
        putBoolean("native_pipeline_v51", true)
        putInt("fps", settings.fps); putInt("quality", settings.quality); putInt("maxWidth", settings.maxWidth); putString("cameraId", settings.cameraId)
    }

    fun setVrEnabled(enabled: Boolean) = synchronized(lock) {
        displayRequestGeneration += 1
        vrEnabled = enabled
        if (!enabled) { clearReturn(); returnOwnerId = null }
    }

    /** Check on the UI thread immediately before handling an asynchronous /display callback. */
    fun isVrModeRequestCurrent(requestGeneration: Long): Boolean = synchronized(lock) {
        session?.running == true && displayRequestGeneration == requestGeneration
    }

    /** Atomically validates and applies a remote display command before the UI updates. */
    fun applyVrModeRequest(enabled: Boolean, requestGeneration: Long): Boolean = synchronized(lock) {
        if (!isVrModeRequestCurrent(requestGeneration)) false else {
            setVrEnabled(enabled)
            true
        }
    }

    fun setDisplayInfo(width: Int, height: Int) = synchronized(lock) {
        displayWidth = width.coerceIn(0, 8192)
        displayHeight = height.coerceIn(0, 8192)
    }

    fun isReturnFrameCurrent(frame: ReturnFrame): Boolean = synchronized(lock) {
        val published = publishedFrames[frame.sourceFrameId]
        val now = SystemClock.elapsedRealtime()
        session?.running == true && cameraEnabled && vrEnabled && frame.sessionId == cameraSessionId &&
            frame.displayGeneration == displayGeneration &&
            lastReturn != null && frame.sourceFrameId >= lastDisplayId &&
            now - frame.receivedAtMs <= FRESH_FRAME_MS && published != null && now - published <= FRESH_FRAME_MS
    }

    fun reportDisplayed(frame: ReturnFrame) = synchronized(lock) {
        if (isReturnFrameCurrent(frame) && frame.sourceFrameId > lastDisplayId) {
            lastDisplayId = frame.sourceFrameId
            displayRate.tick(SystemClock.elapsedRealtime())
        }
    }

    internal fun currentSettings(): VideoSettings = synchronized(lock) { settings }
    internal fun isLocalGenerationCurrent(value: Long): Boolean = synchronized(lock) { value == cameraGeneration && cameraEnabled }
    fun applyPhoneSettings(value: VideoSettings) {
        val before = synchronized(lock) {
            val limits = cameraLimits[value.cameraId] ?: throw IllegalArgumentException("请等待镜头就绪")
            require(CameraSettingsRules.validate(value, cameraLimits.keys, limits)) { "相机设置超出镜头范围" }
            settings.also { settings = value; saveSettings() }
        }
        onSettingsChanged?.invoke(before, value)
    }
    fun focusFromPhone() { onFocusRequest?.invoke() }
    internal fun reportCameraFrame(generation: Long, now: Long) = synchronized(lock) {
        if (generation == cameraGeneration && cameraEnabled) cameraRate.tick(now)
    }
    internal fun setCapabilities(generation: Long, ranges: List<Pair<Int, Int>>, selected: Pair<Int, Int>?) = synchronized(lock) {
        if (generation == cameraGeneration) capabilities = Capabilities(ranges, ranges.maxOfOrNull { it.second } ?: 0, selected)
    }
    private fun clearReturn() {
        displayGeneration += 1
        lastReturn = null
        lastDisplayId = -1L
        returnRate.reset()
        displayRate.reset()
    }
    private fun resetFrames() {
        latest = null
        publishedFrames.clear()
        lastSentId = -1L
        cameraRate.reset()
        uplinkRate.reset()
        clearReturn()
        onLocalClear?.invoke()
    }

    /** Binds locally and starts background accept/HTTP workers; never waits for a computer. */
    fun start() {
        synchronized(lock) {
            if (session?.running == true) return
            try {
                val created = Session(LocalServerSocket(SOCKET_NAME))
                session = created
                lastError = null
                resetFrames()
                Thread({ acceptClients(created) }, "HearLens-USB-accept").apply { isDaemon = true }.start()
            } catch (error: IOException) {
                lastError = "有线传输启动失败：${error.message ?: "本地连接不可用"}"
            }
        }
    }

    /** Closes the listener before allowing another start, and interrupts all bounded workers. */
    fun stop() {
        synchronized(lock) {
            val old = session
            session = null
            returnOwnerId = null
            displayRequestGeneration += 1
            resetFrames()
            if (old != null) closeSession(old)
            lock.notifyAll()
        }
    }

    internal fun beginCameraSession(message: String): Long = synchronized(lock) {
        cameraGeneration += 1
        cameraSessionId = UUID.randomUUID().toString()
        cameraEnabled = true
        cameraState = "waiting"
        cameraMessage = message
        resetFrames()
        lock.notifyAll()
        cameraGeneration
    }

    internal fun updateCameraState(generation: Long, state: String, message: String) {
        synchronized(lock) {
            if (generation != cameraGeneration || !cameraEnabled) {
                return
            }
            cameraState = state
            cameraMessage = message
            if (state != "streaming") {
                latest = null
            }
            lock.notifyAll()
        }
    }

    internal fun endCameraSession(state: String = "paused", message: String = "相机已暂停") {
        synchronized(lock) {
            cameraGeneration += 1
            cameraSessionId = UUID.randomUUID().toString()
            cameraEnabled = false
            selectedCameraId = ""
            activePhysicalCameraId = null
            cameraControls = cameraControls.copy(torchEnabled = false)
            cameraState = state
            cameraMessage = message
            resetFrames()
            session?.streams?.toList()?.forEach { runCatching { it.close() } }
            lock.notifyAll()
        }
    }

    internal fun shouldEncode(generation: Long): Boolean = synchronized(lock) {
        generation == cameraGeneration && cameraEnabled && (onLocalBitmap != null ||
            (session?.running == true && session?.streams?.isNotEmpty() == true))
    }

    /** The single latest-frame slot is replaced atomically; old lens callbacks cannot publish. */
    internal fun publishFrame(generation: Long, jpeg: ByteArray, width: Int, height: Int) {
        synchronized(lock) {
            if (!shouldEncode(generation)) return
            sequence += 1
            val now = SystemClock.elapsedRealtime()
            latest = Frame(sequence, cameraSessionId, jpeg, width, height, now)
            publishedFrames[sequence] = now
            val iterator = publishedFrames.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > FRESH_FRAME_MS || publishedFrames.size > 300) iterator.remove() else break
            }
            cameraState = "streaming"
            cameraMessage = "USB 有线画面传输中"
            lock.notifyAll()
        }
    }

    internal fun publishLocalBitmap(generation: Long, bitmap: android.graphics.Bitmap, rotation: Int, mirror: Boolean) {
        val callback = synchronized(lock) {
            if (!isLocalGenerationCurrent(generation) || onLocalBitmap == null) { bitmap.recycle(); return }
            sequence++
            val sideways = rotation % 180 != 0
            latest = Frame(sequence, cameraSessionId, byteArrayOf(), if (sideways) bitmap.height else bitmap.width,
                if (sideways) bitmap.width else bitmap.height, SystemClock.elapsedRealtime())
            cameraState = "streaming"; cameraMessage = "手机本地高清双眼显示中"
            onLocalBitmap
        }
        callback?.invoke(bitmap, generation, rotation, mirror)
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        val running = session?.running == true
        val frame = latest
        val age = frame?.let { SystemClock.elapsedRealtime() - it.time }
        val fresh = age != null && age <= FRESH_FRAME_MS
        val clients = session?.streams?.size ?: 0
        val state = when {
            !running && lastError != null -> "error"
            !running -> "paused"
            !cameraEnabled -> cameraState
            fresh && clients > 0 -> "streaming"
            else -> "waiting"
        }
        val message = when {
            !running && lastError != null -> lastError!!
            !running -> "USB 有线传输已关闭"
            !cameraEnabled -> cameraMessage
            clients == 0 -> "等待电脑通过 USB 连接"
            fresh -> "USB 有线画面传输中"
            else -> if (cameraState == "waiting") cameraMessage else "等待新的相机画面"
        }
        val now = SystemClock.elapsedRealtime()
        Snapshot(running, clients, lastError, sequence, if (fresh) frame!!.width else 0,
            if (fresh) frame!!.height else 0, state, message, age, cameraSessionId, settings, capabilities,
            cameraRate.fps(now), uplinkRate.fps(now), returnRate.fps(now), displayRate.fps(now),
            lastReturn?.let { now - it.receivedAtMs }, lastReturn?.sourceFrameId ?: 0, vrEnabled, displayWidth, displayHeight,
            cameras.toList(), selectedCameraId, cameraControls, cameraAvailabilityNote, activePhysicalCameraId, returnOwnerId)
    }

    private fun acceptClients(current: Session) {
        try {
            while (current.running) {
                val client = current.socket.accept()
                // adbd normally connects as shell (2000), or root on engineering builds.
                val uid = runCatching { client.peerCredentials.uid }.getOrDefault(-1)
                if (uid != 0 && uid != 2000 && uid != Process.myUid()) {
                    runCatching { client.close() }
                    continue
                }
                val accepted = synchronized(lock) {
                    if (session !== current || !current.running || current.clients.size >= MAX_CLIENTS) false
                    else { current.clients.add(client); true }
                }
                if (!accepted) {
                    runCatching { client.close() }
                    continue
                }
                try {
                    current.workers.execute {
                        try {
                            serve(current, client)
                        } catch (_: IOException) {
                            // A browser tab closing, USB disconnect, or write timeout is normal.
                        } catch (_: IllegalArgumentException) {
                            // Reject malformed requests without affecting other clients.
                        } finally {
                            synchronized(lock) {
                                current.clients.remove(client)
                                current.streams.remove(client)
                                current.streamClientIds.remove(client)
                                if (session === current && current.streams.isEmpty()) {
                                    latest = null
                                    publishedFrames.clear()
                                    clearReturn()
                                }
                                lock.notifyAll()
                            }
                            runCatching { client.close() }
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    synchronized(lock) { current.clients.remove(client) }
                    runCatching { client.close() }
                }
            }
        } catch (error: IOException) {
            synchronized(lock) {
                if (session === current && current.running) {
                    lastError = "USB 连接中断，请关闭后重新开启有线传输"
                }
            }
        } finally {
            synchronized(lock) {
                closeSession(current)
                if (session === current) {
                    session = null
                    returnOwnerId = null
                    displayRequestGeneration += 1
                    resetFrames()
                }
                lock.notifyAll()
            }
        }
    }

    /** Must hold lock. Closing the listener unblocks accept immediately. */
    private fun closeSession(current: Session) {
        current.running = false
        // shutdown wakes a thread already blocked in accept; close alone is not sufficient
        // on every Linux kernel while that syscall still holds a file descriptor reference.
        runCatching { Os.shutdown(current.socket.fileDescriptor, OsConstants.SHUT_RDWR) }
        runCatching { current.socket.close() }
        current.clients.toList().forEach { runCatching { it.close() } }
        current.streams.clear()
        current.streamClientIds.clear()
        current.workers.shutdownNow()
    }

    private fun serve(current: Session, client: LocalSocket) {
        client.soTimeout = IO_TIMEOUT_MS
        client.sendBufferSize = 128 * 1024
        val input = BufferedInputStream(client.inputStream)
        val header = ByteArrayOutputStream()
        val deadline = SystemClock.elapsedRealtime() + HEADER_TIMEOUT_MS
        var tail = 0
        while (header.size() < MAX_HEADER_BYTES) {
            if (SystemClock.elapsedRealtime() >= deadline) throw IOException("Header timeout")
            val byte = input.read()
            if (byte == -1) return
            header.write(byte)
            tail = (tail shl 8) or byte
            if (tail == 0x0d0a0d0a) break
        }
        val output = client.outputStream
        if (tail != 0x0d0a0d0a) {
            respond(output, "431 Request Header Fields Too Large", "text/plain; charset=utf-8", "请求头过大".toByteArray())
            return
        }
        val lines = header.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val request = lines.first().split(' ')
        if (request.size != 3 || request[0] !in setOf("GET", "POST") || !request[2].startsWith("HTTP/1.")) {
            respond(output, "405 Method Not Allowed", "text/plain; charset=utf-8", "仅支持 GET 或 POST".toByteArray())
            return
        }
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1).filter { it.isNotEmpty() }) {
            val split = line.indexOf(':')
            if (split <= 0) { reject(output, "400 Bad Request", "无效请求头"); return }
            val name = line.substring(0, split).lowercase()
            if (headers.put(name, line.substring(split + 1).trim()) != null) {
                reject(output, "400 Bad Request", "重复请求头"); return
            }
        }
        if (headers.containsKey("transfer-encoding")) { reject(output, "400 Bad Request", "不支持分块请求体"); return }
        if (request[0] == "POST") {
            post(current, client, request[1], headers, input, output)
            return
        }
        when (request[1].substringBefore('?')) {
            "/", "/index.html" -> {
                val html = appContext.assets.open("usb-viewer.html").use { it.readBytes() }
                respond(output, "200 OK", "text/html; charset=utf-8", html)
            }
            "/status" -> {
                respond(output, "200 OK", "application/json; charset=utf-8", statusJson().toString().toByteArray())
            }
            "/frame.jpg" -> {
                val frame = synchronized(lock) { latest?.takeIf { isFresh(it) } }
                if (frame == null) respond(output, "503 Service Unavailable", "text/plain; charset=utf-8", "暂无实时画面".toByteArray())
                else respond(output, "200 OK", "image/jpeg", frame.jpeg)
            }
            "/stream" -> {
                val clientId = request[1].toUri().getQueryParameter("client")
                if (clientId != null && !clientId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) {
                    reject(output, "400 Bad Request", "client无效")
                } else stream(current, client, output, clientId)
            }
            else -> respond(output, "404 Not Found", "text/plain; charset=utf-8", "页面不存在".toByteArray())
        }
    }

    internal fun statusJson(): JSONObject {
        val value = snapshot()
        val ranges = JSONArray().also { array -> value.capabilities.fpsRanges.forEach { array.put(JSONArray(listOf(it.first, it.second))) } }
        val selected = value.capabilities.selectedFpsRange?.let { JSONArray(listOf(it.first, it.second)) } ?: JSONObject.NULL
        return JSONObject().put("state", value.state).put("message", value.message)
            .put("isRunning", value.isRunning).put("clients", value.clientCount)
            .put("frameId", value.frameId).put("width", value.width).put("height", value.height)
            .put("lastFrameAgeMs", value.lastFrameAgeMs ?: JSONObject.NULL).put("lastError", value.lastError ?: JSONObject.NULL)
            .put("sessionId", value.sessionId)
            .put("settings", JSONObject().put("fps", value.settings.fps).put("quality", value.settings.quality).put("maxWidth", value.settings.maxWidth)
                .put("cameraId", value.settings.cameraId).put("zoomRatio", value.settings.zoomRatio).put("exposureIndex", value.settings.exposureIndex).put("torch", value.settings.torch))
            .put("capabilities", JSONObject().put("fpsRanges", ranges).put("maxFps", value.capabilities.maxFps).put("selectedFpsRange", selected))
            .put("cameraFps", value.cameraFps).put("uplinkFps", value.uplinkFps).put("returnFps", value.returnFps).put("displayFps", value.displayFps)
            .put("returnAgeMs", value.returnAgeMs ?: JSONObject.NULL).put("returnFrameId", value.returnFrameId)
            .put("vrEnabled", value.vrEnabled).put("displayWidth", value.displayWidth).put("displayHeight", value.displayHeight)
            .put("cameras", JSONArray().also { array -> value.cameras.forEach { option ->
                array.put(JSONObject().put("id", option.id).put("label", option.label).put("facing", option.facing)
                    .put("focalLengthMm", option.focalLengthMm ?: JSONObject.NULL).put("logicalCameraId", option.logicalCameraId)
                    .put("physicalCameraId", option.physicalCameraId ?: JSONObject.NULL).put("verified", option.verified))
            } })
            .put("selectedCameraId", value.selectedCameraId).put("cameraAvailabilityNote", value.cameraAvailabilityNote)
            .put("activePhysicalCameraId", value.activePhysicalCameraId ?: JSONObject.NULL)
            .put("returnOwnerId", value.returnOwnerId ?: JSONObject.NULL)
            .put("cameraControls", value.cameraControls.let { controls -> JSONObject()
                .put("zoomMin", controls.zoomMin).put("zoomMax", controls.zoomMax).put("zoomRatio", controls.zoomRatio)
                .put("exposureMin", controls.exposureMin).put("exposureMax", controls.exposureMax).put("exposureStep", controls.exposureStep)
                .put("exposureIndex", controls.exposureIndex).put("torchSupported", controls.torchSupported).put("torchEnabled", controls.torchEnabled)
                .put("focusSupported", controls.focusSupported).put("controlsError", controls.controlsError ?: JSONObject.NULL) })
    }

    private fun post(current: Session, client: LocalSocket, target: String, headers: Map<String, String>, input: BufferedInputStream, output: OutputStream) {
        val path = target.substringBefore('?')
        if (path !in setOf("/settings", "/display", "/return-frame", "/focus")) { reject(output, "404 Not Found", "页面不存在"); return }
        val length = headers["content-length"]?.toLongOrNull()
        if (length == null || length <= 0) { reject(output, "411 Length Required", "需要有效 Content-Length"); return }
        val maxBytes = if (path == "/return-frame") JpegValidator.MAX_BYTES else 1024
        if (length > maxBytes) { reject(output, "413 Payload Too Large", "请求体过大"); return }
        val type = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()
        if (type != if (path == "/return-frame") "image/jpeg" else "application/json") {
            reject(output, "415 Unsupported Media Type", "请求格式不支持"); return
        }
        if (path == "/return-frame") {
            if (!returnInFlight.compareAndSet(false, true)) { reject(output, "429 Too Many Requests", "已有回传正在处理"); return }
            try {
                val uri = target.toUri()
                val incomingSession = uri.getQueryParameter("session")
                val source = uri.getQueryParameter("source")?.toLongOrNull()
                val clientId = uri.getQueryParameter("client")
                if (incomingSession == null || source == null || !canAcceptReturn(current, incomingSession, source, clientId)) {
                    reject(output, "409 Conflict", "回传帧已过期，请使用当前会话和新帧"); return
                }
                val body = readBody(client, input, length.toInt())
                if (JpegValidator.dimensions(body) == null) { reject(output, "400 Bad Request", "无效或尺寸超限的 JPEG"); return }
                val accepted = synchronized(lock) {
                    if (!canAcceptReturn(current, incomingSession, source, clientId)) null
                    else ReturnFrame(body, incomingSession, source, SystemClock.elapsedRealtime(), displayGeneration).also {
                        lastReturn = it
                        returnRate.tick(it.receivedAtMs)
                    }
                }
                if (accepted == null) { reject(output, "409 Conflict", "回传帧已过期"); return }
                onReturnFrame?.invoke(accepted)
                respond(output, "204 No Content", "text/plain", byteArrayOf())
            } finally { returnInFlight.set(false) }
            return
        }
        val body = readBody(client, input, length.toInt())
        try {
            val json = JSONObject(body.toString(Charsets.UTF_8))
            if (path == "/display") {
                val keys = json.keys().asSequence().toSet()
                if (keys.any { it !in setOf("enabled", "clientId", "takeover") } || json.opt("enabled") !is Boolean) throw IllegalArgumentException()
                val clientId = if (json.has("clientId")) json.get("clientId") as? String ?: throw IllegalArgumentException("clientId无效") else null
                if (clientId != null && !clientId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) throw IllegalArgumentException("clientId无效")
                val takeover = if (json.has("takeover")) json.get("takeover") as? Boolean ?: throw IllegalArgumentException("takeover无效") else false
                val enabled = json.getBoolean("enabled")
                val accepted = synchronized(lock) {
                    val owner = returnOwnerId
                    if (!ReturnOwnershipRules.canRequest(owner, clientId, enabled, takeover)) null
                    else {
                        if (enabled && clientId != null) {
                            if (owner != clientId) clearReturn()
                            returnOwnerId = clientId
                        }
                        if (!enabled) { returnOwnerId = null; clearReturn() }
                        displayRequestGeneration += 1
                        displayRequestGeneration
                    }
                }
                if (accepted == null) { reject(output, "409 Conflict", "另一页面正在回传，请点击接管后再开始"); return }
                respond(output, "204 No Content", "text/plain", byteArrayOf())
                onVrModeRequest?.invoke(enabled, accepted)
            } else if (path == "/focus") {
                if (json.length() != 1 || json.opt("action") != "center") throw IllegalArgumentException("仅支持 action=center")
                if (!synchronized(lock) { cameraEnabled && selectedCameraId.isNotEmpty() && cameraControls.focusSupported }) {
                    reject(output, "409 Conflict", "当前镜头未就绪或不支持中心对焦测光"); return
                }
                respond(output, "204 No Content", "text/plain", byteArrayOf())
                onFocusRequest?.invoke()
            } else {
                val keys = json.keys().asSequence().toSet()
                if (keys.isEmpty() || keys.any { it !in setOf("fps", "quality", "maxWidth", "cameraId", "zoomRatio", "exposureIndex", "torch") }) throw IllegalArgumentException("包含未知设置项")
                fun integer(key: String, default: Int): Int {
                    if (!json.has(key)) return default
                    val value = json.get(key)
                    if (value !is Int) throw IllegalArgumentException()
                    return value
                }
                val change = synchronized(lock) {
                    val before = settings
                    val cameraId = if (json.has("cameraId")) (json.get("cameraId") as? String ?: throw IllegalArgumentException("cameraId需为字符串")) else before.cameraId
                    val changesHardware = keys.any { it in setOf("cameraId", "zoomRatio", "exposureIndex", "torch") }
                    val limits = cameraLimits[cameraId] ?: if (!changesHardware) CameraControls() else throw IllegalArgumentException("镜头ID无效或镜头列表尚未就绪")
                    val defaults = if (cameraId != before.cameraId) CameraSettingsRules.resetForCamera(before, cameraId, limits) else before
                    val zoom = if (json.has("zoomRatio")) {
                        val number = json.get("zoomRatio") as? Number ?: throw IllegalArgumentException("zoomRatio需为有限数值")
                        val value = number.toDouble()
                        if (!value.isFinite()) throw IllegalArgumentException("zoomRatio需为有限数值")
                        value.toFloat()
                    } else defaults.zoomRatio
                    val torch = if (json.has("torch")) json.get("torch") as? Boolean ?: throw IllegalArgumentException("torch需为布尔值") else defaults.torch
                    val after = VideoSettings(integer("fps", before.fps), integer("quality", before.quality), integer("maxWidth", before.maxWidth),
                        cameraId, zoom, integer("exposureIndex", defaults.exposureIndex), torch)
                    if (changesHardware && !CameraSettingsRules.validate(after, cameraLimits.keys, limits)) throw IllegalArgumentException("缩放、曝光或补光灯超出当前镜头支持范围")
                    settings = after
                    if (before != after) saveSettings()
                    before to after
                }
                if (change.first != change.second || keys.any { it in setOf("zoomRatio", "exposureIndex", "torch") })
                    onSettingsChanged?.invoke(change.first, change.second)
                respond(output, "200 OK", "application/json; charset=utf-8", statusJson().toString().toByteArray())
            }
        } catch (_: org.json.JSONException) { reject(output, "400 Bad Request", "无效 JSON 参数") }
        catch (error: IllegalArgumentException) { reject(output, "400 Bad Request", error.message ?: "设置参数不在有效范围内") }
    }

    private fun canAcceptReturn(current: Session, incomingSession: String, source: Long, clientId: String?): Boolean = synchronized(lock) {
        val published = publishedFrames[source]
        session === current && current.running && cameraEnabled && current.streams.isNotEmpty() &&
            ReturnOwnershipRules.acceptsFrame(returnOwnerId, clientId) &&
            incomingSession == cameraSessionId && source > (lastReturn?.sourceFrameId ?: -1) &&
            published != null && SystemClock.elapsedRealtime() - published <= FRESH_FRAME_MS
    }

    private fun readBody(client: LocalSocket, input: BufferedInputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        val deadline = SystemClock.elapsedRealtime() + BODY_TIMEOUT_MS
        var offset = 0
        while (offset < length) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw IOException("Body timeout")
            client.soTimeout = remaining.coerceAtMost(IO_TIMEOUT_MS.toLong()).toInt().coerceAtLeast(1)
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw IOException("Truncated body")
            offset += count
        }
        client.soTimeout = IO_TIMEOUT_MS
        return bytes
    }

    private fun reject(output: OutputStream, code: String, message: String) = respond(output, code, "text/plain; charset=utf-8", message.toByteArray())

    private fun stream(current: Session, client: LocalSocket, output: OutputStream, clientId: String?) {
        val generation = synchronized(lock) {
            if (session !== current || !current.running || !cameraEnabled) null
            else {
                fun evict(old: LocalSocket) {
                    current.streams.remove(old)
                    current.streamClientIds.remove(old)
                    runCatching { old.shutdownOutput() }
                    runCatching { old.close() }
                }
                if (clientId != null && clientId == returnOwnerId) {
                    // A reload/reconnect from the return owner supersedes its stale socket.
                    current.streams.filter { current.streamClientIds[it] == clientId }.toList().forEach(::evict)
                    if (current.streams.size >= MAX_STREAMS) {
                        // Insertion order chooses the oldest observer. Observers never evict
                        // the owner, and the global connection/thread limits remain unchanged.
                        current.streams.firstOrNull { current.streamClientIds[it] != returnOwnerId }?.let(::evict)
                    }
                }
                if (current.streams.size >= MAX_STREAMS) null
                else {
                    current.streams.add(client)
                    current.streamClientIds[client] = clientId
                    lock.notifyAll()
                    cameraGeneration
                }
            }
        }
        if (generation == null) {
            respond(output, "503 Service Unavailable", "text/plain; charset=utf-8", "相机已暂停或观看人数已满（最多2个）".toByteArray())
            return
        }
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n" + COMMON_HEADERS + "\r\n").toByteArray(Charsets.US_ASCII))
        output.flush()
        var lastSent = -1L
        var lastSendTime = SystemClock.elapsedRealtime()
        while (current.running) {
            val frame = synchronized(lock) {
                while (current.running && session === current && cameraEnabled && generation == cameraGeneration && client in current.streams &&
                    (latest == null || latest?.id == lastSent || latest?.let { !isFresh(it) } == true)) {
                    // Closing after a short stall lets the browser remove a frozen image and retry.
                    if (SystemClock.elapsedRealtime() - lastSendTime > STREAM_STALL_MS) return
                    try { lock.wait(250L) } catch (_: InterruptedException) { return }
                }
                if (!current.running || session !== current || !cameraEnabled || generation != cameraGeneration || client !in current.streams) return
                latest ?: return
            }
            output.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.jpeg.size}\r\nX-Frame-Id: ${frame.id}\r\nX-Session-Id: ${frame.sessionId}\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.write(frame.jpeg)
            output.write(CRLF)
            output.flush()
            lastSent = frame.id
            lastSendTime = SystemClock.elapsedRealtime()
            synchronized(lock) {
                if (session === current && frame.id > lastSentId && frame.sessionId == cameraSessionId) {
                    lastSentId = frame.id
                    uplinkRate.tick(lastSendTime)
                }
            }
        }
    }

    private fun isFresh(frame: Frame) = SystemClock.elapsedRealtime() - frame.time <= FRESH_FRAME_MS

    private fun respond(output: OutputStream, code: String, contentType: String, bytes: ByteArray) {
        output.write(("HTTP/1.1 $code\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n" + COMMON_HEADERS + "\r\n").toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    companion object {
        const val SOCKET_NAME = "hearlens_usb_video"
        private const val MAX_CLIENTS = 8
        private const val MAX_STREAMS = 2
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val HEADER_TIMEOUT_MS = 5_000L
        private const val BODY_TIMEOUT_MS = 3_000L
        private const val IO_TIMEOUT_MS = 2_000
        private const val FRESH_FRAME_MS = 1_500L
        private const val STREAM_STALL_MS = 3_000L
        private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)
        private const val COMMON_HEADERS = "Cache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n"
    }
}
