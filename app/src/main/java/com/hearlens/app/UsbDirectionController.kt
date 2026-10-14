package com.hearlens.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.Executors

/** USB host serial input only: no firmware flashing, polling commands, or serial payload writes. */
internal class UsbDirectionController(context:Context) {
    private val app=context.applicationContext
    private val manager=app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prefs=app.getSharedPreferences("usb_direction_v1",Context.MODE_PRIVATE)
    private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"HearLens-direction-usb").apply {isDaemon=true}}
    private val lock=Any()
    private var config=runCatching {DirectionConfig(prefs.getInt("baud",115200),DirectionFormat.valueOf(prefs.getString("format","AUTO")!!),
        prefs.getString("frontDegrees","0.0")!!.toDouble(),prefs.getBoolean("clockwise",true),prefs.getLong("staleMs",3000),prefs.getBoolean("overlay",true))}.getOrDefault(DirectionConfig())
    private var enabled=prefs.getBoolean("enabled",false)
    @Volatile private var foreground=false
    @Volatile private var generation=0L
    @Volatile private var orientationRevision=0L
    @Volatile private var released=false
    @Volatile private var port:UsbSerialPort?=null
    private var pendingDevice:String?=null
    val permissionPending get()=pendingDevice!=null
    private var selectedName:String?=null
    private var permissionRequests=0L
    private var portOpens=0L
    private var portCloses=0L
    private var message="USB 方向小板未连接"
    private var deviceLabel=""
    @Volatile private var connected=false
    @Volatile private var opening=false
    private var reading:DirectionReading?=null
    private val directionHistory=ArrayDeque<DirectionReading>()
    fun directionsBetween(start:Long,end:Long):List<DirectionReading> = synchronized(lock) {
        if(!enabled || !connected)emptyList() else directionHistory.filter {it.receivedAt in start..end}.map {it.copy(degrees=DirectionMath.relative(it.degrees,config.frontDegrees,config.clockwise))}
    }
    fun captureDirectionWindow(start:Long,end:Long):Pair<Long,List<DirectionReading>> = synchronized(lock) {calibrationGeneration() to directionsBetween(start,end)}
    private var bytes=0L;private var accepted=0L;private var rejected=0L
    private var rawText="";private var rawHex=""
    private val permissionAction="${app.packageName}.DIRECTION_USB_PERMISSION"
    data class Device(val name:String,val label:String,val supported:Boolean)
    private val receiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            when(intent.action) {
                permissionAction -> {
                    val name=pendingDevice ?: return
                    if(intent.getStringExtra("deviceName")!=name)return
                    pendingDevice=null
                    val device=manager.deviceList[name]
                    if(device!=null && manager.hasPermission(device)) {if(foreground)open(name)}
                    else setMessage("USB 授权未允许；可在环境音感知设置中点重试授权")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached=IntentCompat.getParcelableExtra(intent,UsbManager.EXTRA_DEVICE,android.hardware.usb.UsbDevice::class.java)
                    if(detached!=null)permissionGate.detached(deviceKey(detached))
                    permissionGate.retainAttached(manager.deviceList.values.map(::deviceKey).toSet())
                    if(selectedName!=null && (detached?.deviceName==selectedName || selectedName !in manager.deviceList.keys)) {
                        pendingDevice=null
                        stopIo("小板已拔出，等待重新连接")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if(foreground)resume()
            }
        }
    }
    init {
        ContextCompat.registerReceiver(app,receiver,IntentFilter().apply {
            addAction(permissionAction);addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        },ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    fun devices():List<Device> = manager.deviceList.values.sortedBy {it.deviceName}.map {
        val driver=UsbSerialProber.getDefaultProber().probeDevice(it)
        Device(it.deviceName,"${it.productName ?: "USB 设备"} · %04X:%04X".format(it.vendorId,it.productId),driver!=null)
    }
    fun snapshot():DirectionSnapshot=synchronized(lock) {
        DirectionSnapshot(enabled,connected,message,deviceLabel,config,reading,SystemClock.elapsedRealtime(),bytes,accepted,rejected,rawText,rawHex,
            permissionRequests,portOpens,portCloses,permissionPending)
    }
    private fun setMessage(text:String)=synchronized(lock) {message=text}
    /** Explicit unified-start action: choose the saved board or the known CH340, never arbitrary USB devices. */
    fun connectForPerception() {
        if(released || !foreground)return
        synchronized(lock) {enabled=true}
        prefs.edit {putBoolean("enabled",true)}
        if(connected) {setMessage("USB 已连接，等待新的方向角");return}
        resume()
    }
    /** Prepare authorization/connection at app entry, even while the recognition switch is off. */
    fun resume() {
        if(released)return
        foreground=true
        val attached=manager.deviceList.values
        permissionGate.retainAttached(attached.map(::deviceKey).toSet())
        if(opening || pendingDevice!=null)return
        if(connected) {
            val current=manager.deviceList[selectedName]
            if(current!=null && manager.hasPermission(current))return
            stopIo("USB 连接或权限已失效")
        }
        // Reconnect only the explicitly selected VID/PID, never an arbitrary attached peripheral.
        val matches=attached.filter {it.vendorId==prefs.getInt("vid",0x1a86) && it.productId==prefs.getInt("pid",0x7523)}
        if(matches.isEmpty()) {setMessage("等待 USB 小板；前台连接后会提前准备授权");return}
        if(matches.size>1) {setMessage("发现多个同型号串口，请在设备列表明确选择");return}
        prepare(matches.single(),explicitRetry=false)
    }
    fun connect(name:String) {
        if(released || !foreground)return
        val device=manager.deviceList[name] ?: run {setMessage("设备已拔出，请刷新列表");return}
        if(UsbSerialProber.getDefaultProber().probeDevice(device)==null) {setMessage("不支持该 USB 接口，请选择串口小板");return}
        if(name!=selectedName && (connected || opening))stopIo("切换小板")
        synchronized(lock) {enabled=true;deviceLabel=devices().firstOrNull {it.name==name}?.label ?: name}
        selectedName=name
        prefs.edit {putBoolean("enabled",true);putInt("vid",device.vendorId);putInt("pid",device.productId)}
        prepare(device,explicitRetry=true)
    }
    fun retryPermission() {
        val matches=manager.deviceList.values.filter {it.vendorId==prefs.getInt("vid",0x1a86) && it.productId==prefs.getInt("pid",0x7523)}
        if(matches.size==1)prepare(matches.single(),explicitRetry=true)
        else setMessage("请先连接唯一小板，或在详细设置选择设备")
    }
    private fun deviceKey(device:android.hardware.usb.UsbDevice)="${device.deviceName}:${device.deviceId}:${device.vendorId}:${device.productId}"
    private fun prepare(device:android.hardware.usb.UsbDevice,explicitRetry:Boolean) {
        if(released || !foreground || pendingDevice!=null || opening)return
        if(connected && device.deviceName==selectedName)return
        selectedName=device.deviceName
        val hasPermission=manager.hasPermission(device)
        if(hasPermission) {open(device.deviceName);return}
        if(!permissionGate.shouldRequest(deviceKey(device),hasPermission,explicitRetry)) {
            setMessage("尚未获得 USB 授权；点击“重试 USB 授权”可再次请求");return
        }
        pendingDevice=device.deviceName;setMessage("正在提前准备 USB 授权，请确认系统提示")
        // Query Android again on return; neither the gate nor a saved preference represents a grant.
        val request=PendingIntent.getBroadcast(app,device.deviceId,Intent(permissionAction).setPackage(app.packageName).putExtra("deviceName",device.deviceName),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        synchronized(lock) {permissionRequests++}
        runCatching {manager.requestPermission(device,request)}.onFailure {pendingDevice=null;setMessage("无法请求 USB 授权：${it.message}")}
    }
    private fun open(name:String) {
        if(!foreground || released || (connected && selectedName==name))return
        stopIo("正在打开串口")
        selectedName=name
        val ticket=generation
        val options=snapshot().config
        opening=true
        worker.execute {
            var opened:UsbSerialPort?=null
            var connection:android.hardware.usb.UsbDeviceConnection?=null
            try {
                if(ticket!=generation)return@execute
                val device=manager.deviceList[name] ?: error("小板已拔出")
                val driver=UsbSerialProber.getDefaultProber().probeDevice(device) ?: error("串口驱动不支持")
                val serial=driver.ports.firstOrNull() ?: error("没有可用串口")
                opened=serial
                connection=manager.openDevice(device) ?: error("USB 未授权或已断开")
                serial.open(connection)
                serial.setParameters(options.baudRate,8,UsbSerialPort.STOPBITS_1,UsbSerialPort.PARITY_NONE)
                serial.dtr=false;serial.rts=false
                synchronized(lock) {
                    if(ticket!=generation)return@execute
                    port=serial;opening=false;connected=true;portOpens++
                    message=if(enabled)"串口已连接，等待有效角度" else "USB 连接已就绪，环境音感知已关闭"
                    deviceLabel="${device.productName ?: "USB 串口"} · %04X:%04X".format(device.vendorId,device.productId)
                }
                val decoder=DirectionLineDecoder()
                val buffer=ByteArray(2048)
                while(ticket==generation && !released) {
                    val count=serial.read(buffer,200)
                    if(count<=0)continue
                    val data=buffer.copyOf(count)
                    synchronized(lock) {
                        if(ticket!=generation)return@execute
                        bytes+=count
                        rawHex=data.takeLast(96).joinToString(" ") {"%02X".format(it.toInt() and 255)}
                        val before=decoder.discardedLines
                        decoder.feed(data) {line ->
                            rawText=(rawText+line+"\n").takeLast(1600)
                            if(!enabled)return@feed
                            when(val event=DirectionProtocol.parse(line,config.format)) {
                                is DirectionEvent.Angle->{reading=DirectionReading(event.degrees,SystemClock.elapsedRealtime());directionHistory.addLast(reading!!);while(directionHistory.size>256)directionHistory.removeFirst();accepted++;message="已收到有效方向角"}
                                DirectionEvent.NoDirection->{reading=null;directionHistory.clear();message="小板报告当前无有效方向"}
                                null->{rejected++}
                            }
                        }
                        rejected+=decoder.discardedLines-before
                    }
                }
            } catch(e:Exception) {
                synchronized(lock) {if(ticket==generation) {connected=false;reading=null;message="串口读取中断：${e.message ?: "连接失败"}"}}
            } finally {
                runCatching {opened?.close()};connection?.close()
                if(opened!=null)synchronized(lock) {portCloses++}
                synchronized(lock) {if(ticket==generation) {port=null;connected=false;opening=false;reading=null}}
            }
        }
    }
    fun configure(value:DirectionConfig) {
        val previous=snapshot().config
        synchronized(lock) {if(config.frontDegrees!=value.frontDegrees || config.clockwise!=value.clockwise) {directionHistory.clear();orientationRevision++};config=value}
        prefs.edit {putInt("baud",value.baudRate);putString("format",value.format.name);putString("frontDegrees",value.frontDegrees.toString())
            putBoolean("clockwise",value.clockwise);putLong("staleMs",value.staleMs);putBoolean("overlay",value.showOverlay)}
        if(previous.baudRate!=value.baudRate || previous.format!=value.format) {
            stopIo("参数已保存，准备重新连接");if(foreground)resume()
        }
    }
    fun calibrateFront():Boolean {
        val s=snapshot()
        if(!s.fresh)return false
        configure(s.config.copy(frontDegrees=s.reading!!.degrees));return true
    }
    fun calibrationGeneration():Long=(generation shl 32) xor orientationRevision
    fun swapLeftRight() {val s=snapshot();configure(s.config.copy(clockwise=!s.config.clockwise))}
    fun applySideCalibration(capture:SideCalibrationCapture):SideCalibrationResult {
        val s=snapshot()
        val result=capture.finish(s,calibrationGeneration())
        if(result is SideCalibrationResult.Success)configure(s.config.copy(clockwise=result.clockwise))
        return result
    }
    fun disconnect() {synchronized(lock) {enabled=false};prefs.edit {putBoolean("enabled",false)};pendingDevice=null;stopIo("已关闭方向小板读取")}
    fun pausePerception() {
        synchronized(lock) {enabled=false;reading=null;directionHistory.clear();orientationRevision++
            message=if(connected)"环境音感知已关闭，USB 连接保持就绪" else "环境音感知已关闭，等待 USB 小板"}
        prefs.edit {putBoolean("enabled",false)}
    }
    fun background() {foreground=false;stopIo("应用在后台，方向读取已暂停")}
    private fun stopIo(text:String) {
        val old=synchronized(lock) {generation++;val previous=port;port=null;opening=false;connected=false;reading=null;directionHistory.clear();bytes=0;accepted=0;rejected=0;rawText="";rawHex="";message=text;previous}
        // The read loop notices the generation within its 200 ms timeout; cleanup stays off the UI.
        if(old!=null)worker.execute {runCatching {old.close()}}
    }
    fun release() {released=true;background();pendingDevice=null;runCatching {app.unregisterReceiver(receiver)};worker.shutdown()}
    companion object {private val permissionGate=UsbPermissionGate()}
}
