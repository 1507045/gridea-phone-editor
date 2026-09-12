<#
  Gridea 打包服务 · 卸载开机自启
  停止并删除开机任务。必须先提权（「卸载开机自启.bat」会自动提权）。
#>

$ErrorActionPreference = 'Stop'

$TaskName = 'Gridea Pack Server'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

function Say {
    param([string]$Text, [string]$Color = 'Gray')
    Write-Host $Text -ForegroundColor $Color
}

Say ''

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($null -eq $task) {
    Say "没有找到任务「$TaskName」——本来就没装。" 'Yellow'
    Say ''
    Read-Host '按回车关闭'
    exit 0
}

try {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
} catch { }

try {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Say "已删除任务：$TaskName" 'Green'
    Say '开机自启已关闭。' 'Green'
} catch {
    Say "删除失败：$($_.Exception.Message)" 'Red'
}

Say ''
Say '（脚本和日志都还在，没动。要重装就跑「安装开机自启.bat」。）' 'DarkGray'
Say ''
Read-Host '按回车关闭'
