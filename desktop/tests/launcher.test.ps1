# Run using Windows PowerShell 5.1. No device, installation or real ADB commands are used.
$ErrorActionPreference = 'Stop'
$desktop = Split-Path -Parent $PSScriptRoot
. (Join-Path $desktop 'UsbCamera.Common.ps1')

function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-Throws([scriptblock]$Action, [string]$Message) {
    $threw = $false
    try { & $Action | Out-Null } catch { $threw = $true }
    Assert-True $threw $Message
}
foreach ($file in [System.IO.Directory]::GetFiles($desktop, '*.ps1')) {
    $bytes = [System.IO.File]::ReadAllBytes($file)
    Assert-True ($bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191) ('Missing UTF-8 BOM: ' + $file)
    $tokens = $null; $parseErrors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($file, [ref]$tokens, [ref]$parseErrors)
    Assert-True ($parseErrors.Count -eq 0) ('Parse failure: ' + $file)
}
Assert-True (Test-HearLensVersion 'versionName=0.4.0') 'Version 0.4.0 should be accepted'
Assert-True (Test-HearLensVersion 'versionName=1.0.0-debug') 'Newer versions should be accepted'
Assert-True (-not (Test-HearLensVersion 'versionName=0.1.0')) 'Old version should be rejected'
Assert-True (-not (Test-HearLensVersion 'Unable to find package')) 'Missing package should be rejected'

$script:responses = @{}
function Invoke-HearLensAdb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $key = $Arguments -join '|'
    if (-not $script:responses.ContainsKey($key)) { throw ('Unexpected ADB call: ' + $key) }
    $response = $script:responses[$key]
    return [pscustomobject]@{ ExitCode = $response[0]; Text = $response[1]; Lines = @($response[1] -split "`n") }
}
$script:responses = @{ '-d|get-serialno' = @(0, 'USB123'); '-s|USB123|get-state' = @(0, 'device') }
Assert-True ((Get-HearLensUsbDevice) -eq 'USB123') 'USB-only selector should succeed'
$script:responses = @{ '-d|get-serialno' = @(1, 'error: more than one device'); 'devices|-l' = @(0, "List of devices attached`nUSB1 device`nUSB2 device") }
Assert-Throws { Get-HearLensUsbDevice } 'Multiple USB devices must be rejected'
$script:responses = @{ '-d|get-serialno' = @(1, 'error: device unauthorized'); 'devices|-l' = @(0, "List of devices attached`nUSB1 unauthorized") }
Assert-Throws { Get-HearLensUsbDevice } 'Unauthorized device must be rejected'
$script:responses = @{ '-d|get-serialno' = @(1, 'error: no devices found'); 'devices|-l' = @(0, "List of devices attached`n192.168.1.1:5555 device") }
Assert-Throws { Get-HearLensUsbDevice } 'Wireless-only connection must not be selected'
$script:responses = @{ '-d|get-serialno' = @(0, '192.168.1.1:5555') }
Assert-Throws { Get-HearLensUsbDevice } 'Unexpected network serial must be rejected defensively'

