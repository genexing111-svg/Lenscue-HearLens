[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$releaseRoot=Join-Path $projectRoot 'dist\0.5.1-HD-LocalStereo-WiFi'
New-Item -ItemType Directory -Force -Path $releaseRoot | Out-Null
$apkName='听见视界-v0.5.1-手机原生高清双眼-WiFi字幕-镜头自定义.apk'
Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination (Join-Path $releaseRoot $apkName) -Force
foreach($name in @('手机本地双眼无线使用说明.md','无线字幕接口.md','本地双眼无线构建与测试报告.md','近视与眼镜调节.md')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('docs\'+$name)) -Destination $releaseRoot -Force
}
foreach($name in @('打开无线双眼控制台.html','Send-WifiSubtitle.ps1')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('desktop\'+$name)) -Destination $releaseRoot -Force
}
Copy-Item -LiteralPath (Join-Path $releaseRoot '打开无线双眼控制台.html') -Destination (Join-Path ([Environment]::GetFolderPath('Desktop')) '听见视界-无线双眼控制台.html') -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\reports\lint-results-debug.txt') -Destination (Join-Path $releaseRoot 'lint-results.txt') -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
Push-Location -LiteralPath $projectRoot
try {
    $sourceFiles=@(& rg --files --hidden -g '!.tools/**' -g '!dist/**' -g '!**/build/**' -g '!.gradle/**' -g '!local.properties' -g '!.git/**' -g '!*.keystore' -g '!*.jks' -g '!*.log' -g '!.idea/**' -g '!*.iml' .)
    if($LASTEXITCODE -ne 0 -or $sourceFiles.Count -lt 20){throw 'Source enumeration failed.'}
    $zipStream=[IO.File]::Open((Join-Path $releaseRoot '听见视界-v0.5.1-本地双眼无线完整源码.zip'),[IO.FileMode]::Create)
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
