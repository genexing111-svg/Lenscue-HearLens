[CmdletBinding()]
param([switch]$NoBrowser, [switch]$NoInstall)

$ErrorActionPreference = 'Stop'
try {
    . (Join-Path $PSScriptRoot 'UsbCamera.Common.ps1')
    Write-Host ''
    Write-Host '听见视界 · 多镜头与 USB 双眼自定义' -ForegroundColor Cyan
    Write-Host '正在检查 USB 连接。请保持手机解锁。'
    Initialize-HearLensAdb
    $serial = Get-HearLensUsbDevice
    Write-Host ('USB 手机已连接：' + $serial)

    $package = Invoke-HearLensAdb -Arguments @('-s', $serial, 'shell', 'dumpsys', 'package', $script:HearLensPackage)
    if (-not (Test-HearLensVersion -PackageDump $package.Text)) {
        if ($NoInstall) { throw '手机需要安装听见视界 0.4.0 或更高版本（USB 版）；当前为禁止自动安装模式。' }
        $apks = @(Get-ChildItem -LiteralPath $PSScriptRoot -File -Filter '*.apk' | Where-Object { $_.Name -eq '听见视界-v0.4.0-多镜头-USB双眼自定义.apk' -or $_.Name -match '(?i)^HearLens-.*USB.*\.apk$' })
        if ($apks.Count -ne 1) { throw '手机未安装 USB 版。请将一个带 USB 标记的 HearLens APK 放在本启动器同目录（仅保留一个），或先在手机手动安装 0.4.0 及以上版本。' }
        Write-Host ('正在安装／升级：' + $apks[0].Name)
        Write-Host '若手机提示允许 USB 安装，请在手机上确认。'
        $install = Invoke-HearLensAdb -Arguments @('-s', $serial, 'install', '-r', $apks[0].FullName) -AllowFailure
        if ($install.ExitCode -ne 0 -or $install.Text -notmatch '(?m)^Success\s*$') {
            throw ('自动安装未完成：' + $install.Text + "`n可将 APK 复制到手机，从文件管理器手动安装后再次运行；不会卸载已有应用。")
        }
        $package = Invoke-HearLensAdb -Arguments @('-s', $serial, 'shell', 'dumpsys', 'package', $script:HearLensPackage)
        if (-not (Test-HearLensVersion -PackageDump $package.Text)) { throw '安装后的应用版本低于 0.4.0，请使用本次提供的 USB 版安装包。' }
    }

    $mappings = @(Get-HearLensForward)
    if ($mappings.Count -gt 0) {
        if ($mappings.Count -ne 1 -or $mappings[0].Serial -ne $serial -or $mappings[0].Remote -ne $script:HearLensSocket) {
            throw '电脑端口 18765 已用于其他 ADB 转发。为避免影响其他程序，本工具未修改它。请停止原占用程序的转发后重试。'
        }
        Write-Host '复用本手机现有的 USB 观看连接。'
    } else {
        $forward = Invoke-HearLensAdb -Arguments @('-s', $serial, 'forward', '--no-rebind', ('tcp:' + $script:HearLensPort), $script:HearLensSocket) -AllowFailure
        if ($forward.ExitCode -ne 0) { throw ('无法开启电脑端口 18765。它可能被其他程序占用，请关闭占用程序后重试。' + "`n" + $forward.Text) }
    }

    $start = Invoke-HearLensAdb -Arguments @('-s', $serial, 'shell', 'am', 'start', '-n', ($script:HearLensPackage + '/.MainActivity')) -AllowFailure
    if ($start.ExitCode -ne 0 -or $start.Text -match '(?m)^Error') { throw ('无法打开手机应用：' + $start.Text + "`n请在手机手动打开听见视界后再次运行。") }
    Write-Host '正在等待手机的 USB 画面服务……'
    $baseUrl = 'http://127.0.0.1:' + $script:HearLensPort
    $health = $null
    $lastHealthError = ''
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        try {
            $candidate = Invoke-RestMethod -Uri ($baseUrl + '/status') -TimeoutSec 2 -Method Get
            if ($null -ne $candidate.state -and $null -ne $candidate.frameId -and $null -ne $candidate.sessionId) { $health = $candidate; break }
            $lastHealthError = '服务返回了旧版或不完整状态，请确认手机安装的是 v0.4.0 双眼回传版。'
        } catch { $lastHealthError = $_.Exception.Message }
        Start-Sleep -Milliseconds 700
    }
    if ($null -eq $health) { throw ('手机画面服务未就绪。请先解锁手机并打开听见视界，允许相机权限，确认 USB 传输已开启。若停在黑屏或旧页面，请从手机最近任务关闭应用后重新打开，再运行本启动器。' + "`n" + '连接诊断：' + $lastHealthError) }
    Write-Host ('手机状态：' + $health.message)
    Write-Host ('电脑观看地址：' + $baseUrl + '/') -ForegroundColor Green
    if (-not $NoBrowser) { Start-Process -FilePath ($baseUrl + '/') }
    Write-Host '电脑工作台会显示完整原图，并自动将双眼合成画面传回手机。可在网页右侧输入手动文字。'
    Write-Host '保持手机应用在前台。结束后可关闭网页，再双击「停止USB连接.cmd」。'
    exit 0
} catch {
    Write-Host ''
    Write-Host $_.Exception.Message -ForegroundColor Yellow
    exit 1
}