# Read the real launcher and replace process termination and import ONLY for in-process mocks.
function Get-TestableLauncher([string]$Name) {
    $text = [System.IO.File]::ReadAllText((Join-Path $desktop $Name), [System.Text.Encoding]::UTF8)
    $text = $text.Replace(". (Join-Path `$PSScriptRoot 'UsbCamera.Common.ps1')", '')
    $text = $text.Replace('exit 0', 'return').Replace('exit 1', "throw 'Launcher failed'")
    return [scriptblock]::Create($text)
}
$start = Get-TestableLauncher 'Start-UsbCamera.ps1'
$stop = Get-TestableLauncher 'Stop-UsbCamera.ps1'
$script:commands = New-Object 'System.Collections.Generic.List[string]'
$script:mappings = @()
$script:installedVersion = '0.4.0'
$script:healthWorks = $true
$script:browserCalls = 0
function Initialize-HearLensAdb {}
function Get-HearLensUsbDevice { return 'USB123' }
function Get-HearLensForward { return $script:mappings }
function Write-Host {}
function Start-Sleep {}
function Start-Process { param($FilePath) $script:browserCalls++ }
function Invoke-RestMethod {
    param($Uri, $TimeoutSec, $Method)
    if (-not $script:healthWorks) { throw 'Not ready' }
    Assert-True ($Uri -eq 'http://127.0.0.1:18765/status') 'Health must use loopback'
    return [pscustomobject]@{ state = 'waiting'; frameId = 0; sessionId = 'test-session'; message = 'ready' }
}
function Invoke-HearLensAdb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $key = $Arguments -join '|'; $script:commands.Add($key)
    $text = ''
    if ($key -match '\|dumpsys\|package\|com.hearlens.app$') { $text = 'versionName=' + $script:installedVersion }
    elseif ($key -match '\|install\|-r\|') { $script:installedVersion = '0.4.0'; $text = 'Success' }
    elseif ($key -match '\|forward\|--no-rebind\|tcp:18765\|localabstract:hearlens_usb_video$') { $text = '' }
    elseif ($key -match '\|forward\|--remove\|tcp:18765$') { $text = '' }
    elseif ($key -match '\|am\|start\|-n\|com.hearlens.app/\.MainActivity$') { $text = 'Starting Activity' }
    else { throw ('Unexpected launcher command: ' + $key) }
    return [pscustomobject]@{ ExitCode = 0; Text = $text; Lines = @($text) }
}
& $start -NoBrowser -NoInstall
Assert-True ($script:commands.Contains('-s|USB123|forward|--no-rebind|tcp:18765|localabstract:hearlens_usb_video')) 'Forward must use no-rebind and exact USB serial'
Assert-True ($script:browserCalls -eq 0) 'NoBrowser must prevent opening browser'
$script:commands.Clear()
$script:mappings = @([pscustomobject]@{ Serial = 'USB123'; Local = 'tcp:18765'; Remote = 'localabstract:hearlens_usb_video' })
& $start -NoBrowser -NoInstall
Assert-True (-not (($script:commands -join ';') -match '\|forward\|')) 'Existing exact forward should be reused'
$script:mappings = @([pscustomobject]@{ Serial = 'OTHER'; Local = 'tcp:18765'; Remote = 'localabstract:hearlens_usb_video' })
$script:commands.Clear()
Assert-Throws { & $start -NoBrowser -NoInstall } 'Unrelated forward should be rejected'
Assert-True (-not (($script:commands -join ';') -match '\|forward\|')) 'Unrelated forward must not be modified'
$script:mappings = @(); $script:installedVersion = '0.1.0'
Assert-Throws { & $start -NoBrowser -NoInstall } 'NoInstall must reject old app without installation'

# APK fixture is metadata only. Native install is mocked above.
$script:apkName = '听见视界-v0.4.0-多镜头-USB双眼自定义.apk'
function Get-ChildItem { param($LiteralPath, [switch]$File, $Filter) return [pscustomobject]@{ Name = $script:apkName; FullName = 'C:\fixture\' + $script:apkName } }
$script:commands.Clear()
& $start -NoBrowser
Assert-True (($script:commands -join ';') -match '\|install\|-r\|') 'Named Chinese USB APK must support upgrade without uninstall'
$script:healthWorks = $false
Assert-Throws { & $start -NoBrowser -NoInstall } 'Unhealthy service must not report success'
$script:healthWorks = $true
$script:mappings = @([pscustomobject]@{ Serial = 'USB123'; Local = 'tcp:18765'; Remote = 'localabstract:hearlens_usb_video' })
$script:commands.Clear()
& $stop
Assert-True ($script:commands.Count -eq 1 -and $script:commands[0] -eq '-s|USB123|forward|--remove|tcp:18765') 'Stop must remove only the selected exact mapping'
$script:mappings = @([pscustomobject]@{ Serial = 'USB123'; Local = 'tcp:18765'; Remote = 'localabstract:OTHER' })
$script:commands.Clear()
Assert-Throws { & $stop } 'Stop must reject another service'
Assert-True ($script:commands.Count -eq 0) 'Stop must not mutate another service'
Write-Output 'PASS: PowerShell 5.1 syntax/BOM; versions; USB selection; authorization; wireless rejection; exact forward reuse; collision rejection; upgrade; NoBrowser/NoInstall; health; safe stop.'
