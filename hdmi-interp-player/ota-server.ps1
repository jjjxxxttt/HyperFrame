# ============================================================================
# HyperFrame 局域网 OTA 分发/推送服务（PC 端，Windows PowerShell 5.1+）
#
# 用法（和平板连同一个局域网）：
#   powershell -ExecutionPolicy Bypass -File ota-server.ps1
#
# 它做三件事：
#   1. HTTP 分发：GET /version.json 返回最新版本信息，GET /HyperFrame-x.y.z.apk 下载安装包
#      （安装包从 dist 目录里自动挑最新，改完版本直接把新 APK 丢进 dist 即可）；
#   2. UDP 发现应答：App 广播 HYPERFRAME-OTA-DISCOVER 到 8788 端口，这里回服务器地址；
#   3. UDP 推送：dist 目录里出现新 APK 时，向 8789 端口广播 HYPERFRAME-OTA-NEW <versionCode>，
#      平板上的 HyperFrame 收到就自动弹更新提示 —— 这就是"内网自动推送版本更新"。
#
# 实现说明：HTTP 用 TcpListener 而不是 HttpListener —— HttpListener 的 http://+:port/
# 前缀需要管理员或 netsh urlacl 保留，TcpListener 监听任意地址无需任何提权。
# ============================================================================

param(
    # 安装包目录（默认工程下的 dist）
    [string]$DistDir = (Join-Path $PSScriptRoot "dist"),
    # HTTP 分发端口（App 端 OtaUpdater.HTTP_PORT）
    [int]$HttpPort = 8787,
    # UDP 发现应答端口（App 端 OtaUpdater.DISCOVER_PORT）
    [int]$UdpDiscoverPort = 8788,
    # UDP 推送广播端口（App 端 OtaUpdater.PUSH_PORT）
    [int]$PushPort = 8789,
    # 手动指定 versionCode；不给就按约定从版本名推导（major*100 + minor*10 + patch，
    # 如 1.0.0 -> 100，与 App 的 versionCode 同步递增约定一致）
    [int]$VersionCode = 0
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $DistDir)) {
    New-Item -ItemType Directory -Path $DistDir | Out-Null
}

# ---- 版本信息：dist 里最新的 HyperFrame-*.apk ----
function Get-UpdateInfo {
    $apk = Get-ChildItem -Path $DistDir -Filter 'HyperFrame-*.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $apk) { return $null }

    $vName = '0.0.0'
    $vCode = $VersionCode
    if ($apk.BaseName -match 'HyperFrame-(\d+)\.(\d+)\.(\d+)$') {
        $vName = "$($Matches[1]).$($Matches[2]).$($Matches[3])"
        if ($vCode -le 0) {
            $vCode = [int]$Matches[1] * 100 + [int]$Matches[2] * 10 + [int]$Matches[3]
        }
    }
    if ($vCode -le 0) { $vCode = 1 }

    $sha = (Get-FileHash -Algorithm SHA256 -Path $apk.FullName).Hash.ToLower()
    return @{
        versionCode = $vCode
        versionName = $vName
        apk         = $apk.Name
        sha256      = $sha
        notes       = "局域网推送（$($apk.Name)）"
    }
}

function Send-TcpResponse($client, $status, $contentType, $bodyBytes) {
    $header = "HTTP/1.1 $status`r`nContent-Type: $contentType`r`nContent-Length: $($bodyBytes.Length)`r`nConnection: close`r`n`r`n"
    $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
    $stream = $client.GetStream()
    $stream.Write($headerBytes, 0, $headerBytes.Length)
    if ($bodyBytes.Length -gt 0) { $stream.Write($bodyBytes, 0, $bodyBytes.Length) }
    $stream.Flush()
}

