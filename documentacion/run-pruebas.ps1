<#
Documentación: formas de arrancar el backend (mediador)

  y las pruebas desde el api-workers-profesores

  Para local development guarda tu OpenAI API key en
  `src/main/resources/application-local.properties` (fichero git-ignored). Ejemplo:

  spring.profiles.active=dev
  openai.api.key.dev=sk-<TU-OPENAI-KEY>

  El script cargará automáticamente ese fichero y exportará la variable necesaria al arrancar el backend.

  # ejecutar el backend sin pasar argumentos adicionales (el script leerá application-local.properties)
  # mvn "-Dopenai.mock=false" "-Dopenai.api.key=$env:OPENAI_API_KEY" "-Dbackend.debug=true" spring-boot:run


#otra opción (menos recomendable, porque no arranca el backend ni controla parámetros):
mvn "-Dopenai.mock=false" "-Dopenai.api.baseurl=https://api.openai.com/v1" "-Dopenai.api.endpoint.completions=/chat/completions" "-Dopenai.api.endpoint.responses=/responses" "-Dacademia.api.baseurl=http://localhost:5000" "-Dbackend.debug=true" spring-boot:run

cd 'C:\Users\Angel FV\Desktop\FORMACION\api-workers-profesores'
$env:MEDIATOR_URL = 'http://localhost:8080'

pytest tests/chat/test_mediator_chat.py -q

#>
param(
  [switch]$StartBackend,
  [switch]$MockOpenAI,
  [ValidateSet('test','prod')] [string]$Mode = 'test',
  [bool]$OpenIndex = $true
)

# Normalize StartBackend: default true when caller omits the parameter (keeps prior behaviour),
# but allow explicit -StartBackend:$false to disable.
if (-not $PSBoundParameters.ContainsKey('StartBackend')) {
  $StartBackend = $true
} else {
  # If caller passed -StartBackend or -StartBackend:$true/$false, convert to boolean
  $StartBackend = [bool]$StartBackend.IsPresent
}

# Script parametrizable de pruebas para el backend de chat.
# - Puede arrancar el backend localmente (opcional) con -StartBackend
# - Controlar si el backend arranca con OpenAI mockeado: -MockOpenAI (por defecto: false en esta primera versión)
# - Mode=test generará flows/HTML y activa backend.debug; Mode=prod desactiva generación de flows y logs verbosos.

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Push-Location (Join-Path $scriptDir '..')  # situarse en la raíz del repo

# Identificador del flujo (se mantiene solo como referencia si quieres forzar headers desde aquí)
$FLOW_ID = "prueba-{0:yyyyMMdd-HHmmss}-{1}" -f (Get-Date), (Get-Random -Maximum 1000)

function New-AuthHeaders {
  param([switch]$IncludeFlow)
  $h = @{"Content-Type"="application/json"}
  if ($env:API_KEY)   { $h["X-Api-Key"]     = $env:API_KEY }
  if ($env:JWT_TOKEN) { $h["Authorization"] = "Bearer $env:JWT_TOKEN" }
  $h["X-Flow-Id"] = $FLOW_ID
  if ($IncludeFlow) { $h["X-Flow-Diagram"] = 'true' }
  return $h
}

# Cargar helper para actualizar el índice (si existe)
if (Test-Path "$PSScriptRoot\flows\Update-FlowsIndex.ps1") { . "$PSScriptRoot\flows\Update-FlowsIndex.ps1" }

function Wait-For-Health {
  param([string]$Url = 'http://localhost:8080', [int]$Timeout = 60)
  $health = "$($Url.TrimEnd('/'))/actuator/health"
  $deadline = (Get-Date).AddSeconds($Timeout)
  while ((Get-Date) -lt $deadline) {
    try {
      $r = Invoke-RestMethod -Uri $health -Method Get -TimeoutSec 3 -ErrorAction Stop
          if ($r -and ($r.status -or $r.STATUS)) {
            $status = $null
            if ($r.status) { $status = $r.status } elseif ($r.STATUS) { $status = $r.STATUS }
            if ($status -and $status.ToString().ToUpper() -eq 'UP') { return $true }
          } else { return $true }
    } catch { Start-Sleep -Seconds 1 }
  }
  return $false
}

