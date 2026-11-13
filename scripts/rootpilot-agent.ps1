param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("health", "capabilities", "status", "network", "sensors", "sensor-sample", "location", "foreground", "screenshot", "ui-tree", "files-list", "files-read", "files-write", "launch", "tap", "swipe", "key", "text")]
    [string]$Command,
    [string]$Token = $env:ROOTPILOT_TOKEN,
    [string]$PackageName = "",
    [string]$Text = "",
    [int]$X = 0,
    [int]$Y = 0,
    [int]$X2 = 0,
    [int]$Y2 = 0,
    [int]$DurationMs = 300,
    [int]$KeyCode = 0,
    [int]$SensorType = 1,
    [string]$RemotePath = "",
    [string]$InputPath = "",
    [string]$OutputPath = "",
    [int]$Port = 8765
)

$ErrorActionPreference = "Stop"
$baseUrl = "http://127.0.0.1:$Port"
$headers = @{}
if ($Command -ne "health") {
    if (-not $Token) { throw "该命令需要 Token。请先设置 `$env:ROOTPILOT_TOKEN。" }
    $headers["X-RootPilot-Token"] = $Token
}

$method = "GET"
$body = $null
$path = switch ($Command) {
    "health" { "/v1/health" }
    "capabilities" { "/v1/capabilities" }
    "status" { "/v1/status" }
    "network" { "/v1/network/status" }
    "sensors" { "/v1/sensors" }
    "sensor-sample" { "/v1/sensors/sample?type=$SensorType" }
    "location" { "/v1/location/current" }
    "foreground" { "/v1/ui/foreground" }
    "screenshot" { "/v1/screen/screenshot" }
    "ui-tree" { "/v1/ui/tree" }
    "files-list" { "/v1/files?path=$([Uri]::EscapeDataString($RemotePath))" }
    "files-read" {
        if (-not $RemotePath) { throw "files-read 需要 -RemotePath。" }
        "/v1/files/read?path=$([Uri]::EscapeDataString($RemotePath))"
    }
    "files-write" {
        if (-not $RemotePath -or -not $InputPath) { throw "files-write 需要 -RemotePath 和 -InputPath。" }
        $method = "POST"
        $bytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $InputPath).Path)
        $body = @{ path = $RemotePath; base64 = [Convert]::ToBase64String($bytes) }
        "/v1/files/write"
    }
    "launch" {
        if (-not $PackageName) { throw "launch 需要 -PackageName。" }
        $method = "POST"
        "/v1/apps/$([Uri]::EscapeDataString($PackageName))/launch"
    }
    "tap" { $method = "POST"; $body = @{ x = $X; y = $Y }; "/v1/ui/tap" }
    "swipe" {
        $method = "POST"
        $body = @{ x1 = $X; y1 = $Y; x2 = $X2; y2 = $Y2; durationMs = $DurationMs }
        "/v1/ui/swipe"
    }
    "key" { $method = "POST"; $body = @{ keyCode = $KeyCode }; "/v1/ui/key" }
    "text" {
        if (-not $Text) { throw "text 需要 -Text。" }
        $method = "POST"
        $body = @{ text = $Text }
        "/v1/ui/text"
    }
}

$invoke = @{
    Uri = "$baseUrl$path"
    Method = $method
    Headers = $headers
    UseBasicParsing = $true
    TimeoutSec = 15
}
if ($body) {
    $invoke.ContentType = "application/json; charset=utf-8"
    $invoke.Body = $body | ConvertTo-Json -Compress
}

$response = Invoke-WebRequest @invoke
$result = $response.Content | ConvertFrom-Json

if ($Command -eq "screenshot") {
    if (-not $OutputPath) { $OutputPath = Join-Path (Get-Location) "rootpilot-screen.png" }
    [IO.File]::WriteAllBytes($OutputPath, [Convert]::FromBase64String($result.base64))
    Write-Output "截图已保存：$OutputPath"
} elseif ($Command -eq "ui-tree") {
    if (-not $OutputPath) { $OutputPath = Join-Path (Get-Location) "rootpilot-ui.xml" }
    [IO.File]::WriteAllText($OutputPath, [string]$result.xml, [Text.Encoding]::UTF8)
    Write-Output "UI 层级已保存：$OutputPath"
} elseif ($Command -eq "files-read") {
    if (-not $OutputPath) { $OutputPath = Join-Path (Get-Location) ([IO.Path]::GetFileName($RemotePath)) }
    [IO.File]::WriteAllBytes($OutputPath, [Convert]::FromBase64String($result.base64))
    Write-Output "文件已保存：$OutputPath"
} else {
    $result | ConvertTo-Json -Depth 12
}
