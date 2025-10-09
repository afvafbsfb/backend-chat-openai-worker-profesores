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
