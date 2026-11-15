# TASK-06 冒烟：SQL 沙箱 / 数据源列表 / Oracle 连通与试跑（SKIP 规则见任务书步骤 6）
. "$PSScriptRoot\_common.ps1"

$script:Skipped = 0
$token = Login-Admin

# 1. /api/algo/datasources 返回 2 条，且不含 password / username 字段
$ds = Invoke-Api -Method "GET" -Path "/api/algo/datasources" -Token $token
Assert-True (@($ds.data).Count -eq 2) "1 数据源列表返回 2 条"
$fieldNames = @(@($ds.data)[0].PSObject.Properties.Name)
Assert-True (-not ($fieldNames -contains "password") -and -not ($fieldNames -contains "username")) "1b 返回不含 password/username 字段"

# 2. validate：DELETE → false；SELECT → true
$vBad = Invoke-Api -Method "POST" -Path "/api/algo/sql/validate" -Token $token -Body @{ sqlText = "DELETE FROM t" }
Assert-True ($vBad.data.valid -eq $false) "2a DELETE 被拦截 valid=false"
$vGood = Invoke-Api -Method "POST" -Path "/api/algo/sql/validate" -Token $token -Body @{ sqlText = "SELECT 1 FROM dual" }
Assert-True ($vGood.data.valid -eq $true) "2b SELECT 放行 valid=true"

function Test-OracleSource {
    param([string]$Key, [string]$PrimarySql, [string]$FallbackSql, [string]$Label)
    $info = @($ds.data | Where-Object { $_.key -eq $Key }) | Select-Object -First 1
    if (-not $info -or -not $info.configured) {
        Write-Host "SKIP: $Key Oracle 账号未配置"
        $script:Skipped++
        return
    }
    $ping = Invoke-Api -Method "POST" -Path "/api/algo/datasources/$Key/ping" -Token $token
    if ([bool]$ping.data.ok) {
        Write-Host "PASS: $Key ping 连通 ($($ping.data.elapsedMs)ms)"
        $trial = $null
        $trialErr = ""
        try {
            $trial = Invoke-Api -Method "POST" -Path "/api/algo/sql/trial" -Token $token `
                -Body @{ sqlText = $PrimarySql; datasourceKey = $Key }
        } catch {
            $trialErr = $_.Exception.Message
            try {
                $trial = Invoke-Api -Method "POST" -Path "/api/algo/sql/trial" -Token $token `
                    -Body @{ sqlText = $FallbackSql; datasourceKey = $Key }
                $trialErr = ""
            } catch {
                $trialErr = $_.Exception.Message
            }
        }
        if ($null -ne $trial -and $trial.code -eq 0) {
            Assert-True ([int]$trial.data.rowCount -ge 1) "$Label trial 成功 rowCount=$($trial.data.rowCount) elapsed=$($trial.data.elapsedMs)ms"
        } else {
            Write-Host "FAIL: $Label trial 失败 ($trialErr)"
            exit 1
        }
        return
    }
    $m = [string]$ping.data.message
    if ($m -match "SQLRecoverableException" -or $m -match "vendorCode=17002" -or $m -match "ORA-12537" -or $m -match "SQLTimeoutException") {
        Write-Host "SKIP: $Label 本机网络或Oracle监听白名单未放行 (<$m>)"
        $script:Skipped++
        return
    }
    if ($m -match "ORA-01017") {
        Write-Host "FAIL: $Label Oracle 账号密码错误 ($m)"
        exit 1
    }
    Write-Host "FAIL: $Label ping 失败 ($m)"
    exit 1
}

# 3. ODS_RO
Test-OracleSource -Key "ODS_RO" `
    -PrimarySql "SELECT COUNT(*) AS CNT FROM HIS.PAT_VISIT WHERE ROWNUM <= 1" `
    -FallbackSql "SELECT 1 AS X FROM DUAL" `
    -Label "ODS_RO"

# 4. HIS_RO
Test-OracleSource -Key "HIS_RO" `
    -PrimarySql "SELECT COUNT(*) AS CNT FROM COMM.DEPT_DICT WHERE ROWNUM <= 1" `
    -FallbackSql "SELECT 1 AS X FROM DUAL" `
    -Label "HIS_RO"

# 5. 仅当 ODS_RO ping.ok：取 1 条存量 SUM SQL 试跑（成功或明确 ORA 错误都不算 FAIL）
$odsInfo = @($ds.data | Where-Object { $_.key -eq "ODS_RO" }) | Select-Object -First 1
if ($odsInfo -and $odsInfo.configured) {
    $ping = Invoke-Api -Method "POST" -Path "/api/algo/datasources/ODS_RO/ping" -Token $token
    if ([bool]$ping.data.ok) {
        $sqlText = docker exec eval-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -c "SELECT sql_executable FROM eval_metric_sql WHERE datasource_key=''ODS_RO'' AND sql_role LIKE ''%_SUM'' ORDER BY id LIMIT 1"'
        if ($sqlText -and $sqlText.Trim()) {
            $preview = $sqlText.Trim() -replace "\s+", " "
            if ($preview.Length -gt 120) { $preview = $preview.Substring(0, 120) + "..." }
            try {
                $trial = Invoke-Api -Method "POST" -Path "/api/algo/sql/trial" -Token $token `
                    -Body @{ sqlText = $sqlText.Trim(); datasourceKey = "ODS_RO" }
                if ($trial.code -eq 0) {
                    Write-Host "PASS: 5 存量SUM试跑成功 rowCount=$($trial.data.rowCount) elapsed=$($trial.data.elapsedMs)ms sql=[$preview]"
                } else {
                    Write-Host "INFO: 5 存量SUM试跑返回 code=$($trial.code) msg=$($trial.msg) sql=[$preview]"
                }
            } catch {
                Write-Host "INFO: 5 存量SUM试跑返回错误（不算 FAIL）: $($_.Exception.Message) sql=[$preview]"
            }
        } else {
            Write-Host "SKIP: 5 库中无 ODS_RO 的 _SUM 存量 SQL"
            $script:Skipped++
        }
    } else {
        Write-Host "SKIP: 5 ODS_RO ping 未连通"
        $script:Skipped++
    }
} else {
    Write-Host "SKIP: 5 ODS_RO 未配置"
    $script:Skipped++
}

if ($script:Skipped -gt 0) {
    Write-Host "SKIPPED: $($script:Skipped)"
}
Write-Host "SMOKE TASK_06 DONE"
