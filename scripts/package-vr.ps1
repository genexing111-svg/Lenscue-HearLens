[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$releaseRoot = Join-Path $projectRoot 'dist\0.4.0-MultiCamera'
$viewerRoot = Join-Path $releaseRoot '电脑多镜头控制台'
$apkName = '听见视界-v0.4.0-多镜头-USB双眼自定义.apk'
New-Item -ItemType Directory -Force -Path $releaseRoot, $viewerRoot, (Join-Path $viewerRoot 'tools') | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination (Join-Path $releaseRoot $apkName) -Force
Copy-Item -LiteralPath (Join-Path $releaseRoot $apkName) -Destination $viewerRoot -Force
Get-ChildItem -LiteralPath (Join-Path $projectRoot 'desktop') -File | Where-Object { $_.Extension -in @('.ps1','.cmd') } | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $viewerRoot -Force
}
foreach ($toolFile in @('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','libwinpthread-1.dll','NOTICE.txt','source.properties')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('.tools\android-sdk\platform-tools\' + $toolFile)) -Destination (Join-Path $viewerRoot 'tools') -Force
}
foreach ($docName in @('多镜头自定义使用说明.md','多镜头构建与测试报告.md','近视与眼镜调节.md')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('docs\' + $docName)) -Destination $releaseRoot -Force
}
Copy-Item -LiteralPath (Join-Path $releaseRoot '多镜头自定义使用说明.md') -Destination $viewerRoot -Force
Copy-Item -LiteralPath (Join-Path $releaseRoot '近视与眼镜调节.md') -Destination $viewerRoot -Force
Compress-Archive -LiteralPath $viewerRoot -DestinationPath (Join-Path $releaseRoot '听见视界-v0.4.0-多镜头电脑控制台.zip') -Force

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
Push-Location -LiteralPath $projectRoot
try {
    $sourceFiles = @(& rg --files --hidden -g '!.tools/**' -g '!dist/**' -g '!**/build/**' -g '!.gradle/**' -g '!local.properties' -g '!.git/**' -g '!*.keystore' -g '!*.jks' -g '!*.log' -g '!.idea/**' -g '!*.iml' .)
    if ($LASTEXITCODE -ne 0 -or $sourceFiles.Count -lt 15) { throw 'Source enumeration failed.' }
    $zipPath = Join-Path $releaseRoot '听见视界-v0.4.0-多镜头完整源码.zip'
    $zipStream = [IO.File]::Open($zipPath,[IO.FileMode]::Create)
    $archive = New-Object IO.Compression.ZipArchive($zipStream,[IO.Compression.ZipArchiveMode]::Create,$false)
    try {
        foreach ($sourceFile in $sourceFiles) {
            $relative = $sourceFile -replace '^\.\\','' -replace '\\','/'
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $projectRoot $sourceFile), ('HearLens/' + $relative), [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $archive.Dispose(); $zipStream.Dispose() }
    $sourceFiles | Sort-Object | Set-Content -LiteralPath (Join-Path $releaseRoot 'source-files.txt') -Encoding UTF8
} finally { Pop-Location }
Get-ChildItem -LiteralPath $releaseRoot -File | Where-Object { $_.Extension -in @('.apk','.zip') } | Get-FileHash -Algorithm SHA256 | ForEach-Object {
    $_.Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetFileName($_.Path)
} | Set-Content -LiteralPath (Join-Path $releaseRoot 'SHA256SUMS.txt') -Encoding UTF8
Write-Output $releaseRoot
