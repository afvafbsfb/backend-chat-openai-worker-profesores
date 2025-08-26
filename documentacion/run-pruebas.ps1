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

Write-Host "Prueba 1: Saludo al API"
$response1 = Invoke-WebRequest -Uri "http://localhost:8080/chat" `
  -Method POST `
  -Headers @{ "Content-Type" = "application/json; charset=utf-8"; "X-Flow-Diagram" = "true" } `
  -Body ([System.Text.Encoding]::UTF8.GetBytes('{"messages":[{"role":"user","content":"Hola"}]}'))
$response1.Content
Write-Host "---"

Write-Host "Prueba 2: Número de alumnos inscritos"
$response2 = Invoke-WebRequest -Uri "http://localhost:8080/chat" `
  -Method POST `
  -Headers @{ "Content-Type" = "application/json; charset=utf-8"; "X-Flow-Diagram" = "true" } `
  -Body ([System.Text.Encoding]::UTF8.GetBytes('{"messages":[{"role":"user","content":"¿Cuántos alumnos tengo inscritos?"}]}'))
$response2.Content
Write-Host "---"

Write-Host "Prueba 3: Lista de alumnos y número total"
$response3 = Invoke-WebRequest -Uri "http://localhost:8080/chat" `
  -Method POST `
  -Headers @{ "Content-Type" = "application/json; charset=utf-8"; "X-Flow-Diagram" = "true" } `
  -Body ([System.Text.Encoding]::UTF8.GetBytes('{"messages":[{"role":"user","content":"me puedes pasar la lista de alumnos y el numero total que tengo?"}]}'))
$response3.Content
Write-Host "---"

Write-Host "Prueba 4: Total de alumnos y lista de los 10 primeros"
$response4 = Invoke-WebRequest -Uri "http://localhost:8080/chat" `
  -Method POST `
  -Headers @{ "Content-Type" = "application/json; charset=utf-8"; "X-Flow-Diagram" = "true" } `
  -Body ([System.Text.Encoding]::UTF8.GetBytes('{"messages":[{"role":"user","content":"dame por favor el total de alumnos y la lista de los 10 primeros alumnos."}]}'))
$response4.Content
Write-Host "---"

Write-Host "Prueba 5: Detalle del alumno 45"
$response5 = Invoke-WebRequest -Uri "http://localhost:8080/chat" `
  -Method POST `
  -Headers @{ "Content-Type" = "application/json; charset=utf-8"; "X-Flow-Diagram" = "true" } `
  -Body ([System.Text.Encoding]::UTF8.GetBytes('{"messages":[{"role":"user","content":"ahora quiero el detalle del alumno 43"}]}'))
$response5.Content
Write-Host "---"

Write-Host "Prueba 6: numero de alumnos inscritos en cada turno"
$response6 = Invoke-WebRequest -Uri "http://localhost:8080/chat" `
  -Method POST `
  -Headers @{ "Content-Type" = "application/json; charset=utf-8"; "X-Flow-Diagram" = "true" } `
  -Body ([System.Text.Encoding]::UTF8.GetBytes('{"messages":[{"role":"user","content":"quiero saber el numero de alumnos que tengo inscritos en cada turno y que me lo devuelvas ordenado para ver en que turnos tengo mas alumnos y en que turnos tengo menos"}]}'))
$response6.Content
Write-Host "---"

# Prueba 7: Exportar alumnos a CSV
Write-Host "Prueba 7: Exportar alumnos a CSV"
$response7 = Invoke-WebRequest -Uri "http://localhost:8080/api/export?tabla=alumnos&type=csv" `
  -Method GET
if ($response7.StatusCode -eq 200) {
    Write-Host "Exportación correcta. Tamaño del archivo descargado: $($response7.Content.Length) bytes."
} else {
    Write-Host "Error en la exportación: $($response7.StatusCode) $($response7.StatusDescription)"
}
Write-Host "---"

# Prueba 8: Exportar los 10 primeros alumnos ordenados por nombre descendente a Excel
Write-Host "Prueba 8: Exportar alumnos a Excel (10 primeros, ordenados por nombre descendente)"
$response8 = Invoke-WebRequest -Uri "http://localhost:8080/api/export?tabla=alumnos&type=xlsx&page=0&size=10&sort=nombre&order=desc" `
  -Method GET
if ($response8.StatusCode -eq 200) {
    Write-Host "Exportación Excel correcta. Tamaño del archivo descargado: $($response8.Content.Length) bytes."
} else {
    Write-Host "Error en la exportación Excel: $($response8.StatusCode) $($response8.StatusDescription)"
}
Write-Host "---"

# Puedes añadir más pruebas copiando el bloque anterior y cambiando el contenido del mensaje.


