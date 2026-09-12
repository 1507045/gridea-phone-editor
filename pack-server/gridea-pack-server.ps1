<#
  Gridea Pro 手机版 · 电脑端打包服务
  ==================================

  为什么需要它
  ------------
  SMB 和 WebDAV 都只是「文件读写」协议——列目录、下载、上传、删除，仅此而已。
  没有任何一条指令能让手机命令这台电脑执行压缩动作，这是协议的能力边界。
  所以想让「手机上点一下同步，电脑就打包」，电脑上必须有个常驻的小服务收这个请求。

  它做的事
  --------
  监听一个端口，收到 GET /pack 就把站点目录打包成 zip 写进站点目录里，
  手机再从共享目录把 zip 下载走、解压、比对。比逐个文件传输快得多。

  当前站点目录
  ------------
  由 pack-config.json（脚本同目录）或 -SiteDir 参数决定；没有配置时脚本会提示你设置。

  用法
  ----
  【推荐】双击「安装开机自启.bat」——提权后注册一个开机任务，之后什么都不用管，
  开机自动在后台跑，不弹窗口。

  【临时调试】双击「启动打包服务.bat」——前台开一个窗口，关掉就停。

  为什么要管理员权限
  ------------------
  Windows 只允许管理员监听「局域网可访问」的地址（http://+:端口）。
  普通权限只能监听本机回环，手机根本连不上。开机任务用 SYSTEM 身份运行，
  天然有权限，也不会弹 UAC。

  日志
  ----
  同目录的 pack-server.log。后台跑的时候没窗口，出问题就看它。
#>

param(
    [int]$Port = 8765,
    # 站点目录。留空时必须用 -SiteDir 参数或在同目录放一个 pack-config.json 指定，
    # 否则脚本会直接报错退出。不写死默认路径，避免把个人路径带进开源仓库。
    [string]$SiteDir = '',
    # 后台无人值守运行（开机自启走这条）。此时绝不 Read-Host——没人按键，会永远卡住。
    [switch]$NonInteractive
)

$ErrorActionPreference = 'Stop'

# 两个都要加载：ZipFile 在 FileSystem 里，ZipArchiveMode 枚举在基础库里。
# 只加载前者时，[System.IO.Compression.ZipArchiveMode] 会解析不到。
Add-Type -AssemblyName System.IO.Compression | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ConfigPath = Join-Path $ScriptDir 'pack-config.json'
$LogPath = Join-Path $ScriptDir 'pack-server.log'

# 前台双击启动时为真——出错停下来让人看清原因。
# 开机自启（-NonInteractive）时为假——出错只写日志、直接退出，不能等按键。
$Interactive = -not $NonInteractive

# 开机自启后这个服务没有窗口，看不到任何输出。
# 所以关键事件（启动、打包、失败）除了写控制台，还必须落一份文件日志。
if (Test-Path -LiteralPath $LogPath) {
    try {
        if ((Get-Item -LiteralPath $LogPath).Length -gt 2MB) {
            Remove-Item -LiteralPath $LogPath -Force
        }
    } catch { }
}

function Write-Log {
    param([string]$Text, [string]$Color = 'Gray')
    $line = '[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Text
    if ($Interactive) { Write-Host $line -ForegroundColor $Color }
    try { Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8 } catch { }
}

function Stop-WithMessage {
    param([string]$Message)
    Write-Log $Message 'Red'
    if ($Interactive) { Read-Host '按回车关闭' }
    exit 1
}

# ───────────────────────── 配置 ─────────────────────────

function Read-PackConfig {
    if (-not (Test-Path -LiteralPath $ConfigPath)) { return $null }
    try {
        $raw = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8
        if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
        return $raw | ConvertFrom-Json
    } catch {
        return $null
    }
}

function Save-PackConfig($cfg) {
    $cfg | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $ConfigPath -Encoding UTF8
}

