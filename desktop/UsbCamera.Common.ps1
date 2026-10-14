$ErrorActionPreference = 'Stop'
$script:HearLensPackage = 'com.hearlens.app'
$script:HearLensSocket = 'localabstract:hearlens_usb_video'
$script:HearLensPort = 18765

function Initialize-HearLensAdb {
    $candidates = @(
        (Join-Path $PSScriptRoot 'tools\adb.exe'),
        (Join-Path $PSScriptRoot '..\.tools\android-sdk\platform-tools\adb.exe')
    )
    $script:HearLensAdb = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (-not $script:HearLensAdb) {
        throw '未找到 ADB。请完整解压 USB 观看工具包，保留 tools 文件夹后再启动；不要单独复制启动脚本。'
    }
    $script:HearLensAdb = (Resolve-Path -LiteralPath $script:HearLensAdb).Path
    $projectUser = Join-Path $PSScriptRoot '..\.tools\android-user'
    if (Test-Path -LiteralPath $projectUser -PathType Container) { $env:ANDROID_USER_HOME = (Resolve-Path -LiteralPath $projectUser).Path }
}

function Invoke-HearLensAdb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments, [switch]$AllowFailure)
    $savedPreference = $ErrorActionPreference
    try {
        # Array splatting preserves file paths; no shell command is assembled or evaluated.
        $ErrorActionPreference = 'Continue'
        $lines = @(& $script:HearLensAdb @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    $result = [pscustomobject]@{ ExitCode = $code; Text = ($lines -join "`n").Trim(); Lines = $lines }
    if ($code -ne 0 -and -not $AllowFailure) { throw ('ADB 执行失败：' + $result.Text) }
    return $result
}

function Get-HearLensUsbDevice {
    # -d is ADB's USB-only selector, unlike selecting the first row from adb devices.
    $selection = Invoke-HearLensAdb -Arguments @('-d', 'get-serialno') -AllowFailure
    if ($selection.ExitCode -ne 0 -or -not $selection.Text -or $selection.Text -eq 'unknown') {
        $listing = Invoke-HearLensAdb -Arguments @('devices', '-l') -AllowFailure
        if ($selection.Text -match 'more than one|multiple') {
            throw '检测到多台 USB 调试设备。请暂时拔掉其他手机，仅保留要观看的 K80 Pro，再运行启动器。不会自动选择设备。'
        }
        if ($selection.Text -match 'unauthorized' -or $listing.Text -match '\sunauthorized(?:\s|$)') {
            throw '手机尚未授权 USB 调试。请解锁手机，确认「允许 USB 调试」弹窗，再运行启动器；若无弹窗，拔插数据线重试。'
        }
        if ($selection.Text -match 'offline' -or $listing.Text -match '\soffline(?:\s|$)') {
            throw 'USB 调试设备离线。请解锁手机、重新插拔数据线，确认 USB 调试已开启后再试。'
        }
        throw '未找到已授权的 USB 手机。请使用可传数据的 USB 线连接电脑，在手机开发者选项开启 USB 调试并允许授权。无线 ADB 不用于本功能。'
    }
    $serial = ($selection.Lines | Where-Object { $_ -and $_ -notmatch '^\*' } | Select-Object -Last 1).Trim()
    if ($serial -match ':|_adb-tls-|\s' -or -not $serial) { throw '检测结果不是可确认的 USB 设备。本功能仅支持通过 USB 数据线连接，请关闭无线调试后重试。' }
    $state = Invoke-HearLensAdb -Arguments @('-s', $serial, 'get-state') -AllowFailure
    if ($state.ExitCode -ne 0 -or $state.Text -ne 'device') { throw 'USB 设备尚未就绪。请在手机上确认调试授权并保持解锁。' }
    return $serial
}

function Get-HearLensForward {
    $result = Invoke-HearLensAdb -Arguments @('forward', '--list')
    $target = 'tcp:' + $script:HearLensPort
    foreach ($line in $result.Lines) {
        $fields = $line.Trim() -split '\s+'
        if ($fields.Length -eq 3 -and $fields[1] -eq $target) {
            [pscustomobject]@{ Serial = $fields[0]; Local = $fields[1]; Remote = $fields[2] }
        }
    }
}

function Test-HearLensVersion {
    param([string]$PackageDump)
    if ($PackageDump -notmatch '(?m)\bversionName=(\d+)\.(\d+)\.(\d+)(?:[^\r\n]*)') { return $false }
    $version = [version]('{0}.{1}.{2}' -f $Matches[1], $Matches[2], $Matches[3])
    return ($version -ge [version]'0.4.0')
}
