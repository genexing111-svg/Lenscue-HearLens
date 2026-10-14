[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
try {
    . (Join-Path $PSScriptRoot 'UsbCamera.Common.ps1')
    Initialize-HearLensAdb
    $serial = Get-HearLensUsbDevice
    $mappings = @(Get-HearLensForward)
    if ($mappings.Count -eq 0) {
        Write-Host '当前没有 USB 观看连接，无需停止。'
    } elseif ($mappings.Count -eq 1 -and $mappings[0].Serial -eq $serial -and $mappings[0].Remote -eq $script:HearLensSocket) {
        $null = Invoke-HearLensAdb -Arguments @('-s', $serial, 'forward', '--remove', ('tcp:' + $script:HearLensPort))
        Write-Host '已停止本手机的 USB 观看连接。可以关闭网页。' -ForegroundColor Green
        Write-Host '手机预览仍可正常使用；其他 ADB 连接未修改。'
    } else {
        throw '端口 18765 属于其他设备或其他服务，已保留。本工具只停止本手机的听见视界 USB 连接。'
    }
    exit 0
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Yellow
    exit 1
}
