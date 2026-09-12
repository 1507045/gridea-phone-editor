<#
  Gridea 打包服务 · 安装开机自启
  ==============================

  做三件事：
    1. 注册一个开机任务（SYSTEM 身份、最高权限）——这样不会弹 UAC，也不需要用户登录
    2. 立刻启动一次，不用等下次开机
    3. 探测端口、打印手机该填的地址

  必须先提权。「安装开机自启.bat」会自动提权。
#>

$ErrorActionPreference = 'Stop'

$TaskName = 'Gridea Pack Server'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ServerScript = Join-Path $ScriptDir 'gridea-pack-server.ps1'
$ConfigPath = Join-Path $ScriptDir 'pack-config.json'

function Say {
    param([string]$Text, [string]$Color = 'Gray')
    Write-Host $Text -ForegroundColor $Color
}

function Fail {
    param([string]$Text)
    Say $Text 'Red'
    Read-Host '按回车关闭'
    exit 1
}

if (-not (Test-Path -LiteralPath $ServerScript)) {
    Fail "找不到 $ServerScript"
}

# 从配置里读端口，读不到就用 8765
$port = 8765
$siteDir = ''
if (Test-Path -LiteralPath $ConfigPath) {
    try {
        $cfg = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($cfg.Port) { $port = [int]$cfg.Port }
        if ($cfg.SiteDir) { $siteDir = $cfg.SiteDir }
    } catch { }
}

Say ''
Say '=====================================================' 'Cyan'
Say '  Gridea 打包服务 · 安装开机自启' 'Cyan'
Say '=====================================================' 'Cyan'
Say ''
Say "  站点目录：$siteDir"
Say "  监听端口：$port"
Say ''

# ── 注册任务 ──────────────────────────────────────────

Say '正在注册开机任务…' 'Cyan'

$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}" -NonInteractive' -f $ServerScript)

# 开机触发。不用 AtLogon 是有意的：那要等用户登录，服务才起来。
$trigger = New-ScheduledTaskTrigger -AtStartup

# SYSTEM 身份 + 最高权限：天然绕过 UAC，且不依赖任何用户登录。
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest

$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit ([TimeSpan]::Zero)

try {
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
        -Principal $principal -Settings $settings -Force | Out-Null
} catch {
    Fail "注册任务失败：$($_.Exception.Message)"
}

Say "已注册：$TaskName" 'Green'

# ── 立即启动 ──────────────────────────────────────────

Say '正在启动服务…' 'Cyan'
try {
    Start-ScheduledTask -TaskName $TaskName
} catch {
    Say "启动命令失败：$($_.Exception.Message)" 'Yellow'
}

# 给它几秒起来，再探测。不探测就只能瞎猜。
Start-Sleep -Seconds 4

$online = $false
try {
    $resp = Invoke-WebRequest -Uri "http://127.0.0.1:$port/ping" -TimeoutSec 6 -UseBasicParsing
    if ($resp.StatusCode -eq 200) { $online = $true }
} catch { }

Say ''

if ($online) {
    Say '服务已在线。' 'Green'
} else {
    Say '端口没通——服务可能没起来。' 'Yellow'
    Say "  去看日志：$(Join-Path $ScriptDir 'pack-server.log')" 'Yellow'
    Say '  常见原因：端口被占用；或站点目录不在。' 'Yellow'
}

$state = (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue).State
Say "任务状态：$state"
Say ''

# ── 手机端怎么填 ──────────────────────────────────────

Say '手机上「同步 → 压缩包通道 → 电脑端打包服务地址」填下面这一行：' 'Yellow'

$ips = @()
try {
    $ips = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object {
            $_.IPAddress -notlike '127.*' -and
            $_.IPAddress -notlike '169.254.*' -and
            $_.PrefixOrigin -ne 'WellKnown'
        } |
        Select-Object -ExpandProperty IPAddress)
} catch { }

# 挑一个最可能是局域网地址的：192.168 / 10. / 172.16-31 优先
$pick = @($ips | Where-Object { $_ -like '192.168.*' })
if ($pick.Count -eq 0) { $pick = @($ips | Where-Object { $_ -like '10.*' }) }
if ($pick.Count -eq 0) { $pick = $ips }
$primary = if ($pick.Count -gt 0) { $pick[0] } else { '<这台电脑的IP>' }

if ($ips.Count -eq 0) {
    Say "    http://<这台电脑的IP>:$port" 'Green'
    Say '    （没检测到局域网 IP，确认电脑连着 Wi-Fi 或网线）' 'DarkGray'
} else {
    foreach ($ip in $ips) {
        Say "    http://$ip`:$port" 'Green'
    }
    Say '    （有多个就挑跟手机同一个网段的，一般是 192.168.x.x 那个）' 'DarkGray'
}

Say "  压缩包路径填：site.zip"
Say ''

# 落一份说明文件，免得以后忘了填什么
$readmePath = Join-Path $ScriptDir '手机端填写说明.txt'
$readme = @"
Gridea Pro 手机版 · 同步配置
============================

手机上打开「同步 → 压缩包通道」，按下面填：

  通过压缩包同步          打开
  压缩包在远端的路径      site.zip
  电脑端打包服务地址      http://$primary`:$port

填完点「测试打包服务」，出现「打包服务在线」就可以了。

------------------------------
重要：这个 IP 会变
------------------------------
$primary 是路由器临时分配给你的。电脑重启、路由器重启之后，
它可能变成别的地址，手机那边就会连不上。

要一劳永逸，去路由器后台把这个 IP 固定给这台电脑
（一般在「DHCP 静态分配 / 地址保留」那一栏，按 MAC 地址绑定），
或者在这台电脑上手动设一个静态 IP。

------------------------------
服务信息
------------------------------
站点目录：$siteDir
监听端口：$port
日志：    $(Join-Path $ScriptDir 'pack-server.log')

服务已注册为开机自启（任务名：$TaskName），开机后自动在后台运行。
要关掉：双击「卸载开机自启.bat」。
"@

try {
    Set-Content -LiteralPath $readmePath -Value $readme -Encoding UTF8
    Say "已写出配置说明：$readmePath" 'Green'
} catch {
    Say "写说明文件失败：$($_.Exception.Message)" 'Yellow'
}

Say ''
Say '以后开机自动跑，不用管了。' 'Green'
Say "想验证：浏览器打开 http://$primary`:$port/ping，看到 ok:true 就是好的。" 'DarkGray'
Say ''
Say "日志：$(Join-Path $ScriptDir 'pack-server.log')" 'DarkGray'
Say ''
Read-Host '按回车关闭'
