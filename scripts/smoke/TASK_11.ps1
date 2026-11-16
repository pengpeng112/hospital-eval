# TASK-11 冒烟：人工上报 → 佐证 → 三级复核 → 归档/解锁 → 工作台 → 清理
. "$PSScriptRoot\_common.ps1"

$script:Skipped = 0
$token = Login-Admin
$YEAR = 2099

# 选一个 MANUAL、非免统、实例数 1~2 的测试指标（SQL 经 stdin 送入 psql，规避引号转义）
$sqlPick = "SELECT d.id FROM eval_metric_dict d JOIN eval_metric_dept_scope s ON s.metric_id = d.id WHERE d.extract_mode = 'MANUAL' AND d.is_applicable = true GROUP BY d.id HAVING count(s.id) BETWEEN 1 AND 2 AND count(*) FILTER (WHERE s.skip_flag = false) = count(*) ORDER BY d.id LIMIT 1;"
$select = ($sqlPick | docker exec -i eval-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A') | Select-Object -First 1
if (-not $select -or -not "$select".Trim() -match "^\d+$") {
    Write-Host "SKIP: 未找到 MANUAL 测试指标"
    Write-Host "SMOKE TASK_11 DONE"
    exit 0
}
$mid = [long]$select.Trim()
$scopes = Invoke-Api -Method "GET" -Path "/api/scope/list?page=1&size=5&metricId=$mid" -Token $token
$scopeIds = @(@($scopes.data.list) | ForEach-Object { [long]$_.id })
Write-Host "测试指标 metricId=$mid 实例数=$($scopeIds.Count)"

# 1. admin manual-value（2099 年，分子 1 分母 2）
$firstScope = $scopeIds[0]
$mv = Invoke-Api -Method "POST" -Path "/api/report/manual-value" -Token $token -Body @{
    metricId = $mid; scopeId = $firstScope; year = $YEAR
    numeratorVal = 1; denominatorVal = 2; remark = "TASK_11 冒烟"
}
Assert-True ($mv.code -eq 0 -and $mv.data.resultId) "1 manual-value 成功 resultId=$($mv.data.resultId)"
$resultId = $mv.data.resultId

# 2. 无附件 submit → failed 含原因
$sub1 = Invoke-Api -Method "POST" -Path "/api/audit/submit" -Token $token -Body @{ resultIds = @($resultId) }
$firstFail = @($sub1.data.failed)[0]
Assert-True ([int]$sub1.code -eq 0 -and @($sub1.data.failed).Count -ge 1 -and $firstFail.reason -match "佐证") "2 无附件 submit 被拒（含佐证原因）"

# 3. 上传临时 PDF → preview-url / 下载 / 篡改 ticket
$pdfPath = Join-Path $env:TEMP "task11_smoke.pdf"
[System.IO.File]::WriteAllText($pdfPath, "smoke test")
Add-Type -AssemblyName System.Net.Http
$httpClient = New-Object System.Net.Http.HttpClient
$httpClient.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $token)
$scMetric = New-Object System.Net.Http.StringContent("$mid")
$scScope = New-Object System.Net.Http.StringContent("$firstScope")
$scPeriod = New-Object System.Net.Http.StringContent("$YEAR")
$scFolder = New-Object System.Net.Http.StringContent("冒烟佐证")
$form = New-Object System.Net.Http.MultipartFormDataContent
$fs = [System.IO.File]::OpenRead($pdfPath)
$fileContent = New-Object System.Net.Http.StreamContent($fs)
$form.Add($fileContent, "file", "task11_smoke.pdf")
$form.Add($scMetric, "metricId")
$form.Add($scScope, "scopeId")
$form.Add($scPeriod, "statPeriod")
$form.Add($scFolder, "folderCategory")
$uploadResp = $httpClient.PostAsync("$Global:Base/api/evidence/upload", $form).Result
$fs.Close()
$uploadBody = $uploadResp.Content.ReadAsStringAsync().Result | ConvertFrom-Json
Assert-True ($uploadBody.code -eq 0) "3a 附件上传成功"
$evId = $uploadBody.data.id

$pv = Invoke-Api -Method "GET" -Path "/api/evidence/$evId/preview-url" -Token $token
Assert-True ($pv.data.url -like "/kkview/onlinePreview?url=*") "3b preview-url 格式正确"

$rawUrl = $pv.data.rawUrl -replace "host.docker.internal", "127.0.0.1"
# PS 5.1 Invoke-WebRequest 在本机存在 WinINET 代理栈阻塞问题（服务端实测正常），改用 curl.exe
$fileText = & curl.exe -s -m 15 -H "Authorization: Bearer $token" $rawUrl
Assert-True ($fileText -match "smoke test") "3c ticket 下载文件成功"

