# telbank-rbac 一键启动（Windows / PowerShell 5.1+）
#
# 同时启动权限中心后端（demo profile）与管理前端（开发模式），可选自动准备临时 MySQL。
#
# 用法（在仓库根目录）：
#   .\scripts\start-all.cmd --temp-db
#   .\scripts\start-all.cmd
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-all-windows.ps1 --temp-db
#
# 参数：
#   -TempDb            自动启动一次性临时 MySQL（默认 3307）并创建 telbank_rbac，退出时清理
#   -InitDb            针对已有 MySQL 先执行 scripts/init-db.sh（需 MYSQL_* 环境变量）
#   -ApiPort 8080      后端端口
#   -FrontPort 5173    前端端口
#   -TempMysqlPort 3307 临时 MySQL 端口（仅 -TempDb）
#
# 前置：JDK 21+、Maven、Node 22+（npm 在 PATH；或 nvm-windows 已切换）、可选本地 MySQL。
# 环境变量：VITE_API_BASE_URL（前端请求后端地址）、RBAC_DB_URL/RBAC_*（透传后端）。

param(
    [switch]$TempDb,
    [switch]$InitDb,
    [int]$ApiPort = 8080,
    [int]$FrontPort = 5173,
    [int]$TempMysqlPort = 3307,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$LogDir = Join-Path $Root 'logs'
$ApiLog = Join-Path $LogDir 'api.log'
$FrontLog = Join-Path $LogDir 'front.log'
$TempMysqlLog = Join-Path $LogDir 'temp-mysql.log'
$ApiProc = $null
$FrontProc = $null
$DbProc = $null
$TempDbStarted = $false
$Running = $false
$TempDir = Join-Path $env:TEMP 'telbank-rbac-mysql'

function Show-Usage {
    Write-Host @"
用法: .\scripts\start-all.cmd [--temp-db] [--init-db]

  --temp-db   自动启动一次性临时 MySQL（默认 3307）并创建 telbank_rbac，退出时清理
  --init-db   针对已有 MySQL 先执行 scripts/init-db.sh（需 MYSQL_* 环境变量）

PowerShell 参数：-TempDb -InitDb -ApiPort 8080 -FrontPort 5173 -TempMysqlPort 3307
"@
}

function Test-PortInUse {
    param([int]$Port)
    $conn = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    return [bool]$conn
}

function Stop-PortProcess {
    param([int]$Port, [switch]$Force)
    Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique |
        ForEach-Object {
            if ($_ -gt 0) {
                if ($Force) { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
                else { Stop-Process -Id $_ -ErrorAction SilentlyContinue }
            }
        }
}

function Get-HttpStatus {
    param([string]$Url)
    try {
        $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop
        return [int]$resp.StatusCode
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            return [int]$_.Exception.Response.StatusCode
        }
        return 0
    }
}

function Wait-HttpStatus {
    param([string]$Url, [int]$TimeoutSeconds, [int[]]$Expected, [string]$Name, $Process, [string]$LogPath)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    Write-Host -NoNewline "    等待${Name}就绪"
    while ((Get-Date) -lt $deadline) {
        if ($Process -and $Process.HasExited) {
            Write-Host ""
            Write-Host "❌ ${Name}进程已退出，日志尾部：" -ForegroundColor Red
            if (Test-Path $LogPath) { Get-Content $LogPath -Tail 40 }
            throw "${Name}启动失败"
        }
        $code = Get-HttpStatus -Url $Url
        if ($Expected -contains $code) {
            Write-Host " 就绪"
            return
        }
        Write-Host -NoNewline "."
        Start-Sleep -Seconds 1
    }
    Write-Host ""
    Write-Host "❌ ${Name}在 ${TimeoutSeconds} 秒内未就绪，日志尾部：" -ForegroundColor Red
    if (Test-Path $LogPath) { Get-Content $LogPath -Tail 40 }
    throw "${Name}就绪超时"
}

function Assert-Command {
    param([string]$Name, [string]$Hint)
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $cmd) { throw "未找到 $Name。$Hint" }
    return $cmd
}

function Test-Java21 {
    $javaExe = $null
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
    } else {
        $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($cmd) { $javaExe = $cmd.Source }
    }
    if (-not $javaExe) { throw "未找到 java。请安装 JDK 21+ 并设置 JAVA_HOME 或加入 PATH。" }
    $ver = (& $javaExe -version 2>&1 | Out-String)
    if ($ver -notmatch '"?(2[1-9]|[3-9][0-9])\.') {
        Write-Host "❌ 需要 JDK 21+，当前：$ver" -ForegroundColor Red
        throw "JDK 版本不满足"
    }
    return $javaExe
}

