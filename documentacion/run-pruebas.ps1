# Antes de ejecutar este script, sitúate en la raíz del proyecto con:
# cd C:\chat-backend-springboot-workers-profesores

# Levantar el backend en una consola powershell
# maven en el path --> mvn spring-boot:run       :sin maven en path --> .\mvnw spring-boot:run  
# esta orden      mvn spring-boot:run     compila y levanta el back-end

#tambien se puede compilar con -->  mvn clean package  ó     .\mvnw clean package y luego levantar con jar
# java -jar "target\chat-backend-springboot-workers-profesores-0.0.1-SNAPSHOT.jar"

# Y luego ejecuta:
# powershell -ExecutionPolicy Bypass -File .\documentacion\run-pruebas.ps1

# Script de pruebas automáticas para el backend de chat
# Ejecuta cada prueba, espera la respuesta y la muestra en consola
# Puedes añadir más pruebas siguiendo el mismo formato

###############################################################
# --- Config índice de flujos ---
$FLOWS_DIR   = "documentacion/flows"
$FLOWS_INDEX = Join-Path $FLOWS_DIR "flows.json"
$FLOWS_KEEP  = 200   # número máximo de flujos a mantener en el índice

# ---- NUEVO: identificador de flujo compartido ----
$FLOW_ID = "prueba-{0:yyyyMMdd-HHmmss}-{1}" -f (Get-Date), (Get-Random -Maximum 1000)

function New-AuthHeaders {
  $h = @{"Content-Type"="application/json"}
  if ($API_KEY)   { $h["X-Api-Key"]     = $API_KEY }
  if ($JWT_TOKEN) { $h["Authorization"] = "Bearer $JWT_TOKEN" }
  $h["X-Flow-Id"] = $FLOW_ID
  return $h
}

# Importar función para actualizar el índice
. "$PSScriptRoot\flows\Update-FlowsIndex.ps1"

Write-Host "Prueba 1: Saludo al API"
$response1 = Invoke-WebRequest -Uri "http://localhost:8080/chat" `
  -Method POST `
  -Headers (New-AuthHeaders) `
  -Body ([System.Text.Encoding]::UTF8.GetBytes('{"messages":[{"role":"user","content":"Hola"}]}'))
$response1.Content
Write-Host "---"

Write-Host "Prueba 2: Quiero el Número de alumnos inscritos"
$response2 = Invoke-WebRequest -Uri "http://localhost:8080/chat" `
  -Method POST `
  -Headers (New-AuthHeaders) `
  -Body ([System.Text.Encoding]::UTF8.GetBytes('{"messages":[{"role":"user","content":"¿Cuántos alumnos tengo inscritos?"}]}'))
$response2.Content
Write-Host "---"

Write-Host "Prueba 3: quiero la Lista de alumnos y el número total"
$response3 = Invoke-WebRequest -Uri "http://localhost:8080/chat" `
  -Method POST `
  -Headers (New-AuthHeaders) `
  -Body ([System.Text.Encoding]::UTF8.GetBytes('{"messages":[{"role":"user","content":"me puedes pasar la lista de alumnos y el numero total que tengo?"}]}'))
$response3.Content
Write-Host "---"

Write-Host "Prueba 4: Quiero el Total de alumnos y lista de los 10 primeros"
$response4 = Invoke-WebRequest -Uri "http://localhost:8080/chat" `
  -Method POST `
  -Headers (New-AuthHeaders) `
  -Body ([System.Text.Encoding]::UTF8.GetBytes('{"messages":[{"role":"user","content":"dame por favor el total de alumnos y la lista de los 10 primeros alumnos."}]}'))
$response4.Content
Write-Host "---"

Write-Host "Prueba 5: Quiero el Detalle del alumno 45"
$response5 = Invoke-WebRequest -Uri "http://localhost:8080/chat" `
  -Method POST `
  -Headers (New-AuthHeaders) `
  -Body ([System.Text.Encoding]::UTF8.GetBytes('{"messages":[{"role":"user","content":"ahora quiero el detalle del alumno 43"}]}'))
$response5.Content
Write-Host "---"

Write-Host "Prueba 6: Quiero el número de alumnos inscritos en cada turno"
$response6 = Invoke-WebRequest -Uri "http://localhost:8080/chat" `
  -Method POST `
  -Headers (New-AuthHeaders) `
  -Body ([System.Text.Encoding]::UTF8.GetBytes('{"messages":[{"role":"user","content":"quiero saber el numero de alumnos que tengo inscritos en cada turno y que me lo devuelvas ordenado para ver en que turnos tengo mas alumnos y en que turnos tengo menos"}]}'))
$response6.Content
Write-Host "---"

