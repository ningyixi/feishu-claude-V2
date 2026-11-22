# 以后端「脱离调用者会话」的方式启动服务，并把日志固定落盘。
# 目的：Claude Code 会话或后台任务被回收时不再连带杀掉服务（2026-11-22 事故复盘），
#      同时让日志有稳定路径可 tail/grep，不再散在会话临时目录里。
# 用法：  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-backend-detached.ps1
# 停止：  读 D:\feishu-claude-data\run\backend.pid 里的 PID 后 taskkill //PID <pid> //F
$ErrorActionPreference = 'Stop'

$RunDir = 'D:\feishu-claude-data\run'
$Jar    = Join-Path $RunDir 'app.jar'
$Log    = Join-Path $RunDir 'backend.log'
$ErrLog = Join-Path $RunDir 'backend.err.log'
$PidFile = Join-Path $RunDir 'backend.pid'
$Java   = 'C:\Program Files\Java\jdk-21\bin\java.exe'

if (-not (Test-Path $Jar))  { throw "找不到 $Jar（先 mvn package 再把 target/*.jar 复制过来）" }
if (-not (Test-Path $Java)) { throw "找不到 JDK：$Java" }

# 日志轮转：保留上一份 .prev，避免一次启动把历史冲掉
foreach ($f in @($Log, $ErrLog)) {
  if (Test-Path $f) { Copy-Item $f "$f.prev" -Force }
}

$env:SPRING_PROFILES_ACTIVE = 'mysql'
$env:FEISHU_MYSQL_URL = 'jdbc:mysql://localhost:3306/feishu_claude?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&preserveInstants=true&allowPublicKeyRetrieval=true&useSSL=false'
$env:FEISHU_MYSQL_USER = 'feishu'
$env:FEISHU_MYSQL_PASSWORD = (Get-Content 'D:\mysql-local\.migrate_pw' -Raw).Trim()
$env:FEISHU_KB_PATH = 'D:\feishu-claude-data\supplier-kb'

$p = Start-Process -FilePath $Java `
  -ArgumentList '-jar', "`"$Jar`"" `
  -WorkingDirectory $RunDir `
  -RedirectStandardOutput $Log `
  -RedirectStandardError $ErrLog `
  -WindowStyle Hidden -PassThru

Set-Content -Path $PidFile -Value $p.Id -Encoding ascii
Write-Host "后端已脱离会话启动：PID=$($p.Id)"
Write-Host "  日志    tail -f /d/feishu-claude-data/run/backend.log"
Write-Host "  错误日志 $ErrLog"
Write-Host "  停止    taskkill //PID $($p.Id) //F"