function Stop-Everything {
    if (-not $Running -and -not $TempDbStarted) { return }
    Write-Host ""
    Write-Host "正在停止前后端 ..."
    foreach ($p in @($ApiProc, $FrontProc)) {
        if ($p -and -not $p.HasExited) {
            & taskkill /PID $p.Id /T /F *> $null
        }
    }
    Stop-PortProcess -Port $ApiPort
    Stop-PortProcess -Port $FrontPort
    Start-Sleep -Seconds 1
    Stop-PortProcess -Port $ApiPort -Force
    Stop-PortProcess -Port $FrontPort -Force
    if ($TempDbStarted) {
        Write-Host "正在停止临时 MySQL（${TempMysqlPort}）并清理数据目录 ..."
        if ($DbProc -and -not $DbProc.HasExited) {
            & taskkill /PID $DbProc.Id /T /F *> $null
        }
        Start-Sleep -Seconds 1
        if (Test-Path $TempDir) {
            Remove-Item -Recurse -Force $TempDir -ErrorAction SilentlyContinue
        }
    }
    Write-Host "已停止（端口 $ApiPort/$FrontPort 已释放）"
}

function Find-Mysqld {
    foreach ($name in @('mysqld.exe', 'mysqld')) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    $candidates = @(
        (Join-Path $env:ProgramFiles 'MySQL\MySQL Server 8.0\bin\mysqld.exe'),
        (Join-Path $env:ProgramFiles 'MySQL\MySQL Server 8.4\bin\mysqld.exe'),
        (Join-Path $env:ProgramFiles 'MySQL\MySQL Server 8.1\bin\mysqld.exe')
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { return $c }
    }
    return $null
}

function Find-MysqlClient {
    foreach ($name in @('mysql.exe', 'mysql')) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    $mysqld = Find-Mysqld
    if ($mysqld) {
        $candidate = Join-Path (Split-Path -Parent $mysqld) 'mysql.exe'
        if (Test-Path $candidate) { return $candidate }
    }
    return $null
}

function Invoke-InitSchema {
    param(
        [string]$HostName = '127.0.0.1',
        [int]$Port = 3306,
        [string]$User = 'root',
        [string]$Password = '',
        [string]$DbName = 'telbank_rbac'
    )
    $mysql = Find-MysqlClient
    if (-not $mysql) { throw "未找到 mysql.exe（用于导入 SQL）。" }
    $mysqlArgs = @('-h', $HostName, '-P', "$Port", '-u', $User)
    if ($Password) { $mysqlArgs += "-p$Password" }

    & $mysql @mysqlArgs -e "CREATE DATABASE IF NOT EXISTS $DbName DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    if ($LASTEXITCODE -ne 0) { throw "创建数据库 $DbName 失败" }

    $exists = & $mysql @mysqlArgs -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DbName' AND table_name='sys_system';"
    if ($LASTEXITCODE -ne 0) { throw "检查表结构失败" }
    if ([int]$exists -gt 0) {
        Write-Host "    检测到 $DbName 已有表结构，跳过 SQL 导入"
        return
    }

    $sqlDir = Join-Path $Root 'telbank-rbac-api\src\main\resources\db\manual'
    $files = Get-ChildItem -Path $sqlDir -Filter '*.sql' | Sort-Object Name
    if (-not $files) { throw "未找到 SQL 文件：$sqlDir" }
    foreach ($file in $files) {
        Write-Host "    导入 $($file.Name) ..."
        # 用 cmd 重定向而不是 PowerShell 管道，避免 Windows 默认编码损坏 SQL 中的中文
        $mysqlCmd = '"' + $mysql + '" -h ' + $HostName + ' -P ' + $Port + ' -u ' + $User
        if ($Password) { $mysqlCmd += ' -p' + $Password }
        $mysqlCmd += ' ' + $DbName + ' < "' + $file.FullName + '"'
        & cmd.exe /c $mysqlCmd
        if ($LASTEXITCODE -ne 0) { throw "SQL 导入失败：$($file.Name)" }
    }
    Write-Host "    数据库 $DbName 初始化完成（表结构 + 示例数据）"
}

