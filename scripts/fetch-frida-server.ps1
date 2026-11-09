<#
.SYNOPSIS
    下载 frida-server 并放入 Android 工程的 assets 目录。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\fetch-frida-server.ps1
    powershell -ExecutionPolicy Bypass -File scripts\fetch-frida-server.ps1 -Version 17.26.2
#>
param(
    [string]$Version = "17.26.2",
    [string]$Arch = "android-arm64"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$assetsDir = Join-Path $root "app\src\main\assets"
$target = Join-Path $assetsDir "frida-server"
$url = "https://github.com/frida/frida/releases/download/$Version/frida-server-$Version-$Arch.xz"
$xz = Join-Path $env:TEMP "frida-server-$Version.xz"

New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null

if (Test-Path $target) {
    Write-Host "已存在 $target，跳过下载（删除该文件可强制重新下载）" -ForegroundColor Yellow
    exit 0
}

Write-Host "下载 $url ..."
$ProgressPreference = 'SilentlyContinue'
Invoke-WebRequest -Uri $url -OutFile $xz -UseBasicParsing -TimeoutSec 600

Write-Host "解压中..."
$py = Get-Command python -ErrorAction SilentlyContinue
if (-not $py) {
    Write-Error "需要 Python 3 来解压 xz 文件，或手动用 7-Zip 解压 $xz 后重命名为 frida-server 放到 $assetsDir"
}
& $py.Source -c @"
import lzma, shutil, sys
with lzma.open(r'$xz') as src, open(r'$target', 'wb') as dst:
    shutil.copyfileobj(src, dst)
print('ok')
"@

Remove-Item $xz -Force
$size = [math]::Round((Get-Item $target).Length / 1MB, 1)
Write-Host "完成: $target ($size MB)" -ForegroundColor Green
