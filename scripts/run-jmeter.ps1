[CmdletBinding()]
param(
    [ValidateSet("read", "write")]
    [string]$Scenario = "read",

    [string]$BaseUrl = "127.0.0.1",
    [int]$Port = 8080,
    [long]$DynamicId = 12345,
    [long]$FeedCursor = 999999,
    [long]$NotificationCursor = 999999,
    [ValidateRange(1, 100)]
    [int]$PageLimit = 20,
    [ValidateRange(1, 10000)]
    [int]$DetailThreads = 50,
    [ValidateRange(1, 10000)]
    [int]$FeedThreads = 30,
    [ValidateRange(1, 10000)]
    [int]$NotificationThreads = 20,
    [ValidateRange(1, 10000)]
    [int]$PublishThreads = 2,
    [ValidateRange(1, 10000)]
    [int]$LikeThreads = 2,
    [ValidateRange(1, 10000)]
    [int]$CommentThreads = 2,
    [ValidateRange(1, 86400)]
    [int]$DurationSeconds = 60,
    [ValidateRange(0, 86400)]
    [int]$RampUpSeconds = 10,
    [string]$OutputRoot,

    [switch]$ConfirmWrite
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $PSScriptRoot "..\target\jmeter-results"
}

if ([string]::IsNullOrWhiteSpace($env:JWT_TOKEN)) {
    throw "JWT_TOKEN must be set in the environment; the runner never stores tokens in files."
}

if ($Scenario -eq "write" -and -not $ConfirmWrite) {
    throw "Write tests mutate MySQL data and may publish RabbitMQ outbox events. Re-run with -ConfirmWrite."
}

$javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
if ($null -eq $javaCommand) {
    throw "java.exe was not found on PATH. Install a supported JDK before running JMeter."
}

$jmeterHome = $null
if (-not [string]::IsNullOrWhiteSpace($env:JMETER_HOME)) {
    $jmeterHome = [IO.Path]::GetFullPath($env:JMETER_HOME)
} else {
    $jmeterCommand = Get-Command jmeter.bat -ErrorAction SilentlyContinue
    if ($null -ne $jmeterCommand) {
        $jmeterHome = Split-Path (Split-Path $jmeterCommand.Source -Parent) -Parent
    }
}

if ([string]::IsNullOrWhiteSpace($jmeterHome)) {
    throw "Apache JMeter was not found. Set JMETER_HOME or add jmeter.bat to PATH."
}

$jmeterBin = Join-Path $jmeterHome "bin"
$jmeterJar = Join-Path $jmeterBin "ApacheJMeter.jar"
if (-not (Test-Path -LiteralPath $jmeterJar -PathType Leaf)) {
    throw "ApacheJMeter.jar was not found: $jmeterJar"
}

if (-not (Test-NetConnection -ComputerName $BaseUrl -Port $Port -InformationLevel Quiet)) {
    throw "Application endpoint is unreachable: $BaseUrl`:$Port"
}

$planName = if ($Scenario -eq "read") { "jmeter-test-plan.jmx" } else { "jmeter-write-safety-test.jmx" }
$planPath = Join-Path $PSScriptRoot $planName
if (-not (Test-Path -LiteralPath $planPath -PathType Leaf)) {
    throw "JMeter plan not found: $planPath"
}

$runId = "{0:yyyyMMdd-HHmmssfff}-{1}" -f (Get-Date), ([Guid]::NewGuid().ToString("N").Substring(0, 8))
$outputDirectory = [IO.Path]::GetFullPath((Join-Path $OutputRoot "$Scenario-$runId"))
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

$jtlPath = Join-Path $outputDirectory "results.jtl"
$reportPath = Join-Path $outputDirectory "html-report"
$summaryPath = Join-Path $outputDirectory "summary.json"

$jmeterLogPath = Join-Path $outputDirectory 'jmeter.log'
$arguments = @(
    "-n",
    "-t", $planPath,
    "-l", $jtlPath,
    "-e",
    "-o", $reportPath,
    "-JBASE_URL=$BaseUrl",
    "-JPORT=$Port",
    "-JJWT_TOKEN=$($env:JWT_TOKEN)",
    "-JDYNAMIC_ID=$DynamicId",
    "-JFEED_CURSOR=$FeedCursor",
    "-JNOTIFICATION_CURSOR=$NotificationCursor",
    "-JPAGE_LIMIT=$PageLimit",
    "-JDETAIL_THREADS=$DetailThreads",
    "-JFEED_THREADS=$FeedThreads",
    "-JNOTIFICATION_THREADS=$NotificationThreads",
    "-JRAMP_UP_SECONDS=$RampUpSeconds",
    "-JDURATION_SECONDS=$DurationSeconds"
)

if ($Scenario -eq "write") {
    $arguments += "-JWRITE_RUN_ID=$runId"
    $arguments += "-JPUBLISH_THREADS=$PublishThreads"
    $arguments += "-JLIKE_THREADS=$LikeThreads"
    $arguments += "-JCOMMENT_THREADS=$CommentThreads"
}

Write-Output "Running $Scenario JMeter plan against $BaseUrl`:$Port"
$jmeterExitCode = 0
Push-Location $jmeterBin
try {
    & $javaCommand.Source -jar $jmeterJar -j $jmeterLogPath @arguments
    $jmeterExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($jmeterExitCode -ne 0) {
    throw "JMeter exited with code $jmeterExitCode. Inspect $outputDirectory and $jmeterLogPath."
}

$analyzer = Join-Path $PSScriptRoot "analyze-jmeter-results.ps1"
try {
    & $analyzer -JtlPath $jtlPath -OutputPath $summaryPath
} catch {
    throw "JTL analysis failed. Inspect $jtlPath. $($_.Exception.Message)"
}

Write-Output "JMeter report: $reportPath"
Write-Output "JMeter summary: $summaryPath"