function Handle-HttpClient($client) {
    try {
        $client.ReceiveTimeout = 3000
        $stream = $client.GetStream()
        # 读请求头（到空行为止，上限 8KB 就够：我们只处理 GET）
        $buf = New-Object byte[] 8192
        $pos = 0
        while ($pos -lt $buf.Length) {
            $n = $stream.Read($buf, $pos, $buf.Length - $pos)
            if ($n -le 0) { break }
            $pos += $n
            $text = [System.Text.Encoding]::ASCII.GetString($buf, 0, $pos)
            if ($text.Contains("`r`n`r`n")) { break }
        }
        $reqText = [System.Text.Encoding]::ASCII.GetString($buf, 0, $pos)
        $firstLine = ($reqText -split "`r`n")[0]
        if ($firstLine -notmatch '^GET\s+(\S+)') {
            Send-TcpResponse $client '400 Bad Request' 'text/plain' ([System.Text.Encoding]::UTF8.GetBytes('bad request'))
            return
        }
        $path = $Matches[1]
        Write-Host ("[HTTP] GET " + $path)

        if ($path -eq '/' -or $path -eq '/index.html') {
            $info = Get-UpdateInfo
            $msg = "HyperFrame OTA server`r`n"
            if ($null -ne $info) {
                $msg += ("latest: v" + $info.versionName + " (code " + $info.versionCode + ")  file: " + $info.apk + "`r`n")
            } else {
                $msg += ("no apk in " + $DistDir + "`r`n")
            }
            Send-TcpResponse $client '200 OK' 'text/plain; charset=utf-8' ([System.Text.Encoding]::UTF8.GetBytes($msg))
            return
        }

        if ($path -eq '/version.json') {
            $info = Get-UpdateInfo
            if ($null -eq $info) {
                Send-TcpResponse $client '404 Not Found' 'text/plain' ([System.Text.Encoding]::UTF8.GetBytes('no apk'))
                return
            }
            $json = ($info | ConvertTo-Json -Compress)
            Send-TcpResponse $client '200 OK' 'application/json; charset=utf-8' ([System.Text.Encoding]::UTF8.GetBytes($json))
            return
        }

        # /HyperFrame-x.y.z.apk —— 只允许 dist 目录下的文件名，杜绝路径穿越
        $name = $path.TrimStart('/')
        if ($name -match '[\\/]' -or $name.Contains('..')) {
            Send-TcpResponse $client '403 Forbidden' 'text/plain' ([System.Text.Encoding]::UTF8.GetBytes('forbidden'))
            return
        }
        $file = Join-Path $DistDir $name
        if (-not (Test-Path $file)) {
            Send-TcpResponse $client '404 Not Found' 'text/plain' ([System.Text.Encoding]::UTF8.GetBytes('not found'))
            return
        }
        $bytes = [System.IO.File]::ReadAllBytes($file)
        Send-TcpResponse $client '200 OK' 'application/vnd.android.package-archive' $bytes
        Write-Host ("[HTTP] 已发送 " + $name + " (" + $bytes.Length + " 字节)")
    } catch {
        Write-Host ("[HTTP] 处理出错: " + $_.Exception.Message)
    } finally {
        try { $client.Close() } catch { }
    }
}

function Send-PushBroadcast($versionCodeVal) {
    try {
        $udp = New-Object System.Net.Sockets.UdpClient
        $udp.EnableBroadcast = $true
        $msg = [System.Text.Encoding]::UTF8.GetBytes("HYPERFRAME-OTA-NEW $versionCodeVal")
        $udp.Send($msg, $msg.Length, [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Broadcast, $PushPort)) | Out-Null
        $udp.Close()
        Write-Host ("[推送] 已广播 HYPERFRAME-OTA-NEW " + $versionCodeVal + " -> 255.255.255.255:" + $PushPort)
    } catch {
        Write-Host ("[推送] 广播失败: " + $_.Exception.Message)
    }
}

# ---- 启动：HTTP 监听 + UDP 发现应答 + dist 目录监视推送 ----
$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $HttpPort)
$listener.Start()

$discover = New-Object System.Net.Sockets.UdpClient($UdpDiscoverPort)
$discover.Client.ReceiveTimeout = 200

$watcher = New-Object System.IO.FileSystemWatcher
$watcher.Path = $DistDir
$watcher.Filter = '*.apk'
$watcher.EnableRaisingEvents = $true
$null = Register-ObjectEvent -InputObject $watcher -EventName Created -Action {
    Start-Sleep -Seconds 1   # 等文件写完再算哈希/广播
    $info = Get-UpdateInfo
    if ($null -ne $info) { Send-PushBroadcast $info.versionCode }
}

$localIps = [System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) |
    Where-Object { $_.AddressFamily -eq 'InterNetwork' } | ForEach-Object { $_.IPAddressToString }
Write-Host '============================================================'
Write-Host ' HyperFrame 局域网 OTA 服务已启动'
Write-Host (" 安装包目录 : " + $DistDir)
Write-Host (" HTTP 分发  : http://<本机IP>:" + $HttpPort + "/version.json")
Write-Host (" UDP 发现   : " + $UdpDiscoverPort + " 端口（App 自动发现）")
Write-Host (" UDP 推送   : " + $PushPort + " 端口（新 APK 丢进 dist 即自动推送）")
foreach ($ip in $localIps) {
    Write-Host ("   可用地址 : http://" + $ip + ":" + $HttpPort)
}
Write-Host ' 平板端：打开 HyperFrame -> 检查更新（内网）；或等启动自动检查/推送弹窗'
Write-Host ' 按 Ctrl+C 停止'
Write-Host '============================================================'

try {
    while ($true) {
        # HTTP：一次处理一个连接（同时只有平板一个客户端，串行足够）
        while ($listener.Pending()) {
            Handle-HttpClient $listener.AcceptTcpClient()
        }

        # UDP 发现应答
        if ($discover.Client.Poll(0, [System.Net.Sockets.SelectMode]::SelectRead)) {
            $remote = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
            $data = $discover.Receive([ref]$remote)
            $text = [System.Text.Encoding]::UTF8.GetString($data).Trim()
            if ($text -eq 'HYPERFRAME-OTA-DISCOVER') {
                $reply = [System.Text.Encoding]::UTF8.GetBytes("HYPERFRAME-OTA-SERVER $HttpPort")
                $discover.Send($reply, $reply.Length, $remote) | Out-Null
                Write-Host ("[发现] 应答 " + $remote.Address + " -> 服务器端口 " + $HttpPort)
            }
        }

        Start-Sleep -Milliseconds 100
    }
} finally {
    try { $listener.Stop() } catch { }
    try { $discover.Close() } catch { }
    Unregister-Event -SourceIdentifier $watcher.Created -ErrorAction SilentlyContinue
    $watcher.Dispose()
}
