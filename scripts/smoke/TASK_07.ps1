# TASK-07 冒烟：跑批接口防误跑 / Oracle 连通前置 / 3 实例跑批对照（SKIP 规则见任务书步骤 8）
. "$PSScriptRoot\_common.ps1"

$token = Login-Admin

# 1. 不带 metricIds/scopeIds 调 /api/batch/run → code == 400
$empty = Invoke-Api -Method "POST" -Path "/api/batch/run" -Token $token -Body @{ years = @(2025) }
Assert-True ([int]$empty.code -eq 400) "1 空 metricIds/scopeIds 被拒绝 code=400"

# 2. Oracle 未配置或 ping 不通（非 ORA-01017）→ SKIP 结束
$ds = Invoke-Api -Method "GET" -Path "/api/algo/datasources" -Token $token
$ods = @($ds.data | Where-Object { $_.key -eq "ODS_RO" }) | Select-Object -First 1
if (-not $ods -or -not $ods.configured) {
    Write-Host "SKIP: Oracle 账号未配置（deploy/.env ODS_RO_* 为占位符）"
    Write-Host "SMOKE TASK_07 DONE"
    exit 0
}
$ping = Invoke-Api -Method "POST" -Path "/api/algo/datasources/ODS_RO/ping" -Token $token
if ([bool]$ping.data.ok -eq $false) {
    $m = [string]$ping.data.message
    if ($m -match "ORA-01017") {
        Write-Host "FAIL: ODS_RO 账号密码错误 ($m)"
        exit 1
    }
    Write-Host "SKIP: Oracle 未连通 ($m)，真实跑批部分跳过"
    Write-Host "SMOKE TASK_07 DONE"
    exit 0
}
Write-Host "PASS: 2 ODS_RO 连通 ($($ping.data.elapsedMs)ms)"

# 3. 从 PostgreSQL 选 3 个测试实例（A 呼吸 SUM / B 康复 DTL / C 麻醉任意）
function Select-ScopeId {
    param([string]$like)
    $r = docker exec eval-postgres sh -c ('psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -c ' + "`"SELECT s.id FROM eval_metric_dept_scope s JOIN eval_metric_dict d ON d.id = s.metric_id WHERE d.metric_name LIKE '%$like%' AND s.id IN (SELECT DISTINCT scope_id FROM eval_metric_sql WHERE is_active = true) ORDER BY s.id LIMIT 1`"")
    if ($LASTEXITCODE -ne 0) { return $null }
    $v = "$r".Trim()
    if ($v -match '^\d+$') { return [long]$v }
    return $null
}
$scopes = @()
foreach ($like in @("%呼吸内科%", "%康复医学%", "%麻醉%")) {
    $sid = Select-ScopeId -like $like
    if ($sid) { $scopes += $sid }
}
Assert-True (@($scopes).Count -ge 3) "3 选出 3 个测试实例 $($scopes -join ',')"

# 4. 发起跑批 years=[2025]
$run = Invoke-Api -Method "POST" -Path "/api/batch/run" -Token $token -Body @{
    scopeIds = $scopes; years = @(2025); versionTag = "TASK_07 冒烟"
}
Assert-True ($run.code -eq 0) "4a 跑批发起 batchId=$($run.data.batchId)"
$batchId = $run.data.batchId

# 轮询最长 30 分钟
$deadline = (Get-Date).AddMinutes(30)
$status = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 10
    $status = Invoke-Api -Method "GET" -Path "/api/batch/$batchId" -Token $token
    $st = $status.data.batch.execStatus
    Write-Host "  轮询 batch=$batchId status=$st"
    if ($st -ne "RUNNING") { break }
}
Assert-True ($null -ne $status -and $status.data.batch.execStatus -ne "RUNNING") "4b 批次结束（$($status.data.batch.execStatus)）"

# 5. 打印每个子任务状态/行数/耗时/错误
$tasks = Invoke-Api -Method "GET" -Path "/api/batch/$batchId/tasks?page=1&size=50" -Token $token
foreach ($t in @($tasks.data.list)) {
    Write-Host ("任务 {0} [{1}] {2} rows={3} elapsed={4}ms err={5}" -f `
        $t.id, $t.status, $t.metricCode, $t.rowCount, $t.elapsedMs, $t.errorMsg)
}

# 6. 成功实例：2025 科室行与全院行 vs 存量基线并排打印（不要求一致）
foreach ($sid in $scopes) {
    $q = docker exec eval-postgres sh -c ('psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -F "|" -c ' + "`"SELECT r.numerator_val, r.denominator_val, r.result_val, r.batch_no, b.id FROM eval_metric_result r JOIN eval_exec_batch b ON b.id = r.batch_id WHERE r.scope_id = $sid AND r.stat_period = '2025' AND r.is_current = true`"")
    $new = @($q | Where-Object { $_ -and ($_ -split "\|")[3] -ne "BATCH_BASELINE_IMPORT" }) | Select-Object -First 1
    $base = @($q | Where-Object { $_ -and ($_ -split "\|")[3] -eq "BATCH_BASELINE_IMPORT" }) | Select-Object -First 1
    Write-Host "对照 scope=$sid 新跑批=[$new] 存量基线=[$base]"
}

Write-Host "SMOKE TASK_07 DONE"
