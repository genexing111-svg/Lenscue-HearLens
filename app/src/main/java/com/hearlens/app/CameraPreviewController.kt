package com.hearlens.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.util.Size
import android.util.Range
import androidx.annotation.MainThread
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.TorchState
import androidx.camera.core.ZoomState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.google.common.util.concurrent.ListenableFuture

/** Owns local preview and a latest-frame-only analysis pipeline for the USB viewer. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class CameraPreviewController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val usbVideoServer: UsbVideoServer,
    private val onState: (State) -> Unit,
    private val onCamerasAvailable: (Set<Int>) -> Unit,
) {
    sealed interface State {
        data object Stopped : State
        data object Connecting : State
        data object Streaming : State
        data class Failed(val messageRes: Int) : State
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraObserver: Observer<CameraState>? = null
    private var boundPreview: Preview? = null
    private var boundAnalysis: ImageAnalysis? = null
    private var targetRotation: Int? = null
    private var nv21Buffer: ByteArray? = null
    private val jpegOutput = ByteArrayOutputStream(256 * 1024)
    private var requestedLens: Int? = null
    private data class Target(val option: CameraOption, val info: CameraInfo, val metadata: CameraCharacteristics)
    private var targets = emptyMap<String, Target>()
    private var currentTarget: Target? = null
    private var lastGoodCameraId: String? = null
    private var controlVersion = 0
    private var controlError: String? = null
    private var zoomObserver: Observer<ZoomState>? = null
    private var torchObserver: Observer<Int>? = null
    private var fallbackAttempted = false
    private var previewReady = false
    private var analysisReady = false
    @Volatile private var active = false
    private var released = false
    private val analysisExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HearLens-JPEG").apply { isDaemon = true }
    }
    private var usbGeneration = 0L
    // Reject late provider callbacks after pause, rotation, or a fast camera switch.
    @Volatile private var generation = 0
    private var frameTimeout: Runnable? = null
    private var recoverableErrorTimeout: Runnable? = null
    private var pendingRebind:Runnable?=null

    init {
        // With COMPATIBLE mode, STREAMING guarantees the preview is visible.
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        usbVideoServer.onSettingsChanged = { before, after ->
            handler.post {
                if (before.fps != after.fps || before.maxWidth != after.maxWidth || before.cameraId != after.cameraId) {
                    val lens = requestedLens
                    if (!released && active && lens != null) {
                        controlError = null
                        pendingRebind?.let { handler.removeCallbacks(it) }
                        val job=Runnable { pendingRebind=null;if(!released && active) { stop();start(lens) } }
                        pendingRebind=job
                        handler.postDelayed(job,300L)
                    }
                } else if (before == after || before.zoomRatio != after.zoomRatio || before.exposureIndex != after.exposureIndex || before.torch != after.torch) {
                    // HTTP commits and worker notifications can be reordered. Always apply
                    // the latest committed values when this main-thread job actually runs.
                    applyControls(usbVideoServer.currentSettings())
                }
            }
        }
        usbVideoServer.onFocusRequest = { handler.post { focusCenter() } }
        previewView.previewStreamState.observe(lifecycleOwner) { stream ->
            if (!active || boundPreview == null) return@observe
            when (stream) {
                PreviewView.StreamState.STREAMING -> {
                    previewReady = true
                    if (analysisReady) cancelTimeout()
                    usbVideoServer.updateCameraState(usbGeneration, "streaming", "相机已就绪，等待电脑连接")
                    onState(State.Streaming)
                }
                PreviewView.StreamState.IDLE -> {
                    previewReady = false
                    usbVideoServer.updateCameraState(usbGeneration, "waiting", "相机正在连接")
                    onState(State.Connecting)
                    awaitFrames(generation)
                }
                null -> Unit
            }
        }
    }

    @MainThread
    fun start(lensFacing: Int) = startInternal(lensFacing, false)

    @MainThread
    private fun startInternal(lensFacing: Int, isFallback: Boolean) {
        if (released) return
        if (active && requestedLens == lensFacing) return
        if (!isFallback) fallbackAttempted = false
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            usbVideoServer.endCameraSession("error", context.getString(R.string.error_permission))
            onState(State.Failed(R.string.error_permission))
            return
        }
        active = true
        requestedLens = lensFacing
        val request = generation
        val frameGeneration = usbVideoServer.beginCameraSession("相机正在连接")
        usbGeneration = frameGeneration
        onState(State.Connecting)
        awaitFrames(request)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!active || request != generation) return@addListener
            try {
                val currentProvider = future.get()
                provider = currentProvider
                discoverCameras(currentProvider)
                val available = targets.values.map { it.info.lensFacing }.toSet()
                onCamerasAvailable(available)
                if (available.isEmpty()) {
                    fail(R.string.error_no_camera)
                    return@addListener
                }
                val requestedId = usbVideoServer.currentSettings().cameraId
                val defaultInfo = runCatching { currentProvider.getCameraInfo(selector(lensFacing)) }.getOrNull()
                val defaultId = defaultInfo?.let { Camera2CameraInfo.from(it).cameraId }
                val target = targets[requestedId] ?: targets[defaultId] ?: targets.values.firstOrNull()
                if (target == null) {
                    fail(R.string.error_facing_unavailable)
                    return@addListener
                }
                currentTarget = target
                val settings = usbVideoServer.selectInitialCamera(target.option.id)
                val selectedSelector = selectorForId(target.option.logicalCameraId)
                val physical = target.option.physicalCameraId
                val physicalSizes = if (physical != null) target.metadata.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.YUV_420_888)?.toSet() ?: emptySet() else emptySet()
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(settings.maxWidth, settings.maxWidth * 3 / 4), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .setResolutionFilter { sizes, _ ->
                        val supported = if (physical == null) sizes else sizes.filter { it in physicalSizes }
                        require(supported.isNotEmpty()) { "物理镜头不支持当前输出尺寸" }
                        val withinLimit = supported.filter { maxOf(it.width, it.height) <= settings.maxWidth }
                        withinLimit.ifEmpty { supported.sortedBy { it.width.toLong() * it.height }.take(1) }
                    }
                    .build()
                val previewBuilder = Preview.Builder().setResolutionSelector(resolution)
                targetRotation?.let { previewBuilder.setTargetRotation(it) }
                if (physical != null && Build.VERSION.SDK_INT >= 28) Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physical)
                Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, captureRequest: CaptureRequest, result: TotalCaptureResult) {
                        usbVideoServer.reportCameraFrame(frameGeneration, SystemClock.elapsedRealtime())
                        if (Build.VERSION.SDK_INT >= 29) usbVideoServer.updateActivePhysicalId(frameGeneration, result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID))
                    }
                })
                val useCase = previewBuilder.build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val localNative = usbVideoServer.onLocalBitmap != null
                val analysisBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(if (localNative) ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888 else ImageAnalysis.OUTPUT_IMAGE_FORMAT_NV21)
                    .setOutputImageRotationEnabled(!localNative)
                targetRotation?.let { analysisBuilder.setTargetRotation(it) }
                if (physical != null && Build.VERSION.SDK_INT >= 28) Camera2Interop.Extender(analysisBuilder).setPhysicalCameraId(physical)
                val analysis = analysisBuilder.build()
                val sessionBuilder = SessionConfig.Builder(listOf(useCase, analysis))
                val cameraInfo = target.info
                val fpsRanges = cameraInfo.getSupportedFrameRateRanges(sessionBuilder.build())
                    .map { it.lower to it.upper }.let { ranges -> constrainPhysicalFps(target, settings.maxWidth, ranges) }
                    .sortedWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
                if (physical != null && fpsRanges.isEmpty()) throw IllegalArgumentException("物理镜头与预览分析组合无共同支持的帧率")
                val selectedRange = FpsSelection.choose(fpsRanges, settings.fps)
                selectedRange?.let { sessionBuilder.setFrameRateRange(Range(it.first, it.second)) }
                usbVideoServer.setCapabilities(frameGeneration, fpsRanges, selectedRange)
                // These variables belong to this lens session and are confined to the one worker.
                var nextEncodeNs = 0L
                var lastEncodeErrorNs = 0L
                var firstImageSeen = false
                analysis.setAnalyzer(analysisExecutor) { image ->
                    try {
                        if (!active || request != generation) return@setAnalyzer
                        if (!firstImageSeen) {
                            firstImageSeen = true
                            handler.post {
                                if (active && request == generation) {
                                    lastGoodCameraId = target.option.id
                                    analysisReady = true
                                    if (previewReady) cancelTimeout()
                                    usbVideoServer.markCameraVerified(frameGeneration, target.option.id)
                                }
                            }
                        }
                        if (!usbVideoServer.shouldEncode(frameGeneration)) return@setAnalyzer
                        val now = SystemClock.elapsedRealtimeNanos()
                        val currentSettings = usbVideoServer.currentSettings()
                        // Auto has no software cap. Explicit values can reduce upload cadence
                        // when no exact fixed camera FPS range is available.
                        if (currentSettings.fps > 0 && selectedRange != (currentSettings.fps to currentSettings.fps)) {
                            val interval = 1_000_000_000L / currentSettings.fps
                            if (now + 1_000_000L < nextEncodeNs) return@setAnalyzer
                            nextEncodeNs = if (nextEncodeNs == 0L || now - nextEncodeNs > interval) now + interval else nextEncodeNs + interval
                        }
                        if (localNative) {
                            // CameraX uses native YUV-to-RGB conversion with row/pixel stride handling.
                            // Rotate and mirror on the GPU at display time; no per-pixel Kotlin or JPEG.
                            val bitmap = image.toBitmap()
                            if (active && request == generation) usbVideoServer.publishLocalBitmap(frameGeneration, bitmap,
                                image.imageInfo.rotationDegrees, target.option.facing == "front")
                            else bitmap.recycle()
                        } else {
                            val frame = encode(image, target.option.facing == "front", currentSettings.quality)
                            if (active && request == generation) usbVideoServer.publishFrame(frameGeneration, frame.first, frame.second.width, frame.second.height)
                        }
                    } catch (_: Exception) {
                        // Surface capture may already be closing. Report persistent failures only
                        // to the current session, without taking down local camera preview.
                        val now = SystemClock.elapsedRealtimeNanos()
                        if (now - lastEncodeErrorNs > 2_000_000_000L) {
                            lastEncodeErrorNs = now
                            usbVideoServer.updateCameraState(frameGeneration, "waiting", "画面转换暂时失败，正在重试")
                        }
                    } finally {
                        image.close()
                    }
                }
                // Set this before bind; synchronous state callbacks must see the current use case.
                boundPreview = useCase
                boundAnalysis = analysis
                val boundCamera = currentProvider.bindToLifecycle(lifecycleOwner, selectedSelector, sessionBuilder.build())
                camera = boundCamera
                zoomObserver = Observer { refreshControls() }
                torchObserver = Observer { refreshControls() }
                boundCamera.cameraInfo.zoomState.observe(lifecycleOwner, zoomObserver!!)
                boundCamera.cameraInfo.torchState.observe(lifecycleOwner, torchObserver!!)
                refreshControls()
                applyControls(settings, preserveError = true)
                val observer = Observer<CameraState> { state ->
                    if (!active || request != generation) return@Observer
                    val error = state.error
                    if (error == null) {
                        cancelRecoverableError()
                        return@Observer
                    }
                    val message = when (error.code) {
                        CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE ->
                            R.string.error_camera_busy
                        CameraState.ERROR_CAMERA_DISABLED -> R.string.error_camera_disabled
                        CameraState.ERROR_CAMERA_FATAL_ERROR -> R.string.error_camera_fatal
                        CameraState.ERROR_STREAM_CONFIG -> R.string.error_camera_configuration
                        else -> R.string.error_camera_generic
                    }
                    if (error.type == CameraState.ErrorType.RECOVERABLE) {
                        // CameraX may briefly wait for the old lens to close during a switch.
                        // Let its automatic recovery finish before presenting a retry action.
                        if (recoverableErrorTimeout == null) {
                            recoverableErrorTimeout = Runnable {
                                recoverableErrorTimeout = null
                                if (active && request == generation) fail(message)
                            }.also { handler.postDelayed(it, RECOVERABLE_ERROR_GRACE_MS) }
                        }
                    } else {
                        fail(message)
                    }
                }
                cameraObserver = observer
                boundCamera.cameraInfo.cameraState.observe(lifecycleOwner, observer)
            } catch (_: SecurityException) {
                fail(R.string.error_permission)
            } catch (_: Exception) {
                fail(R.string.error_camera_generic)
            }
        }, executor)
    }

    @MainThread
    fun stop() {
        pendingRebind?.let { handler.removeCallbacks(it) };pendingRebind=null
        active = false
        previewReady = false
        analysisReady = false
        generation += 1
        requestedLens = null
        cancelTimeout()
        cancelRecoverableError()
        controlVersion += 1
        val oldCamera = camera
        if (oldCamera?.cameraInfo?.hasFlashUnit() == true) oldCamera.cameraControl.enableTorch(false)
        zoomObserver?.let { oldCamera?.cameraInfo?.zoomState?.removeObserver(it) }
        torchObserver?.let { oldCamera?.cameraInfo?.torchState?.removeObserver(it) }
        zoomObserver = null
        torchObserver = null
        cameraObserver?.let { observer -> camera?.cameraInfo?.cameraState?.removeObserver(observer) }
        cameraObserver = null
        camera = null
        val oldPreview = boundPreview
        val oldAnalysis = boundAnalysis
        boundPreview = null
        boundAnalysis = null
        usbVideoServer.endCameraSession()
        oldAnalysis?.clearAnalyzer()
        val oldUseCases = listOfNotNull(oldPreview, oldAnalysis).toTypedArray()
        if (oldUseCases.isNotEmpty()) provider?.unbind(*oldUseCases)
        onState(State.Stopped)
    }

    @MainThread
    fun release() {
        if (released) return
        released = true
        usbVideoServer.onSettingsChanged = null
        usbVideoServer.onFocusRequest = null
        stop()
        analysisExecutor.shutdown()
    }

    private fun fail(messageRes: Int) {
        val failedId = currentTarget?.option?.id
        val restoreId = lastGoodCameraId?.takeIf { it != failedId }
            ?: targets.values.firstOrNull { it.option.physicalCameraId == null && it.option.id != failedId }?.option?.id
        val lens = requestedLens ?: CameraSelector.LENS_FACING_BACK
        stop()
        if (!fallbackAttempted && restoreId != null && failedId != null && usbVideoServer.currentSettings().cameraId == failedId) {
            controlError = "镜头 ${currentTarget?.option?.label ?: failedId} 无法输出画面，已回退：${context.getString(messageRes)}"
            usbVideoServer.selectInitialCamera(restoreId)
            // One automatic fallback only; a failure in the known/default camera is surfaced.
            fallbackAttempted = true
            currentTarget = null
            startInternal(lens, true)
            return
        }
        usbVideoServer.endCameraSession("error", context.getString(messageRes))
        onState(State.Failed(messageRes))
    }

    @MainThread
    fun updateRotation(rotation: Int) {
        targetRotation = rotation
        boundPreview?.targetRotation = rotation
        boundAnalysis?.targetRotation = rotation
    }

    private fun encode(image: ImageProxy, mirror: Boolean, quality: Int): Pair<ByteArray, Size> {
        val planes = image.planes.map { Nv21ImageConverter.Plane(it.buffer, it.rowStride, it.pixelStride) }
        val nv21 = if (image.imageInfo.rotationDegrees == 0)
            Nv21ImageConverter.copyNv21(image.width, image.height, planes, mirror, nv21Buffer)
        else Nv21ImageConverter.convert(image.width, image.height, planes, image.imageInfo.rotationDegrees, mirror)
        nv21Buffer = nv21.bytes
        jpegOutput.reset()
        check(YuvImage(nv21.bytes, ImageFormat.NV21, nv21.width, nv21.height, null)
            .compressToJpeg(Rect(0, 0, nv21.width, nv21.height), quality, jpegOutput))
        return jpegOutput.toByteArray() to Size(nv21.width, nv21.height)
    }

    private fun awaitFrames(request: Int) {
        if (frameTimeout != null) return
        frameTimeout = Runnable {
            frameTimeout = null
            if (active && request == generation) fail(R.string.error_timeout)
        }.also { handler.postDelayed(it, FRAME_TIMEOUT_MS) }
    }

    private fun cancelTimeout() {
        frameTimeout?.let(handler::removeCallbacks)
        frameTimeout = null
    }

    private fun cancelRecoverableError() {
        recoverableErrorTimeout?.let(handler::removeCallbacks)
        recoverableErrorTimeout = null
    }

    private fun selector(facing: Int) = CameraSelector.Builder().requireLensFacing(facing).build()

    private fun selectorForId(id: String) = CameraSelector.Builder().addCameraFilter { infos ->
        infos.filter { Camera2CameraInfo.from(it).cameraId == id }
    }.build()

    private fun focusAction() = FocusMeteringAction.Builder(
        SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f),
        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
    ).setAutoCancelDuration(5, TimeUnit.SECONDS).build()

    private fun controlsFor(target: Target, current: Camera? = null): CameraControls {
        val info = current?.cameraInfo ?: target.info
        val zoom = info.zoomState.value
        val exposure = info.exposureState
        val physical = target.option.physicalCameraId != null
        val physicalExposure = target.metadata.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val exposureMin = maxOf(exposure.exposureCompensationRange.lower, physicalExposure?.lower ?: Int.MIN_VALUE)
        val exposureMax = minOf(exposure.exposureCompensationRange.upper, physicalExposure?.upper ?: Int.MAX_VALUE)
        val validRange = exposureMin <= exposureMax && exposure.isExposureCompensationSupported
        return CameraControls(
            // A forced physical output has no independent CameraX zoom control. Keep it at
            // optical 1x instead of misrepresenting logical-camera zoom as physical zoom.
            zoomMin = if (physical) 1f else zoom?.minZoomRatio ?: 1f,
            zoomMax = if (physical) 1f else zoom?.maxZoomRatio ?: 1f,
            zoomRatio = if (physical) 1f else zoom?.zoomRatio ?: 1f,
            exposureMin = if (validRange) exposureMin else 0,
            exposureMax = if (validRange) exposureMax else 0,
            exposureStep = exposure.exposureCompensationStep.toFloat(),
            exposureIndex = exposure.exposureCompensationIndex,
            torchSupported = info.hasFlashUnit() && (!physical || target.metadata.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true),
            torchEnabled = info.torchState.value == TorchState.ON,
            focusSupported = info.isFocusMeteringSupported(focusAction()), controlsError = controlError,
        )
    }

    private fun refreshControls() {
        val target = currentTarget ?: return
        val current = camera ?: return
        if (active) usbVideoServer.updateCameraControls(usbGeneration, target.option.id, controlsFor(target, current))
    }

    private fun applyControls(settings: VideoSettings, preserveError: Boolean = false) {
        val current = camera ?: return
        val target = currentTarget ?: return
        if (!active || settings.cameraId != target.option.id) return
        if (!preserveError) controlError = null
        val version = ++controlVersion
        val limits = controlsFor(target, current)
        fun track(future: ListenableFuture<*>, label: String) {
            future.addListener({
                if (camera === current && version == controlVersion && active) {
                    try { future.get() } catch (_: Exception) { controlError = "$label 失败，请重试" }
                    refreshControls()
                }
            }, executor)
        }
        track(current.cameraControl.setZoomRatio(settings.zoomRatio.coerceIn(limits.zoomMin, limits.zoomMax)), "缩放设置")
        if (current.cameraInfo.exposureState.isExposureCompensationSupported)
            track(current.cameraControl.setExposureCompensationIndex(settings.exposureIndex.coerceIn(limits.exposureMin, limits.exposureMax)), "曝光设置")
        if (current.cameraInfo.hasFlashUnit()) track(current.cameraControl.enableTorch(settings.torch && limits.torchSupported), "补光灯设置")
        refreshControls()
    }

    private fun focusCenter() {
        val current = camera ?: return
        if (!active || !current.cameraInfo.isFocusMeteringSupported(focusAction())) return
        controlError = null
        val request = generation
        val future = current.cameraControl.startFocusAndMetering(focusAction())
        future.addListener({
            if (camera === current && active && request == generation) {
                try {
                    val result = future.get()
                    if (!result.isFocusSuccessful) controlError = "中心测光已完成，对焦未确认；请保持镜头稳定后重试"
                } catch (_: Exception) { controlError = "中心对焦测光失败，请重试" }
                refreshControls()
            }
        }, executor)
    }

    private fun discoverCameras(currentProvider: ProcessCameraProvider) {
        val manager = context.getSystemService(CameraManager::class.java)
        val all = LinkedHashMap<String, Target>()
        val defaultBackId = runCatching { Camera2CameraInfo.from(currentProvider.getCameraInfo(selector(CameraSelector.LENS_FACING_BACK))).cameraId }.getOrNull()
        val reference = defaultBackId?.let { runCatching { manager.getCameraCharacteristics(it) }.getOrNull() }
        val referenceFov = reference?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.let { focal ->
            reference.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width?.takeIf { it > 0 }?.let { focal / it }
        }
        for (info in currentProvider.availableCameraInfos) {
            val id = Camera2CameraInfo.from(info).cameraId
            val metadata = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            fun option(cameraId: String, data: CameraCharacteristics, physical: String? = null): CameraOption {
                val facing = when (data.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    else -> "external"
                }
                val focal = data.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val label = CameraSettingsRules.classifyFacing(facing, physical == null && id == defaultBackId,
                    focal, data.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width, referenceFov)
                val suffix = if (physical == null) "ID $id" else "物理 $physical / 通道 $id"
                return CameraOption(cameraId, "$label · $suffix", facing, focal, id, physical)
            }
            all[id] = Target(option(id, metadata), info, metadata)
            if (Build.VERSION.SDK_INT >= 28) {
                for (physical in metadata.physicalCameraIds.sorted()) {
                    val data = runCatching { manager.getCameraCharacteristics(physical) }.getOrNull() ?: continue
                    val focal = data.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                    val parentFocal = metadata.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                    if (focal != null && parentFocal != null && kotlin.math.abs(focal - parentFocal) < 0.01f) continue
                    // Only IDs explicitly advertised by a public logical camera are candidates.
                    // Hidden HAL-only IDs are never opened or added independently.
                    val key = "$id/$physical"
                    all[key] = Target(option(key, data, physical), info, data)
                }
            }
        }
        targets = all
        usbVideoServer.setCameraCatalog(all.values.map { it.option }, all.mapValues { controlsFor(it.value).copy(controlsError = null) },
            "仅列出系统开放的相机及其声明的物理通道。物理副摄首次选择后验证输出，失败会回退；物理通道固定光学1倍。")
    }

    private fun constrainPhysicalFps(target: Target, maxWidth: Int, logicalRanges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (target.option.physicalCameraId == null) return logicalRanges
        val supported = target.metadata.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower to it.upper }?.toSet() ?: return emptyList()
        val map = target.metadata.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888).filter { maxOf(it.width, it.height) <= maxWidth }
        if (sizes.isEmpty()) return emptyList()
        val durations = sizes.map { map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, it) }.filter { it > 0 }
        val maxSafeFps = durations.maxOrNull()?.let { (1_000_000_000.0 / it).toInt().coerceAtLeast(1) } ?: 30
        return logicalRanges.filter { it in supported && it.second <= maxSafeFps }
    }

    private companion object {
        const val FRAME_TIMEOUT_MS = 12_000L
        const val RECOVERABLE_ERROR_GRACE_MS = 1_500L
    }
}
