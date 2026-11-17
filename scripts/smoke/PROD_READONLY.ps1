# TASK-19 生产只读冒烟（仅 GET + 登录；不调用任何写数据接口）
. "$PSScriptRoot\_common.ps1"

$base = $env:EVAL_SMOKE_BASE
if (-not $base) { Write-Host "请先设置 `$env:EVAL_SMOKE_BASE（如 http://10.10.8.75:18080）"; exit 1 }
$Global:Base = $base

$token = Login-Admin
Assert-True ($null -ne $token -and $token.Length -gt 10) "1 admin 登录成功（生产迁移库）"

$h = Invoke-Api -Method "GET" -Path "/api/health" -Token $token
Assert-True ($h.status -eq "UP" -and $h.db -eq "UP") "2 /api/health UP db UP"

$tree = Invoke-Api -Method "GET" -Path "/api/metric/tree" -Token $token
Assert-True ($tree.code -eq 0 -and @($tree.data).Count -ge 1) "3 /api/metric/tree 正常（根章节 $(@($tree.data).Count) 个）"

$s = Invoke-Api -Method "GET" -Path "/api/result/summary?page=1&size=1&years=2022,2023,2024,2025" -Token $token
Assert-True ($s.code -eq 0 -and $s.data.total -eq 1485) "4 /api/result/summary total=$($s.data.total) == 1485"

$b = Invoke-Api -Method "GET" -Path "/api/result/batches?page=1&size=200" -Token $token
$blist = if ($null -ne $b.data -and $null -ne $b.data.list) { @($b.data.list) } else { @($b.data) }
$hasBase = @($blist | Where-Object { $_.batchNo -eq "BATCH_BASELINE_IMPORT" }).Count -ge 1
Assert-True ($b.code -eq 0 -and $hasBase) "5 /api/result/batches 含 BATCH_BASELINE_IMPORT"

$ds = Invoke-Api -Method "GET" -Path "/api/algo/datasources" -Token $token
Assert-True ($ds.code -eq 0 -and @($ds.data).Count -eq 2) "6 /api/algo/datasources 返回 $(@($ds.data).Count) 条 == 2"

Write-Host "PROD READONLY ALL PASS"