<#
  定下要打包哪个目录。

  顺序：pack-config.json → $SiteDir 参数（留空时必须显式指定，否则报错退出）。
  之所以不再交互提问：这个服务是开机自启后台跑的，没人坐在那儿敲路径，
  一旦弹 Read-Host 就会永远卡住。
#>
function Resolve-PackConfig {
    $existing = Read-PackConfig
    if ($null -ne $existing -and (Test-Path -LiteralPath $existing.SiteDir)) {
        return $existing
    }
    if ($null -ne $existing) {
        Write-Log "配置里的站点目录找不到了：$($existing.SiteDir)，改用默认目录 $SiteDir" 'Yellow'
    }

    if (-not (Test-Path -LiteralPath $SiteDir)) {
        Write-Log "站点目录不存在：$SiteDir（改脚本顶部的 `$SiteDir 默认值，或用 -SiteDir 指定）" 'Red'
        return $null
    }
    $item = Get-Item -LiteralPath $SiteDir
    if (-not $item.PSIsContainer) {
        Write-Log "这不是一个文件夹：$SiteDir" 'Red'
        return $null
    }

    $cfg = [pscustomobject]@{
        SiteDir     = $item.FullName
        ArchiveName = 'site.zip'
        Port        = $Port
        ExcludeDirs = @('output', '.git', 'node_modules', '.idea')
    }
    Save-PackConfig $cfg

    Write-Host "站点目录：$($item.FullName)" -ForegroundColor Green
    Write-Host "（已记入 pack-config.json，以后改这里就行）" -ForegroundColor DarkGray
    return $cfg
}

# ───────────────────────── 打包 ─────────────────────────

