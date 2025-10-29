# Script para arrancar el backend Java (mediador de chat)
# El secreto de delegación ya está configurado en application.properties

Write-Host "🚀 Arrancando Backend Java (Chat Mediator)..." -ForegroundColor Green

# Verificar que el JAR compilado existe
$jarPath = "target\backend-chat-openai-worker-profesores-0.0.1-SNAPSHOT.jar"

if (-not (Test-Path $jarPath)) {
    Write-Host "❌ JAR no encontrado. Compilando primero..." -ForegroundColor Red
    Write-Host ""
    .\mvnw.cmd clean package -DskipTests
    Write-Host ""
}

if (Test-Path $jarPath) {
    Write-Host "✅ JAR encontrado: $jarPath" -ForegroundColor Cyan
    Write-Host "✅ Configuración (application.properties):" -ForegroundColor Cyan
    Write-Host "   - jwt.delegation.secret.dev = mi_secret_delegacion_local_larga"
    Write-Host "   - academia.api.baseurl.dev = http://localhost:5000"
    Write-Host "   - server.port.dev = 8080"
    Write-Host ""
    Write-Host "🌐 Arrancando servidor en http://localhost:8080 ..." -ForegroundColor Green
    Write-Host "   Presiona Ctrl+C para detener" -ForegroundColor Yellow
    Write-Host ""
    
    # Arrancar con el perfil dev (ya configurado en application.properties)
    java -jar $jarPath
} else {
    Write-Host "❌ Error: No se pudo compilar el JAR" -ForegroundColor Red
    exit 1
}