function Start-TempMysql {
    $mysqld = Find-Mysqld
    if (-not $mysqld) {
        throw "未找到 mysqld.exe。请安装 MySQL 8（并把 bin 加入 PATH），或改为连接已有 MySQL（不加 --temp-db）。"
    }
    $mysql = Find-MysqlClient
    if (-not $mysql) { throw "未找到 mysql.exe（用于建库/导入 SQL）。" }

    if (Test-PortInUse -Port $TempMysqlPort) {
        throw "临时 MySQL 端口 $TempMysqlPort 已被占用，请先停止占用进程或改用 -TempMysqlPort 其他端口。"
    }

    $dataDir = Join-Path $TempDir 'data'
    New-Item -ItemType Directory -Force -Path $dataDir | Out-Null

    $initialized = Test-Path (Join-Path $dataDir 'mysql')
    if (-not $initialized) {
        Write-Host "    初始化临时 MySQL 数据目录 ..."
        & $mysqld --initialize-insecure "--datadir=$dataDir" 2>&1 | Out-File -FilePath $TempMysqlLog -Encoding utf8 -Append
        if ($LASTEXITCODE -ne 0) { throw "临时 MySQL 初始化失败，日志：logs\temp-mysql.log" }
    }

    Write-Host "==> 启动临时 MySQL（端口 ${TempMysqlPort}）"
    $dbOut = Join-Path $LogDir 'temp-mysql.out.log'
    $dbErr = Join-Path $LogDir 'temp-mysql.err.log'
    $script:DbProc = Start-Process -FilePath $mysqld `
        -ArgumentList @("--datadir=$dataDir", "--port=$TempMysqlPort", "--bind-address=127.0.0.1", "--mysqlx=0", "--console") `
        -RedirectStandardOutput $dbOut -RedirectStandardError $dbErr -PassThru -WindowStyle Hidden
    $script:TempDbStarted = $true

    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        if ($script:DbProc.HasExited) {
            Write-Host "❌ 临时 MySQL 退出，错误日志：" -ForegroundColor Red
            if (Test-Path $dbErr) { Get-Content $dbErr -Tail 30 }
            throw "临时 MySQL 启动失败"
        }
        if (Test-PortInUse -Port $TempMysqlPort) { break }
        Start-Sleep -Milliseconds 500
    }
    if (-not (Test-PortInUse -Port $TempMysqlPort)) {
        throw "临时 MySQL 60 秒内未监听端口 $TempMysqlPort"
    }

    & $mysql -h 127.0.0.1 -P $TempMysqlPort -u root --skip-password `
        -e "CREATE DATABASE IF NOT EXISTS telbank_rbac DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" 2>&1 |
        Out-File -FilePath $TempMysqlLog -Encoding utf8 -Append
    if ($LASTEXITCODE -ne 0) { throw "创建 telbank_rbac 库失败，日志：logs\temp-mysql.log" }

    $env:RBAC_DB_URL = "jdbc:mysql://127.0.0.1:${TempMysqlPort}/telbank_rbac?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
    Write-Host "    临时库已创建：telbank_rbac @ 127.0.0.1:${TempMysqlPort}，即将导入表结构/示例数据"
}

# ---------------- 主流程 ----------------
try {
    if ($Help) { Show-Usage; exit 0 }

    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

    if ($TempDb -and $env:RBAC_DB_URL) {
        throw "-TempDb 与 RBAC_DB_URL 冲突：临时库由脚本自动配置，请二选一。"
    }
    if ($TempDb -and $InitDb) {
        Write-Host "提示：-TempDb 会自动创建 telbank_rbac 库，-InitDb 将被忽略。"
        $InitDb = $false
    }

    if (Test-PortInUse -Port $ApiPort) {
        throw "端口 $ApiPort 已被占用（可能是已启动的后端）。可用 -ApiPort 其他端口重试。"
    }
    if (Test-PortInUse -Port $FrontPort) {
        throw "端口 $FrontPort 已被占用（可能是已启动的前端）。可用 -FrontPort 其他端口重试。"
    }

    $javaExe = Test-Java21
    Assert-Command -Name 'mvn' -Hint '请安装 Maven 并加入 PATH。' | Out-Null
    $npmCmd = Assert-Command -Name 'npm' -Hint '请安装 Node 22+（或 nvm-windows 后执行 nvm use 22）并加入 PATH。'

    $frontDir = Join-Path $Root 'telbank-rbac-front'
    if (-not (Test-Path $frontDir)) { throw "缺少前端目录：$frontDir" }
    if (-not (Test-Path (Join-Path $frontDir 'node_modules'))) {
        Write-Host "==> 安装前端依赖（首次运行）"
        Push-Location $frontDir
        try { & npm install --no-audit --no-fund } finally { Pop-Location }
    }

    if ($TempDb) {
        Start-TempMysql
        Invoke-InitSchema -Port $TempMysqlPort
    }
    elseif ($InitDb) {
        Write-Host "==> 初始化数据库（建库 + 导入表结构/示例数据）"
        $dbHost = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { '127.0.0.1' }
        $dbPort = if ($env:MYSQL_PORT) { [int]$env:MYSQL_PORT } else { 3306 }
        $dbUser = if ($env:MYSQL_USER) { $env:MYSQL_USER } else { 'root' }
        $dbPass = if ($env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD } else { '' }
        $dbName = if ($env:RBAC_DB_NAME) { $env:RBAC_DB_NAME } else { 'telbank_rbac' }
        Invoke-InitSchema -HostName $dbHost -Port $dbPort -User $dbUser -Password $dbPass -DbName $dbName
    }

    # 非临时库模式：把 MYSQL_* 映射给后端（否则只设 MYSQL_PASSWORD 会导致 Access denied）
    if (-not $TempDb) {
        if (-not $env:RBAC_DB_USER -and $env:MYSQL_USER) { $env:RBAC_DB_USER = $env:MYSQL_USER }
        if (-not $env:RBAC_DB_PASSWORD -and $env:MYSQL_PASSWORD) { $env:RBAC_DB_PASSWORD = $env:MYSQL_PASSWORD }
        if (-not $env:RBAC_DB_URL -and ($env:MYSQL_HOST -or $env:MYSQL_PORT -or $env:RBAC_DB_NAME)) {
            $dbHost = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { '127.0.0.1' }
            $dbPort = if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { '3306' }
            $dbName = if ($env:RBAC_DB_NAME) { $env:RBAC_DB_NAME } else { 'telbank_rbac' }
            $env:RBAC_DB_URL = "jdbc:mysql://${dbHost}:${dbPort}/${dbName}?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
        }
        if ($env:RBAC_DB_URL) {
            Write-Host "    数据源：$($env:RBAC_DB_URL)"
        } else {
            Write-Host "    数据源：默认 jdbc:mysql://localhost:3306/telbank_rbac"
        }
    }

    # 后端：通过临时 cmd 启动，保证重定向与工作目录可靠
    Write-Host "==> 启动权限中心后端（端口 ${ApiPort}，日志 logs\api.log）"
    $apiRunner = Join-Path $LogDir 'run-api.cmd'
    @(
        '@echo off',
        "cd /d `"$Root`"",
        "mvn -pl telbank-rbac-api -am spring-boot:run -Dspring-boot.run.profiles=demo -Dspring-boot.run.arguments=--server.port=$ApiPort > `"$ApiLog`" 2>&1"
    ) | Set-Content -Path $apiRunner -Encoding ASCII
    $script:ApiProc = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', ('"{0}"' -f $apiRunner)) -PassThru -WindowStyle Hidden
    $script:Running = $true
    Wait-HttpStatus -Url "http://127.0.0.1:$ApiPort/api/me" -TimeoutSeconds 180 -Expected @(401, 200) -Name "后端" -Process $script:ApiProc -LogPath $ApiLog

    # 前端
    Write-Host "==> 启动管理前端（端口 ${FrontPort}，日志 logs\front.log）"
    if (-not $env:VITE_API_BASE_URL) {
        $env:VITE_API_BASE_URL = "http://localhost:$ApiPort"
    }
    $frontRunner = Join-Path $LogDir 'run-front.cmd'
    @(
        '@echo off',
        "cd /d `"$frontDir`"",
        "set VITE_API_BASE_URL=$($env:VITE_API_BASE_URL)",
        "npm run dev -- --port $FrontPort > `"$FrontLog`" 2>&1"
    ) | Set-Content -Path $frontRunner -Encoding ASCII
    $script:FrontProc = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', ('"{0}"' -f $frontRunner)) -PassThru -WindowStyle Hidden
    Wait-HttpStatus -Url "http://127.0.0.1:$FrontPort/" -TimeoutSeconds 120 -Expected @(200) -Name "前端" -Process $script:FrontProc -LogPath $FrontLog

    Write-Host @"

============================================================
 权限中心后端 : http://localhost:$ApiPort     （日志 logs\api.log）
 管理前端     : http://localhost:$FrontPort   （日志 logs\front.log）
$(if ($TempDb) { " 临时 MySQL   : 127.0.0.1:$TempMysqlPort（telbank_rbac，退出时自动清理）`n" }) 演示 JWT     : 运行 scripts\gen-demo-jwt.sh 13800138000（需 bash）或用现有 JWT
 登录账号     : 13800138000（CRM/ERP 管理员 + 权限中心管理员 rbac-admin）
 停止服务     : 按 Ctrl+C（或关闭本窗口）
============================================================

"@
    Write-Host "服务运行中，按 Ctrl+C 停止 ..."
    while ($true) { Start-Sleep -Seconds 1 }
}
catch {
    Write-Host ""
    Write-Host "❌ $($_.Exception.Message)" -ForegroundColor Red
    if (Test-Path $ApiLog) { Write-Host "后端日志尾部（logs\api.log）："; Get-Content $ApiLog -Tail 30 }
    exit 1
}
finally {
    Stop-Everything
}
