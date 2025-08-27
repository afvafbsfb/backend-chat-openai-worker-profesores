@echo off
setlocal

REM ===========================================
REM  Ejecuta run-pruebas.ps1 y abre el índice HTML
REM  Proyecto: chat-backend-springboot-workers-profesores
REM ===========================================

REM --- (opcional) configura variables de entorno aquí ---
REM set CHAT_BACKEND_BASE_URL=http://localhost:8080
REM set CHAT_BACKEND_API_KEY=tu_api_key
REM set CHAT_BACKEND_JWT=tu_jwt

REM Directorio del script (.cmd)
set "SCRIPT_DIR=%~dp0"

REM URL base por defecto si no está definida
if "%CHAT_BACKEND_BASE_URL%"=="" set "CHAT_BACKEND_BASE_URL=http://localhost:8080"

REM Ruta del script de pruebas
set "PS1=%SCRIPT_DIR%documentacion\run-pruebas.ps1"

REM Selección de PowerShell (pwsh si existe, si no powershell clásico)
where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (
  set "PS_EXE=pwsh"
) else (
  set "PS_EXE=powershell"
)

echo.
echo === Ejecutando pruebas E2E con %PS_EXE% ===
"%PS_EXE%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%PS1%"
set "TEST_RC=%ERRORLEVEL%"

echo.
echo === Abriendo índice de flujos ===
set "FLOWS_URL=%CHAT_BACKEND_BASE_URL%/flows/index.html"
set "FLOWS_FILE=%SCRIPT_DIR%documentacion\flows\index.html"

REM Intentar abrir la URL servida por el backend; si falla, abrir el fichero local
"%PS_EXE%" -NoLogo -NoProfile -Command ^
  "$u='%FLOWS_URL%'; try{ $r=Invoke-WebRequest -UseBasicParsing -Uri $u -Method Head -TimeoutSec 3; if($r.StatusCode -ge 200 -and $r.StatusCode -lt 400){ Start-Process $u; exit 0 } else { exit 1 } } catch { exit 1 }"
if %ERRORLEVEL% neq 0 (
  if exist "%FLOWS_FILE%" (
    start "" "%FLOWS_FILE%"
  ) else (
    echo No se pudo abrir %FLOWS_URL% ni encontrar "%FLOWS_FILE%".
  )
)

echo.
if %TEST_RC%==0 (
  echo ✅ Pruebas completadas correctamente.
) else (
  echo ❌ run-pruebas.ps1 finalizo con codigo %TEST_RC%.
)

endlocal
