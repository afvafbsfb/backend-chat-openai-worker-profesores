param(
    [string]$DelegationSecret = 'mi_secret_delegacion_local_larga',
    [string]$MvnArgs = '',
    [switch]$NewWindow
)

# Resolve repo root and ensure logs dir
$RepoRoot = (Resolve-Path "$PSScriptRoot\..").Path
$LogsDir = Join-Path $RepoRoot 'logs'
New-Item -Path $LogsDir -ItemType Directory -Force | Out-Null

# Remove existing .log files (cleanup before start)
Get-ChildItem -Path $LogsDir -Filter '*.log' -File -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

# Build mvn command with system properties
# Use separate files for stdout and stderr to avoid Start-Process error when paths are identical
$LogFileOut = Join-Path $LogsDir 'mediator_stdout.log'
# Note: we log stdout to a file; stderr will be merged into stdout when running in foreground.
# Build command using explicit call operator (&) so PowerShell invokes mvn properly
# Use double quotes around the delegation secret so Maven receives the full value
Set-Location -LiteralPath $RepoRoot

# Export environment variables so the forked JVM (and any child processes) inherit them
# This makes System.getenv(...) inside the Spring Boot app return the expected values
$env:JWT_DELEGATION_SECRET = $DelegationSecret
$env:DEBUG = '1'
$env:FLASK_DEBUG = '1'
$env:APP_ENV = 'development'

# Build argument list for mvn and ensure JVM system properties are passed to the forked Spring Boot JVM
# Use -Dspring-boot.run.jvmArguments to forward JVM args. Construct the -D argument as a single token
$jvmArgs = "-Djwt.delegation.secret=$DelegationSecret -Dbackend.debug=true"
$jvmArgsArg = "-Dspring-boot.run.jvmArguments=$jvmArgs"
$argList = @($jvmArgsArg, 'spring-boot:run')
if ($MvnArgs -ne '') {
    # If MvnArgs contains multiple args separated by spaces, split them
    $extra = $MvnArgs -split '\s+'
    $argList += $extra
}

if ($NewWindow) {
    # Open a new PowerShell window and run mvn there so you can see live output
    # Build the command string by concatenation to avoid nested-quote parsing issues
    $safeCmd = 'Set-Location -LiteralPath ' + "'$RepoRoot'" + '; ' + "`$env:JWT_DELEGATION_SECRET = '$DelegationSecret'" + "; `$env:DEBUG = '1'; `$env:FLASK_DEBUG = '1'; `$env:APP_ENV = 'development'; mvn '" + $jvmArgsArg + "' spring-boot:run"
    Start-Process -FilePath 'powershell' -ArgumentList @('-NoExit','-Command',$safeCmd)
} else {
    # Run mvn in foreground in this PowerShell process so output is visible and logged.
    Write-Output "Starting mediator (foreground). Logs -> $LogFileOut"

    # Build the mvn arguments array and run it via the call operator. Quote the jvm arg so it's a single token.
    $mvnArgs = @("$jvmArgsArg", 'spring-boot:run')
    if ($MvnArgs -ne '') {
        $mvnArgs += ($MvnArgs -split '\s+')
    }

    try {
        # Merge stderr into stdout and tee to log file so you get live output and a full log
        & mvn @mvnArgs 2>&1 | Tee-Object -FilePath $LogFileOut -Append
    } catch {
        Write-Error "Error launching mvn: $_"
    }

    if (Test-Path $LogFileOut) {
        Write-Output "--- mediator stdout/stderr (tail) ---"
        Get-Content -Path $LogFileOut -Tail 200
    }
}
