# Ejecuta validaciones (unit + integration) y guarda salidas en documentacion/flows/<timestamp>
# Uso: Ejecuta en PowerShell desde la raíz del proyecto

param(
    [string]$ProjectRoot = "$(Get-Location)",
    [string]$MavenCmd = "mvn"
)

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dest = Join-Path -Path $ProjectRoot -ChildPath "documentacion\flows\run-$timestamp"
New-Item -ItemType Directory -Force -Path $dest | Out-Null

Write-Host "Guardando resultados en: $dest"

# 1) Tests unitarios rápidos
$out1 = Join-Path $dest "unit-tests.txt"
Write-Host "Ejecutando unit tests..."
& $MavenCmd -DskipITs test *>&1 | Tee-Object -FilePath $out1

# 2) Suite completa (unit + IT)
$out2 = Join-Path $dest "full-verify.txt"
Write-Host "Ejecutando mvn verify (unit + integration)... Esto puede tardar..."
& $MavenCmd -DskipTests=false verify *>&1 | Tee-Object -FilePath $out2

# 3) Salida breve de logs de Spring Boot (últimas 200 líneas)
$out3 = Join-Path $dest "latest-spring-logs.txt"
Get-Content $out2 -Raw | Select-String -Pattern "\bINFO\b|\bWARN\b|\bERROR\b" | Select-Object -Last 200 | Out-File $out3 -Encoding utf8

Write-Host "Ejecución completada. Resultados guardados en: $dest"
Write-Host "Puedes abrir los archivos con tu editor o copiar la carpeta a otro entorno para su revisión."
