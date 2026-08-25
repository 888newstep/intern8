# JMeter 压测资产说明

当前项目的唯一有效 JMeter 入口位于 `scripts/`，避免 `deploy/jmeter` 与实际 Controller 路径、JWT 认证和持续时间模型分叉。

## 入口文件

- `scripts/jmeter-test-plan.jmx`：只读基线，动态详情、Feed、通知列表。
- `scripts/jmeter-write-safety-test.jmx`：发布、点赞、评论写入测试，默认门禁阻断请求。
- `scripts/run-jmeter.ps1`：推荐的 PowerShell CLI runner。
- `scripts/analyze-jmeter-results.ps1`：JTL 聚合分析。
- `scripts/PERFORMANCE_TEST_GUIDE.md`：前置条件、参数、清理和证据要求。

## 推荐运行方式

```powershell
$secureToken = Read-Host "Enter temporary JWT token" -AsSecureString
$env:JWT_TOKEN = [System.Net.NetworkCredential]::new('', $secureToken).Password
try {
  powershell -NoProfile -ExecutionPolicy Bypass `
    -File scripts/run-jmeter.ps1 `
    -Scenario read `
    -BaseUrl 127.0.0.1 `
    -Port 8080 `
    -DurationSeconds 60 `
    -RampUpSeconds 10
} finally {
  Remove-Item Env:JWT_TOKEN -ErrorAction SilentlyContinue
}
```

不要使用仓库中不存在的旧 `performance-test.jmx`，也不要把固定的 QPS/P99 目标当作已验证结果。真实指标必须来自 JTL 和对应环境记录。
