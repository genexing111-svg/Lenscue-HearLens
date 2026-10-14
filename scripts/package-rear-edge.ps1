[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$releaseRoot=Join-Path $projectRoot 'dist\0.12.3-Rear-Edge-Captions'
New-Item -ItemType Directory -Force -Path $releaseRoot | Out-Null
$apkName='听见视界-v0.12.3-后方声音贴边-字幕自动避让.apk'
Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination (Join-Path $releaseRoot $apkName) -Force
foreach($name in @('v0.12.3-后方贴边与回滚说明.md','v0.12.2-开关与USB授权说明.md','环境音与光晕接口.md','v0.12.3-验证记录.md','v0.11.1-左右校准使用说明.md','USB方向接口协议.md','v0.10.0-设置与快捷操作说明.md','v0.6.2-本地双眼无线遥控使用说明.md','无线字幕接口.md','近视与眼镜调节.md')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('docs\'+$name)) -Destination $releaseRoot -Force
}
foreach($name in @('打开无线双眼控制台.html','Send-WifiSubtitle.ps1')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('desktop\'+$name)) -Destination $releaseRoot -Force
}
Copy-Item -LiteralPath (Join-Path $releaseRoot '打开无线双眼控制台.html') -Destination (Join-Path ([Environment]::GetFolderPath('Desktop')) '听见视界-无线双眼控制台.html') -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\reports\lint-results-debug.txt') -Destination (Join-Path $releaseRoot 'lint-results.txt') -Force
$previewRoot=Join-Path $releaseRoot 'previews'
New-Item -ItemType Directory -Force -Path $previewRoot | Out-Null
foreach($name in @('before-rear-caption.png','rear-caption-raised.png','rear-four-lines-calibrated.png','rear-wireless.png','rear-manual-text.png','rear-mono-portrait.png','rear-scene-stereo.png')) {
    $source=Join-Path $projectRoot ('.tools\rear-layout-qa\'+$name)
    if(Test-Path -LiteralPath $source) {Copy-Item -LiteralPath $source -Destination $previewRoot -Force}
}
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$protocolRoot=Join-Path $releaseRoot 'speech-reference'
New-Item -ItemType Directory -Force -Path $protocolRoot | Out-Null
foreach($name in @('PROTOCOL.md','protocol.d.ts')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('docs\speech-reference\'+$name)) -Destination $protocolRoot -Force
}
$testRoot=Join-Path $releaseRoot 'unit-test-results'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\build\test-results\testDebugUnitTest') -Filter 'TEST-*.xml' |
    ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $testRoot -Force }
Push-Location -LiteralPath $projectRoot
try {
    $sourceFiles=@(& rg --files --hidden -g '!.tools/**' -g '!.kotlin/**' -g '!dist/**' -g '!**/build/**' -g '!.gradle/**' -g '!local.properties' -g '!.git/**' -g '!*.keystore' -g '!*.jks' -g '!*.pem' -g '!.env*' -g '!**/.env*' -g '!*.log' -g '!.idea/**' -g '!*.iml' .)
    if($LASTEXITCODE -ne 0 -or $sourceFiles.Count -lt 20){throw 'Source enumeration failed.'}
    $zipStream=[IO.File]::Open((Join-Path $releaseRoot '听见视界-v0.12.3-后方声音贴边完整源码.zip'),[IO.FileMode]::Create)
    $archive=New-Object IO.Compression.ZipArchive($zipStream,[IO.Compression.ZipArchiveMode]::Create,$false)
    try { foreach($file in $sourceFiles) {
        $relative=$file -replace '^\.\\','' -replace '\\','/'
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,(Join-Path $projectRoot $file),('HearLens/'+$relative),[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    } } finally { $archive.Dispose();$zipStream.Dispose() }
    $sourceFiles | Sort-Object | Set-Content -LiteralPath (Join-Path $releaseRoot 'source-files.txt') -Encoding UTF8
} finally { Pop-Location }
Get-ChildItem -LiteralPath $releaseRoot -File | Where-Object {$_.Extension -in @('.apk','.zip')} | Get-FileHash -Algorithm SHA256 | ForEach-Object {
    $_.Hash.ToLowerInvariant()+'  '+[IO.Path]::GetFileName($_.Path)
} | Set-Content -LiteralPath (Join-Path $releaseRoot 'SHA256SUMS.txt') -Encoding UTF8
Write-Output $releaseRoot





