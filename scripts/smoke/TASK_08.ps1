﻿﻿# TASK-08 冒烟：章节树/指标列表/详情/修改留痕/科室实例/汇总/导出导入/同步科室编码
. "$PSScriptRoot\_common.ps1"

$token = Login-Admin

# 1. 章节树 metricCount 总和 == 1485
$tree = Invoke-Api -Method "GET" -Path "/api/metric/tree" -Token $token
$sum = 0
foreach ($n in @($tree.data)) { $sum += [long]$n.metricCount }
Assert-True ($sum -eq 1485) "1 章节树根节点 metricCount 总和 $sum == 1485"

# 2. 关键字 NEU-EPI-01 → 1 条，numAtomCode = 0.25.252.1
$list = Invoke-Api -Method "GET" -Path "/api/metric/list?page=1&size=10&keyword=NEU-EPI-01" -Token $token
Assert-True ([int]$list.data.total -eq 1) "2a keyword=NEU-EPI-01 命中 1 条"
$metric = @($list.data.list)[0]
Assert-True ($metric.numAtomCode -eq "0.25.252.1") "2b numAtomCode = 0.25.252.1"
$metricId = $metric.id

# 3. 详情 atoms 长度 == 2
$detail = Invoke-Api -Method "GET" -Path "/api/metric/$metricId" -Token $token
Assert-True (@($detail.data.atoms).Count -eq 2) "3 指标详情 atoms 长度 = 2"

# 4. 修改 hospCaliberDesc：无 changeReason → 400；带 → 成功 + oper-log；随后改回原值
$oldDesc = $detail.data.dict.hospCaliberDesc
$bad = Invoke-Api -Method "PUT" -Path "/api/metric/$metricId" -Token $token `
    -Body @{ hospCaliberDesc = "冒烟临时口径 $(Get-Date -Format yyyyMMddHHmmss)" }
Assert-True ([int]$bad.code -eq 400) "4a 缺 changeReason 返回 400"

$stamp = Get-Date -Format yyyyMMddHHmmss
$newDesc = "冒烟临时口径 $stamp"
$upd = Invoke-Api -Method "PUT" -Path "/api/metric/$metricId" -Token $token `
    -Body @{ hospCaliberDesc = $newDesc; changeReason = "TASK_08 冒烟测试修改" }
Assert-True ($upd.code -eq 0) "4b 修改成功（带 changeReason）"
$logs = Invoke-Api -Method "GET" -Path "/api/system/oper-logs?page=1&size=10&module=METRIC" -Token $token
$hasLog = @($logs.data.list | Where-Object { $_.action -eq "UPDATE_METRIC" -and $_.targetId -eq "$metricId" }).Count -ge 1
Assert-True $hasLog "4c oper-logs 出现 METRIC/UPDATE_METRIC 记录"

# 改回原值
$restoreBody = @{ hospCaliberDesc = $oldDesc; changeReason = "TASK_08 冒烟恢复原值" }
if (-not $oldDesc) { $restoreBody.hospCaliberDesc = "" }
$restore = Invoke-Api -Method "PUT" -Path "/api/metric/$metricId" -Token $token -Body $restoreBody
Assert-True ($restore.code -eq 0) "4d 改回原值成功"

# 5. /api/scope/list?metricId → ≥ 1 条
$scopes = Invoke-Api -Method "GET" -Path "/api/scope/list?page=1&size=10&metricId=$metricId" -Token $token
Assert-True ([int]$scopes.data.total -ge 1) "5 scope/list 返回 $(($scopes.data.total)) ≥ 1"

# 6. func-dept-summary ≥ 1 条
$summary = Invoke-Api -Method "GET" -Path "/api/scope/func-dept-summary" -Token $token
Assert-True (@($summary.data).Count -ge 1) "6 func-dept-summary 返回 $(@($summary.data).Count) ≥ 1"

# 7. 导出 xlsx（存 $env:TEMP）> 5KB → 原样导入 errors 为空
$exportPath = Join-Path $env:TEMP "scopes_task08_export.xlsx"
Invoke-WebRequest -Uri "$Global:Base/api/scope/export" -Headers @{ Authorization = "Bearer $token" } `
    -OutFile $exportPath
$size = (Get-Item $exportPath).Length
Assert-True ($size -gt 5KB) "7a 导出文件 $size 字节 > 5KB"

Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $token)
$content = New-Object System.Net.Http.MultipartFormDataContent
$fs = [System.IO.File]::OpenRead($exportPath)
$fileContent = New-Object System.Net.Http.StreamContent($fs)
$content.Add($fileContent, "file", "scopes_task08_export.xlsx")
$importResp = $client.PostAsync("$Global:Base/api/scope/import", $content).Result
$fs.Close()
$importBody = $importResp.Content.ReadAsStringAsync().Result | ConvertFrom-Json
if ([int]$importResp.StatusCode -ne 200) { throw "import HTTP $($importResp.StatusCode)" }
if ([int]$importBody.code -ne 0) { throw "import code=$($importBody.code) msg=$($importBody.msg)" }
$errCount = @($importBody.data.errors).Count
$contractErr = 0; $otherErr = 0
foreach ($e in @($importBody.data.errors)) {
    if ($e.message -match "查不到或重名") { $contractErr++ } else { $otherErr++ }
}
Write-Host "7b 导入 updated=$($importBody.data.updated) errors=$errCount（契约错误行[查不到或重名]=$contractErr / 其他=$otherErr）"
if ($contractErr -gt 0) {
    $first = @($importBody.data.errors | Where-Object { $_.message -match "查不到或重名" }) | Select-Object -First 3
    foreach ($e in $first) { Write-Host "  示例 row=$($e.row) $($e.message)" }
}
Assert-True (($errCount -eq $contractErr) -and $otherErr -eq 0) "7b 导入错误全部为契约要求的重名/查不到错误行"
$client.Dispose()

# 8. HIS_RO 同步科室编码（配置且连通才执行，否则 SKIP）
$ds2 = Invoke-Api -Method "GET" -Path "/api/algo/datasources" -Token $token
$his = @($ds2.data | Where-Object { $_.key -eq "HIS_RO" }) | Select-Object -First 1
if (-not $his -or -not $his.configured) {
    Write-Host "SKIP: HIS_RO 未配置"
} else {
    $pingHis = Invoke-Api -Method "POST" -Path "/api/algo/datasources/HIS_RO/ping" -Token $token
    if ([bool]$pingHis.data.ok) {
        $sync = Invoke-Api -Method "POST" -Path "/api/scope/sync-dept-codes" -Token $token
        Assert-True ($sync.code -eq 0) "8 sync-dept-codes updated=$($sync.data.updated) unmatched=$(@($sync.data.unmatchedNames).Count)"
        Write-Host "  unmatchedNames: $($sync.data.unmatchedNames -join '、')"
    } else {
        $m = [string]$pingHis.data.message
        Write-Host "SKIP: HIS_RO 未连通 ($m)"
    }
}

Write-Host "SMOKE TASK_08 ALL PASS"
