[CmdletBinding()]
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$GradleTasks = @('assembleDebug', 'lintDebug')
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolsRoot = Join-Path $projectRoot '.tools'
$javaRoot = Join-Path $toolsRoot 'java'

if (Test-Path -LiteralPath $javaRoot -PathType Container) {
    $localJdk = Get-ChildItem -LiteralPath $javaRoot -Directory |
        Sort-Object Name |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') -PathType Leaf } |
        Select-Object -First 1
    if ($null -ne $localJdk) {
        $env:JAVA_HOME = $localJdk.FullName
    }
}

$localSdk = Join-Path $toolsRoot 'android-sdk'
if (Test-Path -LiteralPath $localSdk -PathType Container) {
    $env:ANDROID_HOME = $localSdk
    $env:ANDROID_USER_HOME = Join-Path $toolsRoot 'android-user'
}
$env:GRADLE_USER_HOME = Join-Path $toolsRoot 'gradle-home'

$localGradle = Join-Path $toolsRoot 'gradle-8.13\bin\gradle.bat'
$wrapper = Join-Path $projectRoot 'gradlew.bat'
if (Test-Path -LiteralPath $localGradle -PathType Leaf) {
    $gradleCommand = $localGradle
} elseif (Test-Path -LiteralPath $wrapper -PathType Leaf) {
    $gradleCommand = $wrapper
} else {
    throw 'Gradle 8.13 and gradlew.bat are missing. Restore the project wrapper or install the local toolchain first.'
}

if ($null -eq $GradleTasks -or $GradleTasks.Count -eq 0) {
    $GradleTasks = @('assembleDebug', 'lintDebug')
}

Write-Host ('Project: ' + $projectRoot)
Write-Host ('Gradle tasks: ' + ($GradleTasks -join ' '))
Push-Location -LiteralPath $projectRoot
try {
    & $gradleCommand @GradleTasks
    $gradleExitCode = $LASTEXITCODE
    if ($gradleExitCode -ne 0) {
        Write-Error ('Gradle failed with exit code ' + $gradleExitCode + '.') -ErrorAction Continue
    }
} finally {
    Pop-Location
}
exit $gradleExitCode
