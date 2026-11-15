# TASK-09 冒烟：算法管理（SQL 查询/新增/修改留痕/历史对比/启停）
. "$PSScriptRoot\_common.ps1"

$token = Login-Admin

# 1. 取 NEU-EPI-01 指标的任一科室实例，GET /api/algo/sqls 返回 ≥ 1 条
$list = Invoke-Api -Method "GET" -Path "/api/metric/list?page=1&size=1&keyword=NEU-EPI-01" -Token $token
$metricId = @($list.data.list)[0].id
$scopes = Invoke-Api -Method "GET" -Path "/api/scope/list?page=1&size=1&metricId=$metricId" -Token $token
$firstScope = @($scopes.data.list)[0]
$targetScopeId = $firstScope.id
$allSqls = Invoke-Api -Method "GET" -Path "/api/algo/sqls?metricId=$metricId" -Token $token
Assert-True (@($allSqls.data).Count -ge 1) "1 NEU-EPI-01 指标共 $(@($allSqls.data).Count) 条 SQL ≥ 1"

# 2. 新增测试 SQL（NUM_SUM / 冒烟测试）→ 成功（允许模式建议 warning）
$createBody = @{
    metricId      = $metricId
    scopeId       = $targetScopeId
    sqlRole       = "NUM_SUM"
    datasourceKey = "ODS_RO"
    periodStart   = "202501"
    periodEnd     = "202512"
    sqlAnnotated  = "SELECT 2025 AS 年份, 1 AS 分子 FROM DUAL"
    changeReason  = "冒烟测试"
}
$created = Invoke-Api -Method "POST" -Path "/api/algo/sqls" -Token $token -Body $createBody
Assert-True ($created.code -eq 0 -and $created.data.id) "2 新增测试 SQL 成功 id=$($created.data.id)"
$sqlId = $created.data.id

# 3. 新增 DELETE FROM T → 400
$bad = Invoke-Api -Method "POST" -Path "/api/algo/sqls" -Token $token -Body @{
    metricId = $metricId; scopeId = $targetScopeId; sqlRole = "NUM_SUM"; datasourceKey = "ODS_RO"
    periodStart = "202501"; periodEnd = "202512"; sqlAnnotated = "DELETE FROM T"; changeReason = "冒烟-应被拦截"
}
Assert-True ([int]$bad.code -eq 400) "3 DELETE 被沙箱拦截 code=400"

# 4. 修改测试 SQL → versionNo == 2
$upd = Invoke-Api -Method "PUT" -Path "/api/algo/sqls/$sqlId" -Token $token -Body @{
    periodStart = "202501"; periodEnd = "202512"
    sqlAnnotated = "SELECT 2025 AS 年份, 2 AS 分子 FROM DUAL"
    changeType = "算法Bug修复"; changeReason = "冒烟测试-分子改为 2"
}
Assert-True ($upd.code -eq 0 -and [int]$upd.data.versionNo -eq 2) "4 修改成功 versionNo=$($upd.data.versionNo)==2"

# 5. history 2 条；最新 his 中 old 含 1 AS 分子、new 含 2 AS 分子
$history = Invoke-Api -Method "GET" -Path "/api/algo/sqls/$sqlId/history" -Token $token
Assert-True (@($history.data).Count -eq 2) "5a history 共 $(@($history.data).Count) 条 == 2"
$latestHisId = @($history.data | Sort-Object { [int]$_.versionNo } -Descending | Select-Object -First 1)[0].hisId
$hisDetail = Invoke-Api -Method "GET" -Path "/api/algo/sql-his/$latestHisId" -Token $token
Assert-True ($hisDetail.data.oldSqlAnnotated -match "1 AS 分子") "5b oldSqlAnnotated 含 1 AS 分子"
Assert-True ($hisDetail.data.newSqlAnnotated -match "2 AS 分子") "5c newSqlAnnotated 含 2 AS 分子"

# 6. 原样再提交一次修改 → 400 未检测到修改
$again = Invoke-Api -Method "PUT" -Path "/api/algo/sqls/$sqlId" -Token $token -Body @{
    periodStart = "202501"; periodEnd = "202512"
    sqlAnnotated = "SELECT 2025 AS 年份, 2 AS 分子 FROM DUAL"
    changeType = "算法Bug修复"; changeReason = "冒烟测试-原样重复提交"
}
Assert-True ([int]$again.code -eq 400 -and $again.msg -match "未检测到修改") "6 原样重复提交返回 400 未检测到修改"

# 7. 停用该测试 SQL（防止被跑批引擎执行）
$toggle = Invoke-Api -Method "POST" -Path "/api/algo/sqls/$sqlId/toggle" -Token $token `
    -Body @{ active = $false; changeReason = "冒烟测试结束停用" }
Assert-True ($toggle.code -eq 0) "7 停用测试 SQL 成功"

Write-Host "SMOKE TASK_09 ALL PASS"