# Función para arrancar el backend si se solicita
$mvnProc = $null
if ($StartBackend) {
  Write-Host "Arrancando backend (StartBackend = $StartBackend) ..."
  # Cargar posibles propiedades locales (application-local.properties)
  $localPropsPath = Join-Path $PWD 'src\main\resources\application-local.properties'
  if (Test-Path $localPropsPath) {
    Write-Host "Cargando propiedades locales desde $localPropsPath"
    $lines = Get-Content $localPropsPath | Where-Object { $_ -and ($_ -match '=') }
    foreach ($ln in $lines) {
      $parts = $ln -split '=', 2
      $key = $parts[0].Trim()
      $val = $parts[1].Trim()
      switch ($key) {
        'openai.api.key' { $env:OPENAI_API_KEY = $val }
        'openai.api.baseurl' { $env:OPENAI_API_URL = $val } # legacy env var used by some components
        'openai.api.endpoint.completions' { $env:OPENAI_API_ENDPOINT_COMPLETIONS = $val }
        'openai.api.endpoint.responses' { $env:OPENAI_API_ENDPOINT_RESPONSES = $val }
        'openai.api.model' { $env:OPENAI_API_MODEL = $val }
        'academia.api.baseurl' { $env:ACADEMIA_API_BASEURL = $val }
        default { }
      }
    }
  }
  # Construir argumentos para mvnw
  if ($MockOpenAI.IsPresent) { $openaiMockValue = 'true' } else { $openaiMockValue = 'false' }
  if ($Mode -eq 'test') { $backendDebug = 'true' } else { $backendDebug = 'false' }
  # No local welcome handling: always delegate to OpenAI; remove chat.handle.welcome config
  # Defaults for test mode: prefer local academy API if not provided
  if (-not $env:ACADEMIA_API_BASEURL) { $env:ACADEMIA_API_BASEURL = 'http://localhost:5000' }
  $procArgs = @(
    "-Dopenai.mock=$openaiMockValue",
  "-Dopenai.api.key=$($env:OPENAI_API_KEY)",
  "-Dopenai.api.baseurl=$($env:OPENAI_API_URL)",
  "-Dopenai.api.endpoint.completions=$($env:OPENAI_API_ENDPOINT_COMPLETIONS)",
  "-Dopenai.api.endpoint.responses=$($env:OPENAI_API_ENDPOINT_RESPONSES)",
  "-Dacademia.api.baseurl=$($env:ACADEMIA_API_BASEURL)",
    "-Dbackend.debug=$backendDebug",
    'spring-boot:run'
  )
  # Ejecutar mvnw.cmd en background
  $mvnExe = Join-Path $PWD 'mvnw.cmd'
  if (-not (Test-Path $mvnExe)) { Write-Warning "mvnw.cmd no encontrado en la raíz. Asegúrate de ejecutar desde la raíz del repo." } else {
  Write-Host "Iniciando: $mvnExe $($procArgs -join ' ')"
  # Prepare logs directory and files for background process output capture
  $logDir = Join-Path $scriptDir 'logs'
  if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
  $safeFlowId = $FLOW_ID -replace '[^a-zA-Z0-9_-]', '_'
  $stdout = Join-Path $logDir "mvn_stdout_$safeFlowId.log"
  $stderr = Join-Path $logDir "mvn_stderr_$safeFlowId.log"
  # Build a single command line that cmd.exe will run; use cmd.exe /c to support redirection in PowerShell 5.1
  $cmdLine = '"' + $mvnExe + '" ' + ($procArgs -join ' ') + ' > "' + $stdout + '" 2> "' + $stderr + '"'
  # Start via cmd.exe so we can use shell redirection and still get a PID from Start-Process
  $mvnProc = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', $cmdLine -WorkingDirectory $PWD -PassThru
    Write-Host "Proceso mvn iniciado en background, PID: $($mvnProc.Id). Logs: $stdout and $stderr. Esperando /actuator/health..."
    if (-not (Wait-For-Health -Url 'http://localhost:8080' -Timeout 60)) { Write-Warning "Timeout esperando /actuator/health. Revisa los logs en $logDir para más detalles." }
  }
} else {
  Write-Host "StartBackend = false: se asume que el mediador ya está corriendo. Comprobando /actuator/health..."
  if (-not (Wait-For-Health -Url 'http://localhost:8080' -Timeout 30)) { Write-Warning "Mediator no responde en http://localhost:8080/actuator/health" }
}


Write-Host "Mediator arrancado (o verificado). Este script solo arranca/verifica el backend. No ejecuta pruebas." -ForegroundColor Cyan

Write-Host "Instrucciones para ejecutar pruebas (desde el repositorio api-workers-profesores):`n" -ForegroundColor DarkCyan
Write-Host "1) Abre una terminal y sitúate en el repo api-workers-profesores" -ForegroundColor Gray
Write-Host "   cd C:\ruta\a\api-workers-profesores" -ForegroundColor Yellow
Write-Host "2) Asegúrate de que el test apunta al mediador (por defecto http://localhost:8080). Puedes exportar la variable MEDIATOR_URL si tu test la usa." -ForegroundColor Gray
Write-Host "   # ejemplo PowerShell:" -ForegroundColor Gray
Write-Host "   $env:MEDIATOR_URL = 'http://localhost:8080'" -ForegroundColor Yellow
Write-Host "3) Ejecuta pytest desde ese repo (los tests harán login y llamarán al mediador):" -ForegroundColor Gray
Write-Host "   pytest tests/chat/test_mediator_chat.py -q" -ForegroundColor Yellow

Write-Host "Generación de flows/HTML: si quieres que los tests generen los artefactos de flujo, los tests deben añadir estas cabeceras en las peticiones al mediador:" -ForegroundColor Gray
Write-Host "   X-Flow-Diagram: true" -ForegroundColor Yellow
Write-Host "   X-Flow-Id: <uuid-por-prueba>" -ForegroundColor Yellow

if ($StartBackend -and $mvnProc) {
    Write-Host "Si deseas detener el backend iniciado por este script, ejecuta:" -ForegroundColor Gray
    Write-Host "  Stop-Process -Id $($mvnProc.Id) -Force" -ForegroundColor Yellow
}

Pop-Location

Write-Host "Fin: el mediador está listo para recibir peticiones (por ejemplo, desde api-workers-profesores)." -ForegroundColor Green