$badTicketUrl = $rawUrl -replace "ticket=", "ticket=X"
$badCode = & curl.exe -s -m 15 -o NUL -w "%{http_code}" -H "Authorization: Bearer $token" $badTicketUrl
Assert-True ($badCode -eq "403" -or $badCode -eq "400") "3d 篡改 ticket 被拒绝 (HTTP $badCode)"

# 4. submit → reject → submit → approve
$sub2 = Invoke-Api -Method "POST" -Path "/api/audit/submit" -Token $token -Body @{ resultIds = @($resultId) }
Assert-True ($sub2.code -eq 0 -and @($sub2.data.success).Count -ge 1) "4a submit 成功"
$rej = Invoke-Api -Method "POST" -Path "/api/audit/reject" -Token $token `
    -Body @{ resultIds = @($resultId); opinion = "冒烟测试退回意见" }
Assert-True ($rej.code -eq 0) "4b reject 成功"
$sub3 = Invoke-Api -Method "POST" -Path "/api/audit/submit" -Token $token -Body @{ resultIds = @($resultId) }
Assert-True ($sub3.code -eq 0) "4c L2_REJECT 再 submit 成功"
$apv = Invoke-Api -Method "POST" -Path "/api/audit/approve" -Token $token -Body @{ resultIds = @($resultId) }
Assert-True ($apv.code -eq 0) "4d approve 成功"

# 5. 归档（1 或 2 实例两种路径）
if ($scopeIds.Count -ge 2) {
    $secondScope = $scopeIds[1]
    $mv2 = Invoke-Api -Method "POST" -Path "/api/report/manual-value" -Token $token -Body @{
        metricId = $mid; scopeId = $secondScope; year = $YEAR
        numeratorVal = 3; denominatorVal = 4; remark = "TASK_11 冒烟-第二科室"
    }
    $rid2 = $mv2.data.resultId
    $subA = Invoke-Api -Method "POST" -Path "/api/audit/submit" -Token $token -Body @{ resultIds = @($rid2) }
    $apvA = Invoke-Api -Method "POST" -Path "/api/audit/approve" -Token $token -Body @{ resultIds = @($rid2) }
    Assert-True ($apvA.code -eq 0) "5x 第二科室审批通过"
}
$archive = Invoke-Api -Method "POST" -Path "/api/audit/archive" -Token $token `
    -Body @{ metricId = $mid; year = $YEAR }
if ($archive.code -ne 0) {
    Write-Host "archive 失败详情: code=$($archive.code) msg=$($archive.msg)"
}
Assert-True ($archive.code -eq 0) "5y archive 成功"

# 6. 归档后 manual-value 失败；unlock 成功
$mvBlocked = Invoke-Api -Method "POST" -Path "/api/report/manual-value" -Token $token -Body @{
    metricId = $mid; scopeId = $firstScope; year = $YEAR
    numeratorVal = 9; denominatorVal = 9
}
Assert-True ([int]$mvBlocked.code -eq 400) "6a 归档后 manual-value 被拒 code=400"
$unlock = Invoke-Api -Method "POST" -Path "/api/audit/unlock" -Token $token `
    -Body @{ metricId = $mid; year = $YEAR; opinion = "TASK_11 冒烟解锁" }
Assert-True ($unlock.code -eq 0) "6b unlock 成功"

# 7. audit/logs 条数 ≥ 6
$logs = Invoke-Api -Method "GET" -Path "/api/audit/logs?metricId=$mid&year=$YEAR" -Token $token
Assert-True (@($logs.data).Count -ge 6) "7 audit/logs $(@($logs.data).Count) ≥ 6"

# 8. 工作台四接口
$ov = Invoke-Api -Method "GET" -Path "/api/workbench/overview?year=2025" -Token $token
$td = Invoke-Api -Method "GET" -Path "/api/workbench/todos?year=2025" -Token $token
$dp = Invoke-Api -Method "GET" -Path "/api/workbench/dept-progress?year=2025" -Token $token
$al = Invoke-Api -Method "GET" -Path "/api/workbench/alerts?year=2025" -Token $token
Assert-True ($ov.code -eq 0 -and $td.code -eq 0 -and $dp.code -eq 0 -and $al.code -eq 0) "8 工作台四接口 code=0"

# 9. 清理 2099 测试数据（唯一允许物理删除的场景，语句打印）
$cleanupSql = @"
DELETE FROM eval_audit_log WHERE result_id IN (SELECT id FROM eval_metric_result WHERE stat_period = '2099');
DELETE FROM eval_metric_result WHERE stat_period = '2099';
DELETE FROM eval_manual_evidence WHERE stat_period = '2099';
"@
$cleanupSql | docker exec -i eval-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
Write-Host "清理语句已执行（3 条 DELETE）"

$left = docker exec eval-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -c "SELECT count(*) FROM eval_metric_result WHERE stat_period = ''2099''"'
Assert-True ([int]"$left" -eq 0) "9 清理后 2099 结果行 = 0"

Write-Host "SMOKE TASK_11 ALL PASS"
