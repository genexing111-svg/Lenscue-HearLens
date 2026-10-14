[CmdletBinding()]
param(
    [string]$ReleaseName='0.14.1-Glow-Stream-Fix',
    [string]$Version='0.14.1',
    [string]$ReceiverVersion='1.0.2',
    [string]$ApkName='听见视界-v0.14.1-环境音辉光连续图传修复.apk'
)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$release=Join-Path $projectRoot ('dist\'+$ReleaseName)
New-Item -ItemType Directory -Force -Path $release | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination (Join-Path $release $apkName) -Force
foreach($name in @('v0.14.0-电脑接收控制台说明.md','v0.14.0-电脑控制接口.md',('v'+$Version+'-验证记录.md'),('v'+$Version+'-辉光图传修复说明.md'))){Copy-Item -LiteralPath (Join-Path $projectRoot ('docs\'+$name)) -Destination $release -Force}
$receiverRoot=Join-Path $projectRoot 'desktop\receiver'
$bundle=Join-Path $release '电脑接收端'
New-Item -ItemType Directory -Force -Path $bundle | Out-Null
Push-Location -LiteralPath $receiverRoot
try {
    $files=@(& rg --files --hidden -g '!.runtime/**' -g '!runtime/**' -g '!downloads/**' -g '!test/**' -g '!.gitignore' .)
    foreach($file in $files){$target=Join-Path $bundle $file;New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($target)) | Out-Null;Copy-Item -LiteralPath (Join-Path $receiverRoot $file) -Destination $target -Force}
}finally {Pop-Location}
New-Item -ItemType Directory -Force -Path (Join-Path $bundle 'downloads') | Out-Null
Copy-Item -LiteralPath (Join-Path $release $apkName) -Destination (Join-Path $bundle ('downloads\'+$apkName)) -Force
Copy-Item -LiteralPath (Join-Path $projectRoot ('docs\v'+$Version+'-辉光图传修复说明.md')) -Destination (Join-Path $bundle '使用说明.md') -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs\v0.14.0-电脑接收控制台说明.md') -Destination (Join-Path $bundle '完整控制台使用说明.md') -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\reports\lint-results-debug.txt') -Destination (Join-Path $release 'lint-results.txt') -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Write-Zip([string]$ZipPath,[string]$Root,[string[]]$Files,[string]$Prefix) {
    $stream=[IO.File]::Open($ZipPath,[IO.FileMode]::Create)
    $archive=New-Object IO.Compression.ZipArchive($stream,[IO.Compression.ZipArchiveMode]::Create,$false)
    try {foreach($file in $Files){$name=($file -replace '^\.\\','' -replace '\\','/');[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,(Join-Path $Root $file),($Prefix+'/'+$name),[IO.Compression.CompressionLevel]::Optimal) | Out-Null}}
    finally {$archive.Dispose();$stream.Dispose()}
}
Push-Location -LiteralPath $bundle
try {$bundleFiles=@(& rg --files --hidden -g '!.runtime/**' .);Write-Zip (Join-Path $release ('听见视界-电脑接收端-v'+$ReceiverVersion+'.zip')) $bundle $bundleFiles '听见视界-电脑接收端'}finally {Pop-Location}
Push-Location -LiteralPath $projectRoot
try {
    $sourceFiles=@(& rg --files --hidden -g '!.tools/**' -g '!.gradle/**' -g '!.kotlin/**' -g '!dist/**' -g '!**/build/**' -g '!**/.runtime/**' -g '!desktop/receiver/runtime/**' -g '!desktop/receiver/downloads/**' -g '!local.properties' -g '!.git/**' -g '!*.keystore' -g '!*.jks' -g '!*.pem' -g '!.env*' -g '!**/.env*' -g '!*.log' -g '!.idea/**' -g '!*.iml' .)
    if($sourceFiles.Count -lt 100){throw 'Source enumeration failed'}
    Write-Zip (Join-Path $release ('听见视界-v'+$Version+'-手机与电脑完整源码.zip')) $projectRoot $sourceFiles 'HearLens'
    $sourceFiles | Sort-Object | Set-Content -LiteralPath (Join-Path $release 'source-files.txt') -Encoding UTF8
}finally {Pop-Location}
Get-ChildItem -LiteralPath $release -File | Where-Object {$_.Extension -in '.apk','.zip'} | Get-FileHash -Algorithm SHA256 | ForEach-Object {$_.Hash.ToLowerInvariant()+'  '+[IO.Path]::GetFileName($_.Path)} | Set-Content -LiteralPath (Join-Path $release 'SHA256SUMS.txt') -Encoding UTF8
Write-Output $release
