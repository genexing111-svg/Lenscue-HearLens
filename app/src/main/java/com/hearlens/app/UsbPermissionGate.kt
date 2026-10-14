package com.hearlens.app

/** Remember attempts, never grants: Android's hasPermission remains the authority. */
internal class UsbPermissionGate {
    private val attempted=mutableSetOf<String>()
    @Synchronized fun shouldRequest(device:String,hasPermission:Boolean,explicitRetry:Boolean=false):Boolean {
        if(hasPermission)return false
        if(!explicitRetry && device in attempted)return false
        attempted+=device
        return true
    }
    @Synchronized fun retainAttached(devices:Set<String>) {attempted.retainAll(devices)}
    @Synchronized fun detached(device:String) {attempted.remove(device)}
}
