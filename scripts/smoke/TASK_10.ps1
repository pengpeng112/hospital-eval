# TASK-10 冒烟：汇总/科室分布/面板/批次/明细/病历调阅/总表回填导出
. "$PSScriptRoot\_common.ps1"

$script:Skipped = 0
$token = Login-Admin

# 1. summary total == 1485
$summary = Invoke-Api -Method "GET" -Path "/api/result/summary?page=1&size=10&years=2022,2023,2024,2025" -Token $token
Assert-True ([int]$summary.data.total -eq 1485) "1 summary total $($summary.data.total) == 1485"

# 2. keyword=NEU-EPI-01 → years["2025"] 存在（存量基线）
$list = Invoke-Api -Method "GET" -Path "/api/metric/list?page=1&size=1&keyword=NEU-EPI-01" -Token $token
$metricId = @($list.data.list)[0].id
$row = Invoke-Api -Method "GET" -Path "/api/result/summary?page=1&size=5&years=2022,2023,2024,2025&keyword=NEU-EPI-01" -Token $token
$first = @($row.data.list)[0]
Assert-True ($null -ne $first.years."2025") "2 NEU-EPI-01 有 2025 年结果（存量基线）"

# 3. scopes ≥ 1；panel trend ≥ 1、sqls ≥ 1
$scopeList = Invoke-Api -Method "GET" -Path "/api/result/metric/$metricId/scopes?years=2022,2023,2024,2025" -Token $token
$panel = Invoke-Api -Method "GET" -Path "/api/result/metric/$metricId/panel" -Token $token
Assert-True (@($scopeList.data.scopes).Count -ge 1) "3a scopes $(@($scopeList.data.scopes).Count) ≥ 1"
Assert-True (@($panel.data.trend).Count -ge 1) "3b panel trend $(@($panel.data.trend).Count) ≥ 1"
Assert-True (@($panel.data.sqls).Count -ge 1) "3c panel sqls $(@($panel.data.sqls).Count) ≥ 1"

# 4. batches 含 BATCH_BASELINE_IMPORT
$batches = Invoke-Api -Method "GET" -Path "/api/result/batches?page=1&size=50" -Token $token
$hasBase = @($batches.data.list | Where-Object { $_.batchNo -eq "BATCH_BASELINE_IMPORT" }).Count -ge 1
Assert-True $hasBase "4 batches 含 BATCH_BASELINE_IMPORT"

# 5~6. 取一个有明细的 (metricId, scopeId, 2025) 做明细与病历调阅（依赖 TASK-07 跑批产物）
$cnt = docker exec eval-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -c "SELECT metric_id || ''|'' || scope_id FROM eval_metric_detail WHERE stat_year = 2025 AND role_type = ''DEN'' AND batch_id = (SELECT id FROM eval_exec_batch WHERE batch_no = ''BATCH_BASELINE_IMPORT'') GROUP BY metric_id, scope_id LIMIT 1"'
if ($cnt -and "$cnt".Trim() -match "^\d+\|\d+$") {
    $mid = ("$cnt".Split("|"))[0]
    $sid = ("$cnt".Split("|"))[1]
    $detail = Invoke-Api -Method "GET" -Path "/api/result/detail?metricId=$mid&scopeId=$sid&year=2025&role=DEN&page=1&size=10" -Token $token
    Assert-True ([int]$detail.data.total -gt 0) "5 明细 total $($detail.data.total) > 0"
    Write-Host "  columns labels: $((@($detail.data.columns) | ForEach-Object { $_.label }) -join ' | ')"

    $ods = Invoke-Api -Method "GET" -Path "/api/algo/datasources" -Token $token
    $odsInfo = @($ods.data | Where-Object { $_.key -eq "ODS_RO" }) | Select-Object -First 1
    if ($odsInfo -and $odsInfo.configured) {
        $ping = Invoke-Api -Method "POST" -Path "/api/algo/datasources/ODS_RO/ping" -Token $token
        if ([bool]$ping.data.ok) {
            $firstRow = @($detail.data.list)[0]
            $docs = Invoke-Api -Method "GET" -Path "/api/emr/documents?patientId=$($firstRow.patientId)&visitId=$($firstRow.visitId)&inpNo=$($firstRow.inpNo)" -Token $token
            Write-Host "6 病历文书数量: $(@($docs.data.documents).Count) warnings: $(@($docs.data.warnings).Count)"
            $firstDoc = @($docs.data.documents) | Select-Object -First 1
            if ($firstDoc) {
                $content = Invoke-Api -Method "GET" -Path "/api/emr/document-content?sys=$($firstDoc.sys)&docId=$($firstDoc.docId)&patientId=$($firstRow.patientId)&visitId=$($firstRow.visitId)&inpNo=$($firstRow.inpNo)" -Token $token
                $len = 0
                if ($content.data.content) { $len = $content.data.content.Length }
                Write-Host "6 病历正文长度: $len"
            } else {
                Write-Host "  SKIP: 该患者无文书"
                $script:Skipped++
            }
        } else {
            Write-Host "SKIP: ODS_RO 未连通 ($($ping.data.message))"
            $script:Skipped++
        }
    } else {
        Write-Host "SKIP: ODS_RO 未配置"
        $script:Skipped++
    }
} else {
    Write-Host "SKIP: TASK-07 冒烟未产生跑批明细（依赖 Oracle）"
    $script:Skipped++
}

# 7. 总表回填导出 + Python 校验
$masterPath = Join-Path $env:TEMP "master_export.xlsx"
Invoke-WebRequest -Uri "$Global:Base/api/export/master?years=2022,2023,2024,2025" `
    -Headers @{ Authorization = "Bearer $token" } -OutFile $masterPath
$size = (Get-Item $masterPath).Length
Assert-True ($size -gt 10KB) "7a 总表导出 $size 字节"

$pyCheck = @"
import openpyxl, sys
wb = openpyxl.load_workbook(r"$masterPath")
ws = wb["sheet1"]
rows = ws.max_row
assert rows == 4720, f"sheet1 行数 {rows} != 4720"
code = ws.cell(row=807, column=1).value
assert str(code) == "25.2.3.3.5.1.1", f"row807 code {code} != 25.2.3.3.5.1.1"
name807 = str(ws.cell(row=807, column=2).value or "")
print("MASTER_EXPORT_CHECK_OK rows=%d row807=%s" % (rows, code))
"@
$pyPath = Join-Path $env:TEMP "check_master_task10.py"
[System.IO.File]::WriteAllText($pyPath, $pyCheck, (New-Object System.Text.UTF8Encoding $false))
$pyOut = python $pyPath 2>&1
Write-Host $pyOut
Remove-Item $pyPath -ErrorAction SilentlyContinue
Assert-True ("$pyOut" -match "MASTER_EXPORT_CHECK_OK") "7b Python 校验总表导出通过"

if ($script:Skipped -gt 0) {
    Write-Host "SKIPPED: $($script:Skipped)"
}
Write-Host "SMOKE TASK_10 DONE"
