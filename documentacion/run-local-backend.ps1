# Script de ayuda para arrancar el backend localmente
# - Lee las claves de las variables de entorno si existen
# - Si no, pide las claves de forma interactiva (no guarda nada)
# - Ejecuta `mvnw.cmd` con el call operator (maneja correctamente rutas con espacios)

# forzar asignación en la misma línea:
#  $env:OPENAI_API_KEY='tu_clave_aqui'; $env:ACADEMIA_API_KEY='tu_clave_academia_si_es_necesaria'; & '.\documentacion\run-local-backend.ps1'



# esto es para levantar el backend  en local 
#------------------------------------------------
#New-Item -Path 'C:\mvnw' -ItemType Directory -Force
#$env:MAVEN_USER_HOME = 'C:\mvnw'
#& '.\documentacion\run-local-backend.ps1'

#comprobacion de estado en cmd:
#C:\Users\Angel FV\Desktop\FORMACION\chat_backend_academia\backend-chat-openai-worker-profesores>curl http://localhost:8080/actuator/health
#{"status":"UP"}

#Estado: backend arrancado localmente y /actuator/health responde UP.


# verifica la clave contra el endpoint de modelos
# $h = @{ Authorization = "Bearer $env:OPENAI_API_KEY" }
# Invoke-RestMethod -Uri 'https://api.openai.com/v1/models' -Headers $h



$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Push-Location (Join-Path $scriptDir '..')  # sitúa en la raíz del repo

# Si existe application-local.properties en src/main/resources, cargar sus valores
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
            'academia.api.key' { $env:ACADEMIA_API_KEY = $val }
            'academia.api.baseurl' { $env:ACADEMIA_API_BASEURL = $val }
            default { }
        }
    }
}

if (-not (Test-Path ".\mvnw.cmd")) {
    Write-Error "No se encuentra mvnw.cmd en la carpeta actual. Ejecuta este script desde la carpeta raíz del repo."
    Pop-Location
    exit 1
}

function Get-Value([string]$envName, [string]$prompt) {
    $item = Get-Item -Path "env:$envName" -ErrorAction SilentlyContinue
    if ($item -and $item.Value) { return $item.Value }
    # pedir enmascarado
    $sec = Read-Host -Prompt $prompt -AsSecureString
    if (-not $sec) { return $null }
    return [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
}

$openai = Get-Value -envName 'OPENAI_API_KEY' -prompt 'OpenAI API key (input oculto)'
$academia = Get-Value -envName 'ACADEMIA_API_KEY' -prompt 'Academia API key (x-api-key) (input oculto)'
$academiaBase = (Get-Item -Path 'env:ACADEMIA_API_BASEURL' -ErrorAction SilentlyContinue).Value
if (-not $academiaBase) { $academiaBase = Read-Host 'Academia API base URL (ej. https://.../prod)' }

if (-not $openai) { Write-Host 'OpenAI key no proporcionada. Salir.'; Pop-Location; exit 1 }
if (-not $academia) { Write-Host 'Academia API key no proporcionada. Salir.'; Pop-Location; exit 1 }
if (-not $academiaBase) { Write-Host 'Academia base URL no proporcionada. Salir.'; Pop-Location; exit 1 }

$procArgs = @(
    "-Dopenai.api.key=$openai",
    "-Dopenai.api.url=https://api.openai.com/v1",
    "-Dacademia.api.baseurl=$academiaBase",
    "-Dacademia.api.key=$academia",
    "-Dbackend.debug=true",
    'spring-boot:run'
)

Write-Host "Ejecutando mvnw.cmd (mostrando logs en esta ventana)..."
Write-Verbose "Ejecutando: $PWD\mvnw.cmd $($procArgs -join ' ')"

$exe = Join-Path $PWD 'mvnw.cmd'
if (-not (Test-Path $exe)) {
    Write-Error "No se encuentra el wrapper en $exe"
    Pop-Location
    exit 1
}

# Evitar errores del wrapper cuando el path de usuario contiene espacios.
# Si MAVEN_USER_HOME no está fijado o contiene espacios, usar C:\mvnw como workaround
if (-not $env:MAVEN_USER_HOME -or $env:MAVEN_USER_HOME -match '\s') {
    $fallbackMavenHome = 'C:\mvnw'
    if (-not (Test-Path $fallbackMavenHome)) {
        New-Item -Path $fallbackMavenHome -ItemType Directory -Force | Out-Null
        Write-Host "Creada carpeta para MAVEN_USER_HOME en $fallbackMavenHome"
    }
    $env:MAVEN_USER_HOME = $fallbackMavenHome
    Write-Host "Aviso: MAVEN_USER_HOME fijado a $fallbackMavenHome para evitar problemas con rutas que contienen espacios."
}

# Exportar también como variables de entorno para que Spring Boot las lea directamente
$env:OPENAI_API_KEY = $openai
$env:ACADEMIA_API_KEY = $academia
$env:ACADEMIA_API_BASEURL = $academiaBase
Write-Host "Variables de entorno exportadas: OPENAI_API_KEY, ACADEMIA_API_KEY, ACADEMIA_API_BASEURL"

# Opción: guardar en archivo local ignorable para no tener que introducir la variable cada vez
$localPropsPath = Join-Path $PWD 'src\main\resources\application-local.properties'
if (Test-Path $localPropsPath) {
    Write-Host "Archivo local de propiedades detectado en src/main/resources/application-local.properties"
} else {
    $save = Read-Host '¿Deseas guardar estas claves en src/main/resources/application-local.properties para uso local? (s/N)'
    if ($save -and $save.ToLower().StartsWith('s')) {
        $content = @()
        $content += "openai.api.key=$openai"
        $content += "openai.api.url=https://api.openai.com/v1"
        $content += "academia.api.baseurl=$academiaBase"
        $content += "academia.api.key=$academia"
        $content | Out-File -FilePath $localPropsPath -Encoding utf8 -Force
        Write-Host "Guardado $localPropsPath (archivo gitignored). No compartas este archivo."
    }
}

try {
    & $exe @procArgs
} catch {
    Write-Error "Error al ejecutar mvnw.cmd: $_"
    Pop-Location
    exit 1
}

Pop-Location