function Invoke-PackSite {
    param(
        $cfg,
        [string]$destPath,
        # 只打「文章与配置」相关的目录，跟手机端「只同步文章与配置」开关对应。
        # 站点里 themes 往往有两千多个文件、上百 MB，手机根本不要它们，
        # 打进去只会让每次同步多传一个数量级。
        [switch]$ContentOnly
    )

    $base = (Get-Item -LiteralPath $cfg.SiteDir).FullName.TrimEnd('\')
    if (Test-Path -LiteralPath $destPath) {
        Remove-Item -LiteralPath $destPath -Force
    }

    $excludes = @()
    if ($cfg.ExcludeDirs) { $excludes = @($cfg.ExcludeDirs) }

    # 与手机端 Vault.CONTENT_DIRS 保持一致，改一边就得改另一边
    $contentDirs = @('posts', 'config', 'post-images', 'images')

    $files = Get-ChildItem -LiteralPath $base -Recurse -File -Force -ErrorAction SilentlyContinue

    # 跳过符号链接/联接点：Windows PowerShell 5.1 的 -Recurse 会跟进这些重解析点，
    # 站点目录里一旦有个指回上级的联接，递归就成死循环了。
    $reparseBit = [int][System.IO.FileAttributes]::ReparsePoint
    $files = @($files | Where-Object { -not ([int]$_.Attributes -band $reparseBit) })

    $count = 0
    $bytes = 0L

    # 第二个参数用字符串而不是枚举字面量：
    # PowerShell 5.1 下 [System.IO.Compression.ZipArchiveMode] 经常解析不到，
    # 写 'Create' 交给方法绑定器转，反而更稳。
    $zip = [System.IO.Compression.ZipFile]::Open($destPath, 'Create')
    try {
        foreach ($f in $files) {
            $rel = $f.FullName.Substring($base.Length + 1).Replace('\', '/')

            # 根目录下的 zip 一律不打进去，两个原因：
            #   site.zip 是本服务的产物，下次打包时它还在原地；
            #   blog.zip 这类是手工备份，动辄几百 MB，卷进来能把同步拖垮。
            # 只针对根目录——子目录里的 zip 可能是主题自带的资源，不能动。
            if ($rel.IndexOf('/') -lt 0 -and $rel.ToLower().EndsWith('.zip')) { continue }

            # 只要文章与配置时，其余顶层目录整个跳过
            if ($ContentOnly) {
                $top = $rel.Split('/')[0]
                if ($contentDirs -notcontains $top) { continue }
            }

            $skip = $false
            foreach ($d in $excludes) {
                if ($rel -eq $d -or $rel.StartsWith($d + '/')) { $skip = $true; break }
            }
            if ($skip) { continue }

            $entry = $zip.CreateEntry($rel, 'Optimal')

            # zip 的时间字段范围是 1980..2107，越界会抛异常。
            # 这个时间戳必须写对：手机那边解压后要靠它判断哪个文件更新，
            # 时间全一样的话每次同步都会全量重传。
            $t = $f.LastWriteTime
            if ($t.Year -lt 1980) { $t = Get-Date '1980-01-01' }
            if ($t.Year -gt 2107) { $t = Get-Date '2107-01-01' }
            $entry.LastWriteTime = [System.DateTimeOffset]$t

            $es = $entry.Open()
            try {
                $fs = [System.IO.File]::OpenRead($f.FullName)
                try { $fs.CopyTo($es) } finally { $fs.Dispose() }
            } finally {
                $es.Dispose()
            }

            $count++
            $bytes += $f.Length
        }
    } finally {
        $zip.Dispose()
    }

    return [pscustomobject]@{ Files = $count; Bytes = $bytes }
}

# ───────────────────────── HTTP ─────────────────────────

function Send-Json {
    param($ctx, $payload, [int]$status = 200)
    $json = $payload | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $ctx.Response.StatusCode = $status
    $ctx.Response.ContentType = 'application/json; charset=utf-8'
    $ctx.Response.ContentLength64 = $bytes.Length
    $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $ctx.Response.Close()
}

function Test-IsAdmin {
    try {
        $id = [System.Security.Principal.WindowsIdentity]::GetCurrent()
        $p = New-Object System.Security.Principal.WindowsPrincipal($id)
        return $p.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)
    } catch {
        return $false
    }
}

function Get-LanIps {
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
    return $ips
}

# ───────────────────────── 主流程 ─────────────────────────

function Invoke-Server {
    $cfg = Resolve-PackConfig
    if ($null -eq $cfg) {
        Stop-WithMessage "没有可用的站点目录，服务无法启动。"
    }

    if (-not (Test-Path -LiteralPath $cfg.SiteDir)) {
        Stop-WithMessage "站点目录找不到了：$($cfg.SiteDir)"
    }

    $listenPort = if ($cfg.Port) { [int]$cfg.Port } else { $Port }
    $archiveName = if ($cfg.ArchiveName) { $cfg.ArchiveName } else { 'site.zip' }
    $zipPath = Join-Path $cfg.SiteDir $archiveName

    $listener = New-Object System.Net.HttpListener
    try {
        # '+' 表示监听所有网卡，这是手机能连上的前提，也是要管理员权限的原因
        $listener.Prefixes.Add("http://+:$listenPort/")
        $listener.Start()
    } catch {
        try { $listener.Close() } catch { }
        $why = if (-not (Test-IsAdmin)) {
            '没有管理员权限。手机要连进来就必须监听局域网地址，Windows 只允许管理员这么做。' + "`n" +
            '做法：右键「启动打包服务.bat」→ 以管理员身份运行。'
        } else {
            "多半是端口 $listenPort 被别的程序占用了。换一个：编辑 gridea-pack-server.ps1，" +
            '把结尾那行的 -Port 改成别的数（例如 8899），然后重新运行「安装开机自启.bat」。'
        }
        Stop-WithMessage ("监听端口 $listenPort 失败：$($_.Exception.Message)`n$why")
    }

    # 防火墙放行（管理员才有权限；失败也不致命，只是手机可能连不上）
    try {
        $ruleName = 'Gridea Pack Server'
        $existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
        if (-not $existing) {
            New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow `
                -Protocol TCP -LocalPort $listenPort -ErrorAction Stop | Out-Null
            Write-Log "已添加防火墙规则（放行 TCP $listenPort）" 'DarkGray'
        }
    } catch {
        Write-Log '没能自动加防火墙规则。若手机连不上，手动放行这个端口。' 'Yellow'
    }

    $ips = Get-LanIps

    Write-Host ''
    Write-Host '=====================================================' -ForegroundColor Cyan
    Write-Host '  Gridea 打包服务已启动' -ForegroundColor Cyan
    Write-Host '=====================================================' -ForegroundColor Cyan
    Write-Host "  站点目录：$($cfg.SiteDir)"
    Write-Host "  打包文件：$archiveName"
    Write-Host "  监听端口：$listenPort"
    Write-Host ''
    Write-Host '  手机上「打包服务地址」照抄下面这一行：' -ForegroundColor Yellow

    if ($ips.Count -eq 0) {
        Write-Host "    http://<这台电脑的IP>:$listenPort" -ForegroundColor Green
        Write-Host '    （没检测到局域网 IP，确认电脑连着 Wi-Fi 或网线）' -ForegroundColor DarkGray
    } else {
        foreach ($ip in $ips) {
            Write-Host "    http://$ip`:$listenPort" -ForegroundColor Green
        }
        Write-Host '    （有多个就挑跟手机同一个网段的，一般是 192.168.x.x 那个）' -ForegroundColor DarkGray
    }

    Write-Host ''
    Write-Host '  保持这个窗口开着。关掉窗口服务就停了。' -ForegroundColor DarkGray
    Write-Host '  按 Ctrl+C 退出。' -ForegroundColor DarkGray
    Write-Host ''

    Write-Log "服务已启动 | 站点=$($cfg.SiteDir) | 端口=$listenPort | IP=$($ips -join ',')" 'Green'

    while ($listener.IsListening) {
        $ctx = $null
        try {
            $ctx = $listener.GetContext()
            $reqPath = $ctx.Request.Url.AbsolutePath.TrimEnd('/')
            if ([string]::IsNullOrEmpty($reqPath)) { $reqPath = '/' }

            switch ($reqPath) {
                '/ping' {
                    Send-Json $ctx @{ ok = $true; version = 1; site = $cfg.SiteDir }
                }
                '/pack' {
                    # 手机端带 ?scope=content 表示「只同步文章与配置」开着，
                    # 那就别把 themes 那两千多个文件白打包一遍
                    $scopeParam = $ctx.Request.QueryString['scope']
                    $contentOnly = ($scopeParam -eq 'content')

                    $sw = [System.Diagnostics.Stopwatch]::StartNew()
                    $r = Invoke-PackSite -cfg $cfg -destPath $zipPath -ContentOnly:$contentOnly
                    $sw.Stop()
                    $secs = [int][Math]::Round($sw.Elapsed.TotalSeconds)
                    $kb = [int][Math]::Round($r.Bytes / 1024)
                    $mark = if ($contentOnly) { '（仅文章与配置）' } else { '' }
                    Write-Log "打包完成$mark：$($r.Files) 个文件 / $kb KB / ${secs}s" 'Green'
                    Send-Json $ctx @{
                        ok      = $true
                        files   = $r.Files
                        bytes   = $r.Bytes
                        seconds = $secs
                    }
                }
                default {
                    Send-Json $ctx @{ ok = $false; error = "未知路径：$reqPath" } 404
                }
            }
        } catch {
            $msg = $_.Exception.Message
            Write-Log "处理请求出错：$msg" 'Red'
            if ($null -ne $ctx) {
                try { Send-Json $ctx @{ ok = $false; error = $msg } 500 } catch { }
            }
        }
    }

    $listener.Stop()
    Write-Log '服务已停止'
}

# 只有「直接运行」才启动服务。
# 被 dot-source（. .\gridea-pack-server.ps1）时只加载函数，方便单独测打包逻辑。
if ($MyInvocation.InvocationName -ne '.') {
    Invoke-Server
}
