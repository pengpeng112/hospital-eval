# TASK-17 冒烟：大屏汇总 → 对账模板 → Python 造数 → 导入 → flag 校验 → 导出
. "$PSScriptRoot\_common.ps1"
Add-Type -AssemblyName System.Net.Http

$token = Login-Admin
$Global:Base

# 1. 大屏汇总
$ov = Invoke-Api -Method "GET" -Path "/api/dashboard/overview?year=2025" -Token $token
$cards = $ov.data.cards
$chapters = @($ov.data.chapters)
$specialties = @($ov.data.specialties)
Assert-True ($ov.code -eq 0 -and $cards.metricTotal -gt 0) "1a cards.metricTotal=$($cards.metricTotal) > 0"
Assert-True ($chapters.Count -ge 5) "1b chapters $($chapters.Count) >= 5"
Assert-True ($specialties.Count -ge 20) "1c specialties $($specialties.Count) >= 20"
Write-Host "    cards: metricTotal=$($cards.metricTotal) sysRate=$($cards.sysRate) manualRate=$($cards.manualRate) archivedRate=$($cards.archivedRate)"

# 2. 下载模板 + Python 造 3 行测试数据
$tplPath = Join-Path $env:TEMP "reconcile_template.xlsx"
& curl.exe -s -m 60 -H "Authorization: Bearer $token" -o $tplPath "$Global:Base/api/reconcile/template"
Assert-True ((Get-Item $tplPath).Length -gt 3000) "2a 模板下载 $((Get-Item $tplPath).Length) 字节 > 3KB"

# 院内值从 /api/result/summary?keyword=NEU-EPI-01 取
$s = Invoke-Api -Method "GET" -Path "/api/result/summary?page=1&size=5&years=2025&keyword=NEU-EPI-01" -Token $token
$mrow = @($s.data.list)[0]
$cell = $mrow.years."2025"
Assert-True ($null -ne $cell -and $null -ne $cell.num) "2b NEU-EPI-01 有 2025 年院内结果 num=$($cell.num) den=$($cell.den) val=$($cell.val)"
$pNum = "$($cell.num)"; $pDen = "$($cell.den)"; $pVal = "$($cell.val)"
$testData = @(
    @{ code = "$($mrow.metricCode)"; name = "$($mrow.metricName)"; year = 2025; num = $pNum; den = $pDen; val = $pVal; expect = "OK" },
    @{ code = "$($mrow.metricCode)"; name = "$($mrow.metricName)"; year = 2025; num = $pNum; den = "0"; val = ""; expect = "PROV_DEN_ZERO" },
    @{ code = "99.99.99"; name = "不存在的指标"; year = 2025; num = "1"; den = "10"; val = "0.1"; expect = "NOT_FOUND" }
)
$dataPath = Join-Path $env:TEMP "reconcile_test.xlsx"
$py = @"
# -*- coding: utf-8 -*-
import sys
from openpyxl import load_workbook
path = r"$dataPath"
wb = load_workbook(r"$tplPath")
ws = wb.active
rows = [
    ["$($testData[0].code)", "$($testData[0].name)", 2025, float("$($testData[0].num)"), float("$($testData[0].den)"), float("$($testData[0].val)")],
    ["$($testData[1].code)", "$($testData[1].name)", 2025, float("$($testData[1].num)"), 0, None],
    ["99.99.99", "不存在的指标", 2025, 1, 10, 0.1],
]
for i, r in enumerate(rows, start=2):  # 第 1 行是表头，数据从第 2 行起
    for j, v in enumerate(r, start=1):
        ws.cell(row=i, column=j, value=v)
wb.save(path)
print("written", path)
"@
$py | Out-File -FilePath (Join-Path $env:TEMP "reconcile_fill.py") -Encoding UTF8
python (Join-Path $env:TEMP "reconcile_fill.py")
Assert-True ($LASTEXITCODE -eq 0) "2c Python 写入 3 行测试数据"

# 3. 导入（multipart）
$httpClient = New-Object System.Net.Http.HttpClient
$httpClient.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $token)
$fs = [System.IO.File]::OpenRead($dataPath)
$fileContent = New-Object System.Net.Http.StreamContent($fs)
$form = New-Object System.Net.Http.MultipartFormDataContent
$form.Add($fileContent, "file", "reconcile_test.xlsx")
$importResp = $httpClient.PostAsync("$Global:Base/api/reconcile/import", $form).Result
$fs.Close()
$importBody = $importResp.Content.ReadAsStringAsync().Result | ConvertFrom-Json
Assert-True ($importBody.code -eq 0) "3a 导入成功 importId=$($importBody.data.importId)"
$importId = $importBody.data.importId
Assert-True ($importBody.data.total -eq 3) "3b total=$($importBody.data.total) == 3"
Assert-True ($importBody.data.issueCount -eq 2) "3c issueCount=$($importBody.data.issueCount) == 2"

# 4. onlyIssue=true → 2 条且 flag 正确
$rows = Invoke-Api -Method "GET" -Path "/api/reconcile/$importId/rows?onlyIssue=true&page=1&size=20" -Token $token
$flags = @(@($rows.data.list) | ForEach-Object { $_.flag }) | Sort-Object
Assert-True (@($rows.data.list).Count -eq 2) "4a onlyIssue 返回 $(@($rows.data.list).Count) 条 == 2"
$expect = @("NOT_FOUND", "PROV_DEN_ZERO") | Sort-Object
$flagsJson = $flags -join ","
Assert-True ($flagsJson -eq ($expect -join ",")) "4b flags=$flagsJson 正确"

# 5. 导出差异清单 > 3KB
$expPath = Join-Path $env:TEMP "reconcile_export.xlsx"
& curl.exe -s -m 60 -H "Authorization: Bearer $token" -o $expPath "$Global:Base/api/reconcile/$importId/export"
Assert-True ((Get-Item $expPath).Length -gt 3000) "5 导出 $((Get-Item $expPath).Length) 字节 > 3KB"

Write-Host "SMOKE TASK_17 ALL PASS"
