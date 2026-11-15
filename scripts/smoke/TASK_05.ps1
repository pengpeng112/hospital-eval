# TASK-05 冒烟：健康检查 / 登录鉴权 / 用户科室 / 操作日志（顺序执行，全部 PASS 才结束）
. "$PSScriptRoot\_common.ps1"

# 1. /api/health
$health = Invoke-Api -Method "GET" -Path "/api/health"
Assert-True ($health.status -eq "UP" -and $health.db -eq "UP") "1 /api/health UP/UP"

# 2. 错误密码登录 → code == 400
$badPwd = "wrong_password_01"
$bad = Invoke-Api -Method "POST" -Path "/api/auth/login" -Body @{ username = "admin"; password = $badPwd }
Assert-True ([int]$bad.code -eq 400) "2 错误密码登录返回 code=400"

# 3. admin 登录成功，roles 含 ADMIN
$token = Login-Admin
$me = Invoke-Api -Method "GET" -Path "/api/auth/me" -Token $token
Assert-True ($me.data.roles -contains "ADMIN") "3 admin 登录成功且含 ADMIN 角色"

# 4. 不带 token 调 /api/auth/me → HTTP 401 或 code == 401
$anon = Invoke-Api -Method "GET" -Path "/api/auth/me"
Assert-True ([int]$anon.httpStatus -eq 401 -or [int]$anon.code -eq 401) "4 未登录访问 me 返回 401"

# 5. /api/system/depts 数量 ≥ 10
$depts = Invoke-Api -Method "GET" -Path "/api/system/depts" -Token $token
Assert-True (@($depts.data).Count -ge 10) "5 科室数量 $(@($depts.data).Count) ≥ 10"

# 6. /api/system/users?page=1&size=5 total ≥ 20
$users = Invoke-Api -Method "GET" -Path "/api/system/users?page=1&size=5" -Token $token
Assert-True ([int]$users.data.total -ge 20) "6 用户总数 $($users.data.total) ≥ 20"

# 7. 新建测试用户 smoke_user_05（已存在则跳过），用 EVAL_INIT_USER_PASSWORD 登录 mustChangePwd=true
$exists = Invoke-Api -Method "GET" -Path "/api/system/users?page=1&size=5&keyword=smoke_user_05" -Token $token
if ([int]$exists.data.total -eq 0) {
    $funcDept = @($depts.data | Where-Object { $_.deptName -eq "医疗部" } | Select-Object -First 1)
    if (-not $funcDept) { $funcDept = @($depts.data | Select-Object -First 1) }
    $created = Invoke-Api -Method "POST" -Path "/api/system/users" -Token $token -Body @{
        username = "smoke_user_05"; realName = "冒烟测试05"; deptId = $funcDept.id; roleCodes = @("LIAISON")
    }
    Assert-True ($created.code -eq 0 -and $created.data.id) "7a 创建 smoke_user_05"
} else {
    Write-Host "PASS: 7a smoke_user_05 已存在，跳过创建"
}
$initPwd = Get-EnvValue "EVAL_INIT_USER_PASSWORD"
$smokeLogin = Invoke-Api -Method "POST" -Path "/api/auth/login" -Body @{ username = "smoke_user_05"; password = $initPwd }
Assert-True ($smokeLogin.code -eq 0 -and $smokeLogin.data.mustChangePwd -eq $true) "7b smoke_user_05 登录且 mustChangePwd=true"

# 8. /api/system/oper-logs 存在 LOGIN 记录
$logs = Invoke-Api -Method "GET" -Path "/api/system/oper-logs?page=1&size=50&module=AUTH" -Token $token
$hasLogin = @($logs.data.list | Where-Object { $_.action -eq "LOGIN" }).Count -ge 1
Assert-True $hasLogin "8 操作日志存在 LOGIN 记录"

Write-Host "SMOKE TASK_05 ALL PASS"
