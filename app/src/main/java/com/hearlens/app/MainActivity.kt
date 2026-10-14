package com.hearlens.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var controller: CameraPreviewController
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var overlay: ScrollView
    private lateinit var overlayTitle: TextView
    private lateinit var overlayBody: TextView
    private lateinit var overlayAction: Button
    private lateinit var settingsAction: Button
    private lateinit var switchButton: Button
    private lateinit var pauseButton: Button
    private lateinit var usbButton: Button
    private lateinit var usbStatus: TextView
    private lateinit var usbServer: UsbVideoServer
    private lateinit var screenRoot: FrameLayout
    private lateinit var regularInterface: View
    private lateinit var vrLayer: FrameLayout
    private lateinit var returnView: LocalStereoView
    private lateinit var stereo: LocalStereoEngine
    private lateinit var wifi: WifiVideoServer
    private lateinit var phoneSettings: PhoneSettingsDialog
    private lateinit var direction:UsbDirectionController
    private lateinit var directionSettings:DirectionSettingsDialog
    private lateinit var sharedAudio:SharedPhoneAudio
    private lateinit var environment:EnvironmentSoundSession
    private lateinit var environmentSettings:SoundPerceptionDialog
    private var perceptionWanted=false
    private var perceptionStartPending=false
    private var perceptionMicRequested=false
    private lateinit var headsetMenu: HeadsetMenu
    private lateinit var quickControls:QuickControls
    private lateinit var remote: RemoteInput
    private lateinit var ptt:PttClient
    private var pttOwnsAudio=false
    private var pttTarget="zh"
    private var desktopPttLease:String?=null
    private var desktopPttDeadline=0L
    private var desktopSideCapture:SideCalibrationCapture?=null
    private var desktopSideMessage="未开始校准"
    private lateinit var liveCaptions:LiveCaptionSession
    private lateinit var captionOverlay:View
    private lateinit var captionButton:Button
    private lateinit var vrSettingsButton: Button
    private lateinit var vrButton: Button
    private lateinit var exitVrButton: Button
    private var vrEnabled = false
    private var interfaceLandscape = false
    private var lastDisplayRotation: Int? = null
    private var statusTick = 0
    private var usbEnabled = true
    private val uiHandler = Handler(Looper.getMainLooper())
    private val usbStatusUpdater = object : Runnable {
        override fun run() {
            if (statusTick++ % 4 == 0) { if (foreground && usbEnabled) wifi.reconcile(); refreshUsbUi();headsetMenu.refresh() }
            if (::returnView.isInitialized) returnView.refreshFreshness()
            if(::liveCaptions.isInitialized) {
                stereo.captionDiagnostic=liveCaptions.diagnostic()
                captionButton.text=if(liveCaptions.active)"语音字幕 · ${liveCaptions.status()}" else "实时语音字幕 / 设置"
                if(!vrEnabled)captionOverlay.invalidate()
            }
            refreshRotation()
            syncSoundPerception()
            updateDesktopControl()
            if(foreground && !pttOwnsAudio && perceptionWanted && !perceptionStartPending && !environment.enabled) {
                val reason=environment.status
                stopSoundPerception();stereo.showPerceptionNotice(false,reason)
                headsetMenu.refresh()
            }
            if (foreground) uiHandler.postDelayed(this, 250L)
        }
    }

    private var foreground = false
    private var userPaused = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var availableCameras = emptySet<Int>()
    private var cameraState: CameraPreviewController.State = CameraPreviewController.State.Stopped
    private val preferences by lazy { getSharedPreferences("camera_preferences", MODE_PRIVATE) }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        syncPreview()
    }
    private val microphoneLauncher=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted && perceptionWanted) {perceptionStartPending=true;syncSoundPerception()}
        if(!granted && perceptionWanted) {stopSoundPerception();stereo.showPerceptionNotice(false,"未授权麦克风")}
        else if(granted && perceptionWanted)stereo.showPerceptionNotice(true)
        Toast.makeText(this,if(granted)if(perceptionWanted)"麦克风已授权，正在开启环境音感知" else "麦克风已授权，请再次手动开启所需的声音功能" else "未授权麦克风，声音类型识别未开启",Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("HearLensDisplay", "create restored=${savedInstanceState != null} vr=${savedInstanceState?.getBoolean(KEY_VR)}")
        userPaused = savedInstanceState?.getBoolean(KEY_PAUSED) ?: false
        perceptionWanted=savedInstanceState?.getBoolean(KEY_PERCEPTION) ?: false
        perceptionMicRequested=savedInstanceState?.getBoolean(KEY_PERCEPTION_MIC) ?: false
        lensFacing = savedInstanceState?.getInt(KEY_LENS, CameraSelector.LENS_FACING_BACK)
            ?: CameraSelector.LENS_FACING_BACK
        usbEnabled = savedInstanceState?.getBoolean(KEY_USB)
            ?: preferences.getBoolean(KEY_USB, true)
        vrEnabled = savedInstanceState?.getBoolean(KEY_VR) ?: false
        usbServer = UsbVideoServer(this)
        stereo = LocalStereoEngine(this, usbServer)
        direction=UsbDirectionController(this)
        directionSettings=DirectionSettingsDialog(this,direction)
        stereo.directionProvider=direction::snapshot
        sharedAudio=SharedPhoneAudio(this)
        pttTarget=getSharedPreferences("ptt_v13",MODE_PRIVATE).getString("target","zh")?.takeIf {it in listOf("zh","en")} ?: "zh"
        ptt=PttClient(sharedAudio::exclusiveMicrophone,{PttAudioPlayer(this)},clock={android.os.SystemClock.uptimeMillis()})
        ptt.changed={state->uiHandler.post {
            if(!isDestroyed) {
                stereo.pttState=if(foreground)state else null;stereo.onFrame?.invoke()
                if(!state.busy && pttOwnsAudio) {
                    desktopPttLease=null
                    pttOwnsAudio=false
                    if(foreground) {liveCaptions.resumeAfterPtt();perceptionStartPending=perceptionWanted;syncSoundPerception()}
                }
                if(!state.busy)uiHandler.postDelayed({if(stereo.pttState?.generation==state.generation && !ptt.state.busy) {stereo.pttState=null;stereo.onFrame?.invoke()}},2200)
            }
        }}
        liveCaptions=LiveCaptionSession(this,stereo,sharedAudio::microphone)
        environment=EnvironmentSoundSession(this,sharedAudio,direction) {stereo.onFrame?.invoke()}
        environmentSettings=SoundPerceptionDialog(this,environment,direction,::startSoundPerception,::stopSoundPerception,{
            directionSettings.show {if(foreground && !isFinishing && !isDestroyed)environmentSettings.show()}
        },{perceptionWanted})
        stereo.environment=environment
        wifi = WifiVideoServer(this, stereo, usbServer)
        wifi.onViewRequest = { enabled -> uiHandler.post { if(foreground && !isFinishing)setVrMode(enabled) } }
        headsetMenu=HeadsetMenu(stereo,usbServer,liveCaptions,::applyCaptionChoice,{usbEnabled},{
            usbEnabled=!usbEnabled;preferences.edit { putBoolean(KEY_USB,usbEnabled) };wifi.enabled=usbEnabled
            if(foreground)wifi.reconcile();refreshUsbUi()
        },{vrEnabled},{setVrMode(!vrEnabled)},{perceptionWanted},{if(perceptionWanted)stopSoundPerception() else startSoundPerception()},
            {pttTarget},{pttTarget=it;getSharedPreferences("ptt_v13",MODE_PRIVATE).edit {putString("target",it)}})
        quickControls=QuickControls(stereo,usbServer,liveCaptions,{headsetMenu.close()},::applyCaptionChoice)
        remote=RemoteInput(this,stereo,{vrEnabled && foreground},{action->if(!quickControls.command(action))headsetMenu.command(action)},
            {setVrMode(true)},::beginPtt,quickControls::language,quickControls::vector,quickControls::cancel,
            ::togglePerceptionShortcut,{foreground},{ptt.end()},{ptt.cancel("按键或窗口被中断，本轮已取消")})
        wifi.onRemoteCalibration={ start -> uiHandler.post { if(foreground) { if(start)remote.beginCalibration() else remote.cancelCalibration() } } }
        phoneSettings = PhoneSettingsDialog(this,stereo,usbServer,wifi,remote::setup,::showSpeechStatus,headsetMenu,environmentSettings::show)
        wifi.onControlSnapshot={onControlThread {desktopControlSnapshot()}}
        wifi.onControlCommand={request->onControlThread {applyDesktopControl(request)}}
        headsetMenu.onAuxiliary=phoneSettings::auxiliary
        usbServer.onLocalBitmap = stereo::render
        usbServer.onLocalClear = { stereo.clear() }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        buildInterface()
        stereo.onFrame = { returnView.requestRender();if(!vrEnabled)captionOverlay.postInvalidateOnAnimation() }
        stereo.onConfiguration = { uiHandler.post { if(!isFinishing) { returnView.configureSurface();headsetMenu.refresh() } } }
        usbServer.onVrModeRequest = { enabled, requestGeneration ->
            uiHandler.post { if (foreground && !isFinishing) setVrMode(enabled, requestGeneration) }
        }
        controller = CameraPreviewController(this, this, previewView, usbVideoServer = usbServer, onState = { state ->
            cameraState = state
            render()
        }, onCamerasAvailable = { cameras ->
            availableCameras = cameras
            render()
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if(stereo.remoteLearning!=null)remote.cancelCalibration()
                else if(quickControls.opened)quickControls.cancel()
                else if (vrEnabled) headsetMenu.command("back") else finish()
            }
        })
        setVrMode(vrEnabled)
        updateDisplayInfo()
        render()
        if(intent.action==Intent.ACTION_APPLICATION_PREFERENCES)screenRoot.post {phoneSettings.show()}
        // Opens the test page only; never authorizes the microphone or starts a session.
        if(savedInstanceState==null && intent.getBooleanExtra("open_speech",false))
            startActivity(Intent(this,SpeechActivity::class.java).apply {
                if(BuildConfig.DEBUG)putExtra("probe_engine",intent.getStringExtra("probe_engine"))
            })
    }

    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if(intent.action==Intent.ACTION_APPLICATION_PREFERENCES && ::phoneSettings.isInitialized)phoneSettings.show()
        if(intent.action==android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED && foreground && ::direction.isInitialized)direction.resume()
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.d("HearLensDisplay", "resume vr=$vrEnabled")
        foreground = true
        direction.resume()
        perceptionStartPending=perceptionWanted
        syncSoundPerception()
        wifi.enabled = usbEnabled
        if (usbEnabled) wifi.reconcile()
        usbServer.setVrEnabled(vrEnabled)
        updateDisplayInfo()
        syncPreview()
        uiHandler.post(usbStatusUpdater)
    }

    override fun onPause() {
        android.util.Log.d("HearLensDisplay", "pause vr=$vrEnabled")
        foreground = false
        desktopPttLease=null;if(desktopSideCapture!=null)desktopSideMessage="应用进入后台，校准已取消";desktopSideCapture=null
        ptt.cancel("离开观看界面，本轮已取消");pttOwnsAudio=false;stereo.pttState=null
        liveCaptions.background()
        environment.stop()
        stereo.clearPerceptionNotice()
        remote.cancel()
        uiHandler.removeCallbacks(usbStatusUpdater)
        controller.stop()
        wifi.stop()
        usbServer.stop()
        returnView.clear("取景已停止")
        super.onPause()
    }

    override fun onStop() {
        direction.background()
        super.onStop()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(usbStatusUpdater)
        phoneSettings.dismiss()
        directionSettings.dismiss()
        environmentSettings.dismiss()
        stereo.environment=null
        environment.release()
        stereo.directionProvider=null
        direction.release()
        remote.release()
        ptt.release()
        liveCaptions.release()
        sharedAudio.release()
        controller.release()
        usbServer.onReturnFrame = null
        usbServer.onVrModeRequest = null
        usbServer.stop()
        returnView.release()
        stereo.onConfiguration=null
        wifi.stop()
        usbServer.onLocalBitmap = null
        wifi.onViewRequest=null
        wifi.onControlSnapshot=null;wifi.onControlCommand=null
        usbServer.onLocalClear = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_PAUSED, userPaused)
        outState.putInt(KEY_LENS, lensFacing)
        outState.putBoolean(KEY_USB, usbEnabled)
        outState.putBoolean(KEY_PERCEPTION,perceptionWanted)
        outState.putBoolean(KEY_PERCEPTION_MIC,perceptionMicRequested)
        outState.putBoolean(KEY_VR, vrEnabled)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        android.util.Log.d("HearLensDisplay", "configuration orientation=${newConfig.orientation} vr=$vrEnabled")
        if (!vrEnabled) { recreate(); return }
        // Keep the capture session and USB socket alive while the return screen turns sideways.
        screenRoot.post {
            updateDisplayInfo()
            preserveCaptureViewport()
            refreshRotation()
            ViewCompat.requestApplyInsets(screenRoot)
        }
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

    private fun syncPreview() {
        if (foreground && hasCameraPermission() && !userPaused) {
            controller.start(lensFacing)
        } else {
            controller.stop()
        }
        render()
    }

    private fun requestPermission() {
        preferences.edit { putBoolean(KEY_REQUESTED, true) }
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_settings, Toast.LENGTH_LONG).show()
        }
    }

    private fun buildInterface() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        interfaceLandscape = landscape
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(system.left + dp(20), system.top + dp(12), system.right + dp(20), system.bottom + dp(12))
            insets
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val name = text(R.string.app_title, 27f, WHITE).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        header.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))
        val helpButton = button(R.string.help, false).apply {
            contentDescription = getString(R.string.help_description)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { showHelp() }
        }
        header.addView(helpButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        val statusRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        statusDot = View(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(9) })
        status = text(R.string.status_permission, 16f, MUTED).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statusRow.addView(status, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(statusRow, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(10)
            bottomMargin = dp(14)
        })

        val viewport = FrameLayout(this).apply {
            background = rounded(PANEL, 24f)
            clipToOutline = true
        }
        previewView = PreviewView(this).apply {
            scaleType=PreviewView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.preview_description)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        viewport.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))
        captionOverlay=object:View(this) {
            override fun onDraw(canvas:android.graphics.Canvas) {
                stereo.drawMonoCaptions(canvas,width,height)
                if(stereo.hasPerceptionNotice())postInvalidateOnAnimation()
            }
        }.apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        viewport.addView(captionOverlay,FrameLayout.LayoutParams(MATCH,MATCH))
        overlay = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(PANEL)
        }
        val overlayContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(26), dp(24), dp(26))
        }
        overlayTitle = text(R.string.welcome_title, 25f, WHITE).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            ViewCompat.setAccessibilityHeading(this, true)
        }
        overlayBody = text(R.string.welcome_body, 17f, MUTED).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        overlayAction = button(R.string.allow_camera, true)
        settingsAction = button(R.string.open_settings, false).apply { setOnClickListener { openAppSettings() } }
        overlayContent.addView(overlayTitle, LinearLayout.LayoutParams(MATCH, WRAP))
        overlayContent.addView(overlayBody, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(15) })
        overlayContent.addView(overlayAction, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(24) })
        overlayContent.addView(settingsAction, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        overlay.addView(overlayContent, FrameLayout.LayoutParams(MATCH, WRAP))
        viewport.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))

        switchButton = button(R.string.choose_lens, false).apply { setOnClickListener { showCameraChooser() } }
        pauseButton = button(R.string.pause, true).apply {
            setOnClickListener {
                userPaused = !userPaused
                syncPreview()
            }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val phase = text(R.string.phase_label, 13f, MUTED).apply { gravity = Gravity.CENTER }
        controls.addView(phase, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
        val buttons = LinearLayout(this).apply {
            orientation = if (landscape || resources.configuration.fontScale >= 1.5f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        val stacked = buttons.orientation == LinearLayout.VERTICAL
        buttons.addView(switchButton, LinearLayout.LayoutParams(if (stacked) MATCH else 0, WRAP, if (stacked) 0f else 1f))
        buttons.addView(pauseButton, LinearLayout.LayoutParams(if (stacked) MATCH else 0, WRAP, if (stacked) 0f else 1f).apply {
            if (stacked) topMargin = dp(10) else marginStart = dp(12)
        })
        controls.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP))
        usbStatus = text(R.string.usb_waiting, 14f, MUTED).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        controls.addView(usbStatus, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        usbButton = button(R.string.usb_disable, false).apply {
            setOnClickListener {
                usbEnabled = !usbEnabled
                preferences.edit { putBoolean(KEY_USB, usbEnabled) }
                wifi.enabled = usbEnabled
                if (usbEnabled && this@MainActivity.foreground) {
                    wifi.reconcile()
                    phoneSettings.connection()
                } else {
                    wifi.stop()
                }
                refreshUsbUi()
            }
        }
        controls.addView(usbButton, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        vrButton = button(R.string.vr_enter, true).apply {
            setOnClickListener { setVrMode(true) }
        }
        controls.addView(vrButton, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        controls.addView(button(R.string.phone_settings, false).apply { setOnClickListener { phoneSettings.show() } }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        captionButton=button(R.string.live_caption_settings,true).apply { setOnClickListener { showCaptionSettings() } }
        controls.addView(captionButton,LinearLayout.LayoutParams(MATCH,WRAP).apply { topMargin=dp(8) })
        controls.addView(button(R.string.speech_test, false).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity,SpeechActivity::class.java)) }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        usbStatus.setOnClickListener { phoneSettings.connection() }
        controls.addView(text(R.string.privacy_note, 13f, MUTED).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        if (landscape) {
            val contentRow = LinearLayout(this)
            contentRow.addView(viewport, LinearLayout.LayoutParams(0, MATCH, 1f))
            val controlsScroll = ScrollView(this).apply { isFillViewport = true }
            controls.gravity = Gravity.CENTER_VERTICAL
            controlsScroll.addView(controls, FrameLayout.LayoutParams(MATCH, WRAP))
            val panelWidth = (resources.configuration.screenWidthDp * 0.32f).roundToInt().coerceIn(200, 300)
            contentRow.addView(controlsScroll, LinearLayout.LayoutParams(dp(panelWidth), MATCH).apply { marginStart = dp(18) })
            root.addView(contentRow, LinearLayout.LayoutParams(MATCH, 0, 1f))
        } else {
            root.addView(viewport, LinearLayout.LayoutParams(MATCH, 0, 1f))
            root.addView(controls, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })
        }
        regularInterface = root
        screenRoot = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        screenRoot.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        vrLayer = FrameLayout(this).apply { visibility = View.GONE }
        returnView = LocalStereoView(this, stereo)
        vrLayer.addView(returnView, FrameLayout.LayoutParams(MATCH, MATCH))
        exitVrButton = button(R.string.vr_exit, false).apply {
            visibility = View.GONE
            setOnClickListener { setVrMode(false) }
        }
        vrLayer.addView(exitVrButton, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(16)
            rightMargin = dp(48)
        })
        vrSettingsButton = button(R.string.phone_settings, false).apply {
            visibility = View.GONE
            setOnClickListener { phoneSettings.show() }
        }
        vrLayer.addView(vrSettingsButton, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { topMargin = dp(16); leftMargin = dp(48) })
        returnView.setOnTouchListener { view,event ->
            if(event.action==MotionEvent.ACTION_UP) {
                view.performClick()
                if(quickControls.opened)quickControls.touch(event.x,event.y,view.width,view.height)
                else headsetMenu.touch(event.x,event.y,view.width,view.height)
            };true
        }
        returnView.setOnClickListener { /* Touch uses per-eye menu coordinates. */ }
        screenRoot.addView(vrLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(screenRoot)
        ViewCompat.requestApplyInsets(root)
    }

    private val hideVrControls = Runnable { if (::exitVrButton.isInitialized) { exitVrButton.visibility = View.GONE; vrSettingsButton.visibility = View.GONE } }

    private fun updateDisplayInfo() {
        val metrics = resources.displayMetrics
        val bounds = if (Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds else null
        val w = bounds?.width() ?: metrics.widthPixels
        val h = bounds?.height() ?: metrics.heightPixels
        usbServer.setDisplayInfo(maxOf(w, h), minOf(w, h))
        @Suppress("DEPRECATION")
        val display=windowManager.defaultDisplay
        val mode=display.supportedModes.maxWithOrNull(compareBy<android.view.Display.Mode> { it.physicalWidth.toLong()*it.physicalHeight }.thenBy { it.refreshRate })
        stereo.dimensions(mode?.physicalWidth ?: w,mode?.physicalHeight ?: h,(mode?.refreshRate ?: display.refreshRate).roundToInt())
        if(vrEnabled && mode!=null) window.attributes=window.attributes.apply { preferredDisplayModeId=mode.modeId }
        returnView.configureSurface()
    }

    private fun refreshRotation() {
        if (!::controller.isInitialized || !::previewView.isInitialized) return
        val rotation = previewView.display?.rotation ?: return
        if (lastDisplayRotation != rotation) {
            lastDisplayRotation = rotation
            controller.updateRotation(rotation)
        }
    }

    private fun preserveCaptureViewport() {
        // The portrait controls may otherwise consume all height after entering landscape.
        // This layer stays covered by the returned image but its camera Surface remains usable.
        val metrics = resources.displayMetrics
        regularInterface.layoutParams = FrameLayout.LayoutParams(MATCH,
            if (vrEnabled) maxOf(metrics.widthPixels, metrics.heightPixels) else MATCH)
    }

    private fun setVrMode(enabled: Boolean, requestGeneration: Long? = null) {
        android.util.Log.d("HearLensDisplay", "display enabled=$enabled remote=${requestGeneration != null} caller=${Throwable().stackTrace.getOrNull(1)}")
        if (requestGeneration != null) {
            if (!usbServer.applyVrModeRequest(enabled, requestGeneration)) return
        } else usbServer.setVrEnabled(enabled)
        val wasEnabled = vrEnabled
        vrEnabled = enabled
        headsetMenu.close();remote.cancel()
        returnView.clear()
        vrLayer.visibility = if (enabled) View.VISIBLE else View.GONE
        preserveCaptureViewport()
        exitVrButton.visibility = View.GONE
        vrSettingsButton.visibility = View.GONE
        regularInterface.importantForAccessibility = if (enabled) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (enabled) {
            if(Build.VERSION.SDK_INT>=28) window.attributes=window.attributes.apply { layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.attributes=window.attributes.apply { preferredDisplayModeId=0 }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        refreshUsbUi()
        screenRoot.post { updateDisplayInfo() }
        if (wasEnabled && !enabled && interfaceLandscape !=
            (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)) recreate()
    }

    override fun dispatchKeyEvent(event:KeyEvent):Boolean = if(::remote.isInitialized && remote.key(event))true else super.dispatchKeyEvent(event)
    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(!hasFocus && ::remote.isInitialized)remote.cancel()
    }
    override fun dispatchGenericMotionEvent(event:MotionEvent):Boolean = if(::remote.isInitialized && remote.motion(event))true else super.dispatchGenericMotionEvent(event)
    override fun dispatchTouchEvent(event:MotionEvent):Boolean = if(::remote.isInitialized && remote.mouse(event))true else super.dispatchTouchEvent(event)

    private fun render() {
        refreshUsbUi()
        val permitted = hasCameraPermission()
        switchButton.setText(R.string.choose_lens)
        switchButton.isEnabled = permitted && usbServer.cameraOptions().isNotEmpty()
        pauseButton.setText(if (userPaused) R.string.resume else R.string.pause)
        pauseButton.isEnabled = permitted
        vrButton.isEnabled = permitted && !userPaused
        overlayAction.visibility = View.GONE
        settingsAction.visibility = View.GONE
        overlay.visibility = View.VISIBLE
        previewView.keepScreenOn = false
        statusDot.background = rounded(MUTED, 8f)

        if (!permitted) {
            status.setText(R.string.status_permission)
            val requested = preferences.getBoolean(KEY_REQUESTED, false)
            val canExplain = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            val goToSettings = requested && !canExplain
            overlayTitle.setText(if (!requested) R.string.welcome_title else if (goToSettings) R.string.settings_title else R.string.denied_title)
            overlayBody.setText(if (!requested) R.string.welcome_body else if (goToSettings) R.string.settings_body else R.string.denied_body)
            action(if (goToSettings) R.string.open_settings else if (requested) R.string.request_again else R.string.allow_camera) {
                if (goToSettings) openAppSettings() else requestPermission()
            }
            if (requested && !goToSettings) settingsAction.visibility = View.VISIBLE
            return
        }
        if (userPaused) {
            status.setText(R.string.status_paused)
            overlayTitle.setText(R.string.paused_title)
            overlayBody.setText(R.string.paused_body)
            return
        }
        val cameraName = currentCameraLabel()
        when (val state = cameraState) {
            CameraPreviewController.State.Streaming -> {
                status.text = getString(R.string.status_streaming, cameraName)
                statusDot.background = rounded(ACCENT, 8f)
                overlay.visibility = View.GONE
                previewView.keepScreenOn = foreground
            }
            is CameraPreviewController.State.Failed -> {
                status.setText(R.string.status_error)
                statusDot.background = rounded(ERROR, 8f)
                overlayTitle.setText(R.string.error_title)
                overlayBody.setText(state.messageRes)
                action(R.string.retry) {
                    controller.stop()
                    syncPreview()
                }
                settingsAction.visibility = View.VISIBLE
            }
            CameraPreviewController.State.Connecting, CameraPreviewController.State.Stopped -> {
                status.text = if (foreground) getString(R.string.status_starting, cameraName)
                    else getString(R.string.status_background)
                overlayTitle.setText(R.string.starting_title)
                overlayBody.setText(R.string.starting_body)
            }
        }
    }

    private fun refreshUsbUi() {
        if (!::usbStatus.isInitialized || !::usbServer.isInitialized) return
        usbButton.setText(if (usbEnabled) R.string.usb_disable else R.string.usb_enable)
        val snapshot = usbServer.snapshot()
        if (::switchButton.isInitialized) switchButton.isEnabled = hasCameraPermission() && usbServer.cameraOptions().isNotEmpty()
        if (cameraState == CameraPreviewController.State.Streaming && hasCameraPermission() && !userPaused) {
            status.text = getString(R.string.status_streaming, currentCameraLabel())
        }
        val local = stereo.status().optDouble("localFps", 0.0)
        val prefix = "手机双眼合成 %.1f fps".format(local)
        usbStatus.text = when {
            !usbEnabled -> "$prefix · 无线共享已关闭"
            !foreground -> "取景与共享已停止"
            wifi.error != null -> "$prefix\n${wifi.error}"
            else -> "$prefix\n${wifi.url.substringBefore('#')}\n点此查看 / 复制完整观看链接"
        }
    }

    private fun action(label: Int, listener: () -> Unit) {
        overlayAction.setText(label)
        overlayAction.visibility = View.VISIBLE
        overlayAction.setOnClickListener { listener() }
    }

    private fun currentCameraLabel(): String {
        val selected = usbServer.snapshot().selectedCameraId
        return usbServer.cameraOptions().firstOrNull { it.id == selected }?.label
            ?: getString(if (lensFacing == CameraSelector.LENS_FACING_FRONT) R.string.front_camera else R.string.rear_camera)
    }

    private fun showCameraChooser() {
        val options = usbServer.cameraOptions()
        if (options.isEmpty()) {
            Toast.makeText(this, R.string.lenses_loading, Toast.LENGTH_SHORT).show()
            return
        }
        val selected = usbServer.snapshot().selectedCameraId
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_lens)
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOfFirst { it.id == selected }) { dialog, index ->
                if (!usbServer.requestCamera(options[index].id)) {
                    Toast.makeText(this, R.string.lens_not_available, Toast.LENGTH_LONG).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun togglePerceptionShortcut() {
        val enabled=!perceptionWanted
        if(enabled)startSoundPerception() else stopSoundPerception()
        stereo.showPerceptionNotice(enabled,if(enabled && pttOwnsAudio)"语音修复结束后恢复" else if(enabled && perceptionStartPending)"等待授权…" else "")
        android.util.Log.d("HearLensPerception","Y requested=$enabled pending=$perceptionStartPending running=${environment.enabled}")
        headsetMenu.refresh()
    }
    private fun startSoundPerception() {
        perceptionWanted=true;perceptionStartPending=true;perceptionMicRequested=false
        direction.connectForPerception();syncSoundPerception()
        if(stereo.hasPerceptionNotice() && stereo.perceptionNotice?.enabled==false)stereo.showPerceptionNotice(true)
    }
    private fun stopSoundPerception() {
        perceptionWanted=false;perceptionStartPending=false;environment.stop();direction.pausePerception()
        // A settings change or failed startup must never leave an old ON notification visible.
        if(stereo.hasPerceptionNotice() && stereo.perceptionNotice?.enabled==true)stereo.showPerceptionNotice(false)
    }
    private fun syncSoundPerception() {
        if(!foreground || pttOwnsAudio || !perceptionWanted || !perceptionStartPending || !::environment.isInitialized || direction.permissionPending)return
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) {
            perceptionStartPending=false;environment.start()
            stereo.perceptionNotice?.takeIf {it.enabled && it.message.isNotBlank()}?.let {stereo.perceptionNotice=it.copy(message="")}
        } else if(!perceptionMicRequested) {
            perceptionMicRequested=true;microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else perceptionStartPending=false
    }

    private fun beginPtt() {
        if(!foreground || pttOwnsAudio || ptt.state.busy)return
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this,"请允许麦克风，授权后重新按住肩键说话",Toast.LENGTH_LONG).show()
            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO);return
        }
        runCatching {
            val key=SpeechCredentials(this).read()
            require(key.isNotBlank()) {"请先在语音接口设置中保存 API Key"}
            val workspace=getSharedPreferences("speech_v7",MODE_PRIVATE).getString("workspace","").orEmpty().ifBlank {PttProtocol.WORKSPACE}
            val connection=AliyunConnection(workspace,key)
            quickControls.cancel();headsetMenu.close();phoneSettings.dismiss()
            pttOwnsAudio=true;environment.stop();perceptionStartPending=perceptionWanted;liveCaptions.suspendForPtt()
            ptt.begin(connection,pttTarget)
        }.onFailure {
            stereo.pttState=PttState(PttPhase.ERROR,message=it.message ?: "无法开始语音修复");stereo.onFrame?.invoke()
            uiHandler.postDelayed({if(!ptt.state.busy) {stereo.pttState=null;stereo.onFrame?.invoke()}},3000)
        }
    }

    private fun onControlThread(block:()->org.json.JSONObject):org.json.JSONObject {
        val task=java.util.concurrent.FutureTask {check(foreground && !isDestroyed) {"请在手机前台打开听见视界"};block()}
        uiHandler.post(task)
        return try {task.get(1800,java.util.concurrent.TimeUnit.MILLISECONDS)}catch(e:Exception) {task.cancel(false);throw IllegalStateException(e.cause?.message ?: "手机设置响应超时")}
    }
    private fun desktopControlSnapshot():org.json.JSONObject {
        val s=environment.settings;val d=direction.snapshot()
        return org.json.JSONObject().put("version",1).put("appVersion",BuildConfig.VERSION_NAME)
            .put("application",org.json.JSONObject().put("stereo",vrEnabled).put("paused",userPaused).put("wireless",usbEnabled))
            .put("captions",liveCaptions.styleJson().put("choice",liveCaptions.choiceIndex).put("status",liveCaptions.status()))
            .put("environment",org.json.JSONObject().put("enabled",perceptionWanted).put("intensity",s.intensity).put("depth",s.depth).put("spread",s.spread)
                .put("transparency",s.transparency).put("language",s.language).put("associateDirection",s.associateDirection).put("reducedMotion",s.reducedMotion).put("status",environment.status))
            .put("direction",d.json().put("rawText",d.rawText).put("rawHex",d.rawHex).put("sideCalibration",desktopSideMessage).put("calibrating",desktopSideCapture!=null)
                .put("devices",org.json.JSONArray(direction.devices().map {org.json.JSONObject().put("name",it.name).put("label",it.label).put("supported",it.supported)})))
            .put("speech",org.json.JSONObject().put("workspace",getSharedPreferences("speech_v7",MODE_PRIVATE).getString("workspace","")).put("configured",getSharedPreferences("speech_credentials",MODE_PRIVATE).contains("value"))
                .put("liveModel",AliyunProtocol.LIVE_MODEL).put("asrModel",AliyunProtocol.ASR_MODEL))
            .put("ptt",org.json.JSONObject().put("target",pttTarget).put("phase",ptt.state.phase.name).put("message",ptt.state.message).put("desktopHeld",desktopPttLease!=null))
            .put("permissions",org.json.JSONObject().put("camera",hasCameraPermission()).put("microphone",ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED))
            .put("remote",org.json.JSONObject().put("learning",stereo.remoteLearning ?: org.json.JSONObject.NULL).put("events",org.json.JSONArray(stereo.remoteEvents)))
    }
    private fun updateDesktopControl() {
        if(desktopPttLease!=null && android.os.SystemClock.uptimeMillis()>desktopPttDeadline) {
            desktopPttLease=null;ptt.cancel("电脑按住连接已中断，本轮已取消")
        }
        desktopSideCapture?.let {capture->
            capture.observe(direction.snapshot(),direction.calibrationGeneration())
            val elapsed=android.os.SystemClock.elapsedRealtime()-capture.startedAt
            if(elapsed>=DirectionSideCalibration.DURATION_MS) {
                desktopSideMessage=when(val result=direction.applySideCalibration(capture)) {
                    is SideCalibrationResult.Success->"${capture.side.label}校准完成 · ${result.sampleCount} 个有效角度"
                    is SideCalibrationResult.Rejected->result.reason
                };desktopSideCapture=null
            } else desktopSideMessage="${capture.side.label}采样中 · ${((4000-elapsed+999)/1000)} 秒 · ${capture.count} 个角度"
        }
    }
    private fun applyDesktopControl(request:org.json.JSONObject):org.json.JSONObject {
        val (section,v)=DesktopControlProtocol.validate(request)
        fun requireMicrophone()=check(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) {"请先在手机允许麦克风权限"}
        when(section) {
            "captions"->{
                if(v.has("choice")) {if(v.getInt("choice")>0)requireMicrophone();applyCaptionChoice(v.getInt("choice"))}
                else if(v.optString("action")=="clear")liveCaptions.clear()
                else if(v.optString("action")=="resetStyle")liveCaptions.configure(org.json.JSONObject())
                else {val style=liveCaptions.styleJson();v.keys().forEach {style.put(it,v.get(it))};liveCaptions.configure(style)}
            }
            "environment"->{
                if(v.optBoolean("enabled",false))requireMicrophone()
                val s=environment.settings
                environment.configure(s.copy(intensity=v.optInt("intensity",s.intensity),depth=v.optInt("depth",s.depth),spread=v.optInt("spread",s.spread),
                    transparency=v.optInt("transparency",s.transparency),language=v.optString("language",s.language),
                    associateDirection=v.optBoolean("associateDirection",s.associateDirection),reducedMotion=v.optBoolean("reducedMotion",s.reducedMotion)))
                if(v.has("enabled")) {if(v.getBoolean("enabled"))startSoundPerception() else stopSoundPerception()}
            }
            "direction"->{
                when(v.optString("action")) {
                    "connect"->{require(direction.devices().any {it.name==v.getString("deviceName") && it.supported}) {"未找到受支持的小板"};direction.connect(v.getString("deviceName"))}
                    "disconnect"->direction.disconnect()
                    "retryPermission"->direction.retryPermission()
                    "calibrateFront"->check(direction.calibrateFront()) {"没有新鲜方向角，未保存"}
                    "swapSides"->direction.swapLeftRight()
                    "startSide"->{val s=direction.snapshot();check(s.enabled && s.connected) {"请先连接小板"};check(desktopSideCapture==null) {"校准已在进行"}
                        desktopSideCapture=SideCalibrationCapture(android.os.SystemClock.elapsedRealtime(),s.config.frontDegrees,CalibrationSide.valueOf(v.getString("side")),direction.calibrationGeneration())
                        desktopSideMessage="正在采样，请在指定一侧持续发声"}
                    "cancelSide"->{desktopSideCapture=null;desktopSideMessage="已取消，未修改校准"}
                    else->{check(desktopSideCapture==null) {"请先完成或取消当前校准"};val s=direction.snapshot().config
                        direction.configure(s.copy(baudRate=v.optInt("baudRate",s.baudRate),format=DirectionFormat.valueOf(v.optString("format",s.format.name)),
                            frontDegrees=v.optDouble("frontDegrees",s.frontDegrees),clockwise=v.optBoolean("clockwise",s.clockwise),
                            staleMs=v.optLong("staleMs",s.staleMs),showOverlay=v.optBoolean("showOverlay",s.showOverlay)))}
                }
            }
            "speech"->{
                check(!liveCaptions.active && !ptt.state.busy) {"请先停止字幕与语音重构，再修改连接配置"}
                if(v.optString("action")=="clearKey")SpeechCredentials(this).clear()
                else {if(v.has("key"))SpeechCredentials(this).save(v.getString("key"));if(v.has("workspace"))getSharedPreferences("speech_v7",MODE_PRIVATE).edit {putString("workspace",v.getString("workspace"))}}
            }
            "ptt"->{
                if(v.has("target")) {pttTarget=v.getString("target");getSharedPreferences("ptt_v13",MODE_PRIVATE).edit {putString("target",pttTarget)}}
                else {val lease=v.getString("lease")
                    when(v.getString("action")) {
                        "start"->{requireMicrophone();check(!pttOwnsAudio && !ptt.state.busy) {"请等当前语音播放完成"};check(SpeechCredentials(this).read().isNotBlank()) {"尚未配置 API Key"}
                            desktopPttLease=lease;desktopPttDeadline=android.os.SystemClock.uptimeMillis()+3000;beginPtt()}
                        "heartbeat"->{check(desktopPttLease==lease) {"本轮按住已结束"};desktopPttDeadline=android.os.SystemClock.uptimeMillis()+3000}
                        "end","cancel"->{check(desktopPttLease==lease) {"本轮按住已结束"};desktopPttLease=null;if(v.getString("action")=="end")ptt.end() else ptt.cancel("电脑取消本轮")}
                    }
                }
            }
            "application"->{
                if(v.has("stereo"))setVrMode(v.getBoolean("stereo"))
                if(v.has("paused")) {userPaused=v.getBoolean("paused");syncPreview()}
                if(v.has("wireless")) {usbEnabled=v.getBoolean("wireless");preferences.edit {putBoolean(KEY_USB,usbEnabled)}
                    uiHandler.postDelayed({wifi.enabled=usbEnabled;if(foreground)wifi.reconcile();refreshUsbUi()},350)}
            }
            "remote"->when(val action=v.getString("action")) {
                "openMenu"->{setVrMode(true);headsetMenu.open()};"closeMenu"->headsetMenu.close();"language"->{setVrMode(true);quickControls.language()}
                "startCalibration"->remote.beginCalibration();"cancelCalibration"->remote.cancelCalibration()
                "learn"->remote.beginLearning(v.getString("mapping"))
                "reset"->{remote.cancelCalibration();getSharedPreferences("remote_v6",MODE_PRIVATE).edit {clear()}}
                else->if(!quickControls.command(action))headsetMenu.command(action)
            }
            "preset"->when(v.getString("name")) {
                "smooth","sharp"->{val sharp=v.getString("name")=="sharp";usbServer.applyPhoneSettings(usbServer.currentSettings().copy(fps=0,maxWidth=if(sharp)1920 else 1280));stereo.configure(stereo.settings.copy(outputWidth=if(sharp)1280 else 960,wirelessFps=15,wirelessQuality=if(sharp)75 else 60))}
                "comfortable"->stereo.configure(stereo.settings.copy(scale=85,fontSize=44,background=82))
                "default"->stereo.configure(StereoSettings())
            }
        }
        headsetMenu.refresh();stereo.onFrame?.invoke()
        return org.json.JSONObject().put("ok",true).put("control",desktopControlSnapshot())
    }

    private fun applyCaptionChoice(index:Int) {
        val mode=CaptionChoices.modeAt(index)
        if(mode==null) {liveCaptions.stop();return}
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this).setTitle("开启实时语音字幕")
                .setMessage("开启后，手机麦克风音频会直接发送到阿里云生成字幕，不保存录音。授权后请再次手动开启。")
                .setPositiveButton("授权麦克风") { _,_->microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO) }.setNegativeButton("取消",null).show()
            error("授权麦克风后请重新选择字幕语言")
        }
        liveCaptions.enable(mode)
    }
    private fun showCaptionSettings() {
        phoneSettings.show()
    }
    private fun showSpeechStatus() {
        val s=liveCaptions.snapshot
        AlertDialog.Builder(this).setTitle("语音连接状态 · ${liveCaptions.selectedMode.label}")
            .setMessage(ControllerHints.span("${liveCaptions.status()}\n${s.message}\n上传 ${s.bytes/32000.0} 秒音频 / 返回 ${s.messages} 条消息\n快速识别显示原文；说话人识别显示同传模型原文。说话人编号由云端提供。"))
            .setPositiveButton("关闭",null).show()
    }
    private fun showHelp() {
        val version = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: getString(R.string.unknown_version)
        AlertDialog.Builder(this)
            .setTitle(R.string.device_help_title)
            .setMessage(ControllerHints.span(getString(R.string.device_help_body, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.RELEASE, Build.VERSION.SDK_INT, version)))
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.open_settings) { _, _ -> openAppSettings() }
            .show()
    }

    private fun text(resource: Int, size: Float, color: Int) = TextView(this).apply {
        setText(resource)
        textSize = size
        setTextColor(color)
        includeFontPadding = true
    }

    private fun button(resource: Int, primary: Boolean) = Button(this).apply {
        setText(resource)
        textSize = 18f
        isAllCaps = false
        minHeight = dp(56)
        minimumHeight = dp(56)
        minWidth = dp(64)
        minimumWidth = dp(64)
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(12), dp(18), dp(12))
        backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(if (primary) ACCENT else BUTTON, 16f), null)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(if (primary) 0xFF54674A.toInt() else 0xFF79847A.toInt(), if (primary) BACKGROUND else WHITE)))
        stateListAnimator = null
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = dp(value.toFloat())
    private fun dp(value: Float) = (resources.displayMetrics.density * value).roundToInt()

    private companion object {
        const val KEY_PAUSED = "user_paused"
        const val KEY_PERCEPTION = "sound_perception_requested"
        const val KEY_PERCEPTION_MIC = "sound_perception_mic_requested"
        const val KEY_LENS = "lens_facing"
        const val KEY_REQUESTED = "camera_requested"
        const val KEY_USB = "usb_enabled"
        const val KEY_VR = "vr_enabled"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val BACKGROUND = Color.rgb(16, 21, 17)
        val PANEL = Color.rgb(28, 36, 30)
        val BUTTON = Color.rgb(43, 55, 46)
        val WHITE = Color.rgb(244, 249, 242)
        val MUTED = Color.rgb(183, 197, 183)
        val ACCENT = Color.rgb(180, 245, 139)
        val ERROR = Color.rgb(255, 184, 159)
    }
}
