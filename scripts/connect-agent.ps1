param(
    [int]$Port = 8765,
    [string]$Device = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$localProperties = Join-Path $projectRoot "local.properties"

if (-not (Test-Path -LiteralPath $localProperties)) {
    throw "找不到 local.properties，无法定位 Android SDK。"
}

$sdkLine = Get-Content -LiteralPath $localProperties |
    Where-Object { $_ -like "sdk.dir=*" } |
    Select-Object -First 1
if (-not $sdkLine) {
    throw "local.properties 中没有 sdk.dir。"
}

$sdkPath = ($sdkLine -replace "^sdk.dir=", "") -replace "\\:", ":" -replace "\\\\", "\"
$adb = Join-Path $sdkPath "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) {
    throw "找不到 ADB：$adb"
}

$connected = @(
    & $adb devices |
        Select-Object -Skip 1 |
        Where-Object { $_ -match "^([^\s]+)\s+device$" } |
        ForEach-Object { $Matches[1] }
)

if ($Device) {
    if ($Device -notin $connected) {
        throw "指定设备未连接或未授权：$Device"
    }
    $serial = $Device
} elseif ($connected.Count -eq 1) {
    $serial = $connected[0]
} elseif ($connected.Count -eq 0) {
    throw "没有检测到已授权的 Android 设备。"
} else {
    throw "检测到多台设备，请使用 -Device <序列号> 指定。"
}

& $adb -s $serial forward "tcp:$Port" "tcp:$Port" | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "ADB 端口转发失败。"
}

$healthUrl = "http://127.0.0.1:$Port/v1/health"
try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $healthUrl -TimeoutSec 8
} catch {
    throw "转发已建立，但 Agent API 无响应。请在手机 RootPilot 的 Agent 页面启用服务。原始错误：$($_.Exception.Message)"
}

Write-Output "设备：$serial"
Write-Output "转发：电脑 127.0.0.1:$Port -> 手机 127.0.0.1:$Port"
Write-Output "健康检查：HTTP $($response.StatusCode) $($response.Content)"
Write-Output "访问地址：$healthUrl"
