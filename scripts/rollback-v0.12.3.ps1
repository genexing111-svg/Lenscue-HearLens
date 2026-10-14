[CmdletBinding()]
param([string]$Serial, [string]$AdbPath)
$ErrorActionPreference='Stop'
$apkName='听见视界-v0.12.3-后方声音贴边-字幕自动避让.apk'
$apk=Join-Path $PSScriptRoot $apkName
if(-not (Test-Path -LiteralPath $apk)) {
    $apk=Join-Path $PSScriptRoot ('..\dist\rollback\v0.12.3-before-y-shortcut\'+$apkName)
}
if(-not (Test-Path -LiteralPath $apk)) {throw '找不到 v0.12.3 回滚 APK，请保留完整备份目录。'}
if((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash -ne '62848a8480a5a2f9a0d3b36250c20503cd78b228b4e3e1be7a570612cdab100d') {
    throw '回滚 APK 校验不符，已停止。'
}
if(-not $AdbPath) {
    $candidates=@((Join-Path $PSScriptRoot '..\.tools\android-sdk\platform-tools\adb.exe'),(Join-Path $PSScriptRoot '..\..\..\.tools\android-sdk\platform-tools\adb.exe'))
    $AdbPath=$candidates | Where-Object {Test-Path -LiteralPath $_} | Select-Object -First 1
    if(-not $AdbPath) {$AdbPath=(Get-Command adb -ErrorAction SilentlyContinue).Source}
}
if(-not $AdbPath) {throw '未找到 ADB，请通过 -AdbPath 指定 adb.exe，或在原 HearLens 工程中运行。'}
if(-not $Serial) {
    $devices=@(& $AdbPath devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object {$_.Matches[0].Groups[1].Value})
    if($devices.Count -ne 1) {throw '请连接并授权一台手机；多台设备时通过 -Serial 指定手机。'}
    $Serial=$devices[0]
}
Write-Host '即将覆盖回滚到 v0.12.3，保留现有应用数据；不会卸载或清除数据。'
& $AdbPath -s $Serial install -r -d $apk
if($LASTEXITCODE -ne 0) {throw '系统未接受保留数据的回滚，已停止；请保留应用并联系开发者，不要先卸载。'}
& $AdbPath -s $Serial shell dumpsys package com.hearlens.app | Select-String 'versionCode=|versionName='
Write-Host '回滚安装完成。请重新打开听见视界。'
