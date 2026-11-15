# TASK-05 冒烟公共函数（信息不打印任何密码）
$Global:Base = if ($env:EVAL_SMOKE_BASE) { $env:EVAL_SMOKE_BASE } else { "http://127.0.0.1:48080" }
$Global:EnvFile = Join-Path $PSScriptRoot "..\..\deploy\.env"

function Get-EnvValue {
    param([string]$name)
    if (-not (Test-Path $Global:EnvFile)) { throw "缺少 $Global:EnvFile" }
    $line = Select-String -Path $Global:EnvFile -Pattern ("^\s*" + [regex]::Escape($name) + "\s*=") | Select-Object -First 1
    if (-not $line) { throw "deploy/.env 缺少变量 $name" }
    $value = $line.Line.Split('=', 2)[1].Trim()
    if (-not $value) { throw "deploy/.env 变量 $name 为空" }
    return $value
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body,
        [string]$Token
    )
    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    $uri = "$Global:Base$Path"
    try {
        if ($null -ne $Body) {
            $json = $Body | ConvertTo-Json -Depth 6
            return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -ContentType "application/json; charset=utf-8" -Body $json
        }
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $status = [int]$resp.StatusCode
            try {
                $stream = $resp.GetResponseStream()
                $reader = New-Object System.IO.StreamReader($stream)
                $raw = $reader.ReadToEnd()
                $json = $raw | ConvertFrom-Json
                return @{ httpStatus = $status; code = $json.code; msg = $json.msg; data = $json.data }
            } catch {
                return @{ httpStatus = $status; code = -1; msg = "HTTP $status" }
            }
        }
        throw
    }
}

function Login-Admin {
    $pwd = Get-EnvValue "EVAL_INIT_ADMIN_PASSWORD"
    $resp = Invoke-Api -Method "POST" -Path "/api/auth/login" -Body @{ username = "admin"; password = $pwd }
    if ($resp.code -ne 0) { throw "admin 登录失败 code=$($resp.code) msg=$($resp.msg)" }
    return $resp.data.token
}

function Assert-True {
    param([bool]$condition, [string]$message)
    if ($condition) {
        Write-Host "PASS: $message"
    } else {
        Write-Host "FAIL: $message"
        exit 1
    }
}