# Prueba 7: Exportar alumnos a CSV
Write-Host "Prueba 7: Quiero Exportar alumnos a CSV"
$response7 = Invoke-WebRequest -Uri "http://localhost:8080/api/export?tabla=alumnos&type=csv" `
  -Method GET `
  -Headers (New-AuthHeaders)
if ($response7.StatusCode -eq 200) {
    Write-Host "Exportación correcta. Tamaño del archivo descargado: $($response7.Content.Length) bytes."
} else {
    Write-Host "Error en la exportación: $($response7.StatusCode) $($response7.StatusDescription)"
}
Write-Host "---"

# Prueba 8: Exportar los 10 primeros alumnos ordenados por nombre descendente a Excel
Write-Host "Prueba 8: Quiero Exportar alumnos a Excel (10 primeros, ordenados por nombre descendente)"
$response8 = Invoke-WebRequest -Uri "http://localhost:8080/api/export?tabla=alumnos&type=xlsx&page=0&size=10&sort=nombre&order=desc" `
  -Method GET `
  -Headers (New-AuthHeaders)
if ($response8.StatusCode -eq 200) {
    Write-Host "Exportación Excel correcta. Tamaño del archivo descargado: $($response8.Content.Length) bytes."
} else {
    Write-Host "Error en la exportación Excel: $($response8.StatusCode) $($response8.StatusDescription)"
}
Write-Host "---"


# --- Mostrar Flow ID y link local ---
Write-Host "`n---" -ForegroundColor DarkGray
Write-Host "Flow ID: $FLOW_ID" -ForegroundColor Yellow
$flowHtmlLocal = Join-Path $FLOWS_DIR "$FLOW_ID.html"
Write-Host "Si corres local, abre: $flowHtmlLocal"
Write-Host "Si lo sirves desde el backend, la URL dependerá de cómo expongas /documentacion/flows/..." -ForegroundColor DarkGray

# --- Generar HTML resumen del flow con las respuestas recogidas ---
try {
  # Helper compatible con PowerShell 5.1 para extraer contenido o devolver un placeholder
  function Get-RespContent($r) {
    if ($null -ne $r) { return $r.Content }
    return "(sin respuesta)"
  }

  $responses = @{
    prueba1 = Get-RespContent $response1
    prueba2 = Get-RespContent $response2
    prueba3 = Get-RespContent $response3
    prueba4 = Get-RespContent $response4
    prueba5 = Get-RespContent $response5
    prueba6 = Get-RespContent $response6
    prueba7 = Get-RespContent $response7
    prueba8 = Get-RespContent $response8
  }

  $html = @"
<!doctype html>
<html lang="es">
<head><meta charset="utf-8"><title>Flow $FLOW_ID</title>
<style>body{font-family:Arial,Helvetica,sans-serif;padding:18px;background:#f7fafc}pre{background:#fff;border:1px solid #e5e7eb;padding:12px;border-radius:6px;overflow:auto}</style>
</head>
<body>
<h1>Flow: $FLOW_ID</h1>
<p>Fecha: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")</p>
<h2>Respuestas</h2>
<h3>Prueba 1 - Saludo</h3>
<pre>$($responses.prueba1)</pre>
<h3>Prueba 2 - Número de alumnos</h3>
<pre>$($responses.prueba2)</pre>
<h3>Prueba 3 - Lista y total</h3>
<pre>$($responses.prueba3)</pre>
<h3>Prueba 4 - Total y 10 primeros</h3>
<pre>$($responses.prueba4)</pre>
<h3>Prueba 5 - Detalle alumno</h3>
<pre>$($responses.prueba5)</pre>
<h3>Prueba 6 - Alumnos por turno</h3>
<pre>$($responses.prueba6)</pre>
<h3>Prueba 7 - Export CSV</h3>
<pre>$($responses.prueba7)</pre>
<h3>Prueba 8 - Export Excel</h3>
<pre>$($responses.prueba8)</pre>
</body>
</html>
"@

  if (-not (Test-Path $FLOWS_DIR)) { New-Item -ItemType Directory -Path $FLOWS_DIR -Force | Out-Null }
  $html | Out-File -FilePath $flowHtmlLocal -Encoding utf8
  Write-Host "HTML del flow guardado en: $flowHtmlLocal" -ForegroundColor Green
} catch {
  Write-Warning "No se pudo generar HTML del flow: $($_.Exception.Message)"
}

# --- Actualizar índice de flujos ---
Update-FlowsIndex -FlowId $FLOW_ID -HtmlFileName (Split-Path $flowHtmlLocal -Leaf) -When (Get-Date)
Write-Host "`nÍndice actualizado: $FLOWS_INDEX" -ForegroundColor Green
Write-Host "Abre el índice: $(Resolve-Path $FLOWS_INDEX)" -ForegroundColor DarkCyan

# Puedes añadir más pruebas copiando el bloque anterior y cambiando el contenido del mensaje.


