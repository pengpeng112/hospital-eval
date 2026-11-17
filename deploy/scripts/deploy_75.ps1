# TASK-19 发布脚本：10.10.8.75（/opt/hospital-eval/，仅 eval- 容器/项目）
# 说明：服务器无外网 → 镜像离线传输（docker save + scp + load）；
#       docker 18.09 不支持 CLI 插件 → 使用独立二进制 docker-compose（v2.24.6）
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$ROOT = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $ROOT

function Step([int]$n, [string]$desc, [scriptblock]$block) {
    Write-Host "== STEP $n $desc =="
    & $block
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) { Write-Host "STEP $n FAILED"; exit 1 }
    Write-Host "STEP $n OK"
}

if (-not (Test-Path "$ROOT\deploy\.env.prod")) { Write-Host "missing deploy\.env.prod"; exit 1 }
if (-not (Test-Path "$ROOT\hospital-eval-server\Dockerfile")) { Write-Host "missing Dockerfile"; exit 1 }

Step 1 "create server dirs" {
    ssh -o BatchMode=yes hospital-eval-75 "mkdir -p /opt/hospital-eval/{data/postgres,data/uploads,sql_init,images,backup}"
}

Step 2 "upload compose and env(600)" {
    scp -o BatchMode=yes "$ROOT\deploy\docker-compose.prod.yml" hospital-eval-75:/opt/hospital-eval/docker-compose.yml
    scp -o BatchMode=yes "$ROOT\deploy\.env.prod" hospital-eval-75:/opt/hospital-eval/.env
    ssh -o BatchMode=yes hospital-eval-75 "chmod 600 /opt/hospital-eval/.env"
}

Step 3 "upload sql_init" {
    Get-ChildItem "$ROOT\docs\sql_init\*.sql" | ForEach-Object {
        scp -o BatchMode=yes $_.FullName "hospital-eval-75:/opt/hospital-eval/sql_init/"
        if ($LASTEXITCODE -ne 0) { Write-Host "scp failed: $($_.Name)"; exit 1 }
    }
}

Step 4 "offline image transfer (save -> scp -> load)" {
    $tmp = $env:TEMP
    foreach ($img in @("postgres:15", "keking/kkfileview:latest", "hospital-eval-server:1.0.0", "hospital-eval-web:1.0.0")) {
        $safe = $img -replace "[/:]", "_"
        $tar = Join-Path $tmp "$safe.tar"
        if (-not (Test-Path $tar)) {
            docker save -o $tar $img
            if ($LASTEXITCODE -ne 0) { Write-Host "save failed: $img"; exit 1 }
        }
        scp -o BatchMode=yes $tar "hospital-eval-75:/opt/hospital-eval/images/$safe.tar"
        ssh -o BatchMode=yes hospital-eval-75 "docker load -i /opt/hospital-eval/images/$safe.tar"
        Remove-Item $tar
    }
    ssh -o BatchMode=yes hospital-eval-75 "docker images --format '{{.Repository}}:{{.Tag}}' | sort"
}

Step 5 "docker-compose up -d" {
    ssh -o BatchMode=yes hospital-eval-75 "cd /opt/hospital-eval; docker-compose -p hospital-eval up -d"
}

Step 6 "wait 40s then ps" {
    Start-Sleep -Seconds 40
    ssh -o BatchMode=yes hospital-eval-75 "cd /opt/hospital-eval; docker-compose -p hospital-eval ps"
}

Write-Host "DEPLOY DONE"
