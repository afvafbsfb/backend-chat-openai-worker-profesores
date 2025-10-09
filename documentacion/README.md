# Documentación de herramientas y scripts

Resumen rápido
- `run-pruebas.ps1` — Script canónico para arrancar/verificar el mediador (backend). NO ejecuta pruebas; sólo arranca el backend opcionalmente y espera a `/actuator/health`.
- `archive/` — Copias de scripts legacy (por ejemplo `run-local-backend.ps1`, `run-pruebas-y-abrir-indice.cmd`, `run-validation-and-save-flows.ps1`) preservadas para referencia.

Uso recomendado (desarrollo local)
1) Levantar el mediador desde este repositorio (opcional):

```powershell
# Arranca el backend desde este repo, no mockea OpenAI por defecto
.\documentacion\run-pruebas.ps1 -StartBackend:$true -MockOpenAI:$false -Mode test
```

2) Ejecutar las pruebas E2E desde `api-workers-profesores` (tests que hacen login y llaman al mediador):

```powershell
# En otra terminal, dentro del repo api-workers-profesores
cd C:\ruta\a\api-workers-profesores
$env:MEDIATOR_URL = 'http://localhost:8080'
pytest tests/chat/test_mediator_chat.py -q
```

Notas de CI
- Para pipelines, se recomienda usar un job que arranque el mediador con `-Dopenai.mock=true` (o usar WireMock) y otro job que arranque `api-workers-profesores` con su DB de pruebas; finalmente ejecutar pytest desde `api-workers-profesores`.
- Si necesitas un script CI no-interactivo en este repo, crea `run-pruebas-ci.ps1` con defaults apropiados (por ejemplo, `-StartBackend:$true -MockOpenAI:$true -Mode test`).

Archivos archivados
- `documentacion/archive/` contiene copias de scripts auxiliares previamente usados. Estos se preservan por compatibilidad y referencia.

Contacto
- Si tienes dudas sobre cómo orquestar ambos repos (mediador + api-workers-profesores) en CI, dime y te hago un ejemplo de pipeline (GitHub Actions o Azure DevOps).

Sincronizar `served-openapi.json` desde el repo API
-----------------------------------------------
Cuando necesites actualizar manualmente el `served-openapi.json` que genera el repo `api-workers-profesores`, sigue estos pasos PowerShell desde tu máquina de desarrollo.

1) (Opcional) Haz una copia de seguridad del spec actual en este repo:

```powershell
Copy-Item -Path "C:\Users\Angel FV\Desktop\FORMACION\chat_backend_academia\backend-chat-openai-worker-profesores\src\main\resources\served-openapi.json" -Destination "C:\Users\Angel FV\Desktop\FORMACION\chat_backend_academia\backend-chat-openai-worker-profesores\src\main\resources\served-openapi.json.bak" -Force
```

2) Copia (sobrescribe) el fichero descargado del repo API (ej. desde tu carpeta de Descargas):

```powershell
Copy-Item -Path "C:\Users\Angel FV\Downloads\served-openapi\docs\served-openapi.json" -Destination "C:\Users\Angel FV\Desktop\FORMACION\chat_backend_academia\backend-chat-openai-worker-profesores\src\main\resources\served-openapi.json" -Force
```

3) Verifica que el JSON se puede parsear y cuenta los `paths` (salida: número de paths):

```powershell
$p = "C:\Users\Angel FV\Desktop\FORMACION\chat_backend_academia\backend-chat-openai-worker-profesores\src\main\resources\served-openapi.json"
$j = Get-Content $p -Raw | ConvertFrom-Json
Write-Host "Paths in spec:" ($j.paths.PSObject.Properties.Count)
```

4) Ejecuta el test focalizado que valida que `OpenAICallApiService` resuelve endpoints por `operationId`:

```powershell
cd "C:\Users\Angel FV\Desktop\FORMACION\chat_backend_academia\backend-chat-openai-worker-profesores"
mvn -DskipITs test -Dtest=*OpenAICallApiServiceOperationIdTest test
```

5) Si todo pasa y quieres versionar el cambio, commitea y pushea:

```powershell
git add src/main/resources/served-openapi.json
git commit -m "chore(spec): update served-openapi.json from api artifact"
git push origin ampliacion-proyecto
```

Notas
- Si más adelante quieres automatizar la descarga, en CI puedes usar un job que haga curl al artifact publicado (o use la API de GitHub para descargar artifacts entre repos) y copie el fichero antes de ejecutar los tests/validaciones.
- Por ahora hemos añadido un workflow de validación ligero que puede usar una URL proporcionada en secrets para descargar el `served-openapi.json` y correr los tests focalizados.
