<#
run_mediator_dev.ps1

Small helper to start the backend in development mode on Windows PowerShell.
It reads or prompts for JWT_DELEGATION_SECRET, sets a few env vars for the JVM,
prints a fingerprint (sha256) of the secret, ensures logs directory exists and
runs the mvnw.cmd wrapper from the repository root, streaming output to logs/mediator_stdout.log.

Usage examples:
  .\scripts\run_mediator_dev.ps1
  .\scripts\run_mediator_dev.ps1 -DelegationSecret 'mi_secret' -OpenAIMock:$true
#>

param(
    [string]$DelegationSecret = $env:JWT_DELEGATION_SECRET,
    [bool]$OpenAIMock = $false
)

Set-StrictMode -Version Latest

# Resolve repo root (script resides in scripts/)
$ScriptPath = $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent (Split-Path -Parent $ScriptPath)

if (-not $DelegationSecret -or $DelegationSecret -eq '') {
    $DelegationSecret = Read-Host -Prompt 'Enter JWT_DELEGATION_SECRET for local dev (will not be stored)'
}

if (-not (Test-Path "$RepoRoot\logs")) { New-Item -Type Directory -Path "$RepoRoot\logs" | Out-Null }

# Export env vars for this process and child processes
$env:JWT_DELEGATION_SECRET = $DelegationSecret
$env:BACKEND_DEBUG = 'true'
$env:SPRING_PROFILES_ACTIVE = 'dev'
if ($OpenAIMock) { $env:OPENAI_MOCK = 'true' } else { $env:OPENAI_MOCK = 'false' }

# Print fingerprint (safe) for quick comparison with API logs
try {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($DelegationSecret)
    $sha = ([System.BitConverter]::ToString(([System.Security.Cryptography.SHA256]::Create()).ComputeHash($bytes))).Replace('-', '').ToLower()
    Write-Output "[run_mediator_dev] JWT_DELEGATION_SECRET_SHA256=$sha, JWT_DELEGATION_SECRET_LEN=$($DelegationSecret.Length)"
} catch {
    Write-Output "[run_mediator_dev] Could not compute secret fingerprint: $($_.Exception.Message)"
}

Write-Output "[run_mediator_dev] Starting backend (profile=dev). Logs -> $RepoRoot\logs\mediator_stdout.log"

Push-Location $RepoRoot
try {
    $mvnw = Join-Path $RepoRoot 'mvnw.cmd'
    if (-not (Test-Path $mvnw)) {
        Write-Error "mvnw.cmd not found in $RepoRoot. Ensure Maven wrapper exists or run mvn from shell."
        exit 1
    }
    Start-Process -FilePath $mvnw -ArgumentList 'spring-boot:run' -NoNewWindow -Wait -RedirectStandardOutput "$RepoRoot\logs\mediator_stdout.log"
} finally {
    Pop-Location
}

