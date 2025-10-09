```markdown
# RESUMEN DEL PROYECTO: chat-backend (mediador) para app Android
# RESUMEN DEL PROYECTO (versión actual)

Breve, operativo, y centrado en la fuente canónica de permisos/whitelist: `served-openapi.json`.

## Propósito
El mediador (backend-chat) valida peticiones del cliente Android, orquesta llamadas a OpenAI (function-calling) y ejecuta, cuando procede, llamadas a la API de academia. Todas las decisiones sobre qué endpoints pueden usarse y qué permisos aplican provienen ahora de `served-openapi.json` (generado por `api-workers-profesores`).

Resumen del flujo:
- Android → Backend-Chat (POST /chat) → OpenAI (function-calling) → Backend ejecuta llamadas a la API de academia cuando OpenAI lo solicita.
- El mediador aplica la política definida en `served-openapi.json` (operationId + `x-permissions`) para validar y sanear cada llamada.

## Fuente canónica: `served-openapi.json`
-- NOTA IMPORTANTE: la whitelist manual `api-whitelist.yaml` ha sido retirada y ELIMINADA del repositorio. A partir de ahora la fuente canónica es `served-openapi.json` (generado por `api-workers-profesores`). El artifact publicado contiene:
   - `operationId` estable para cada operación.
   - Metadatos `x-permissions` (roles, scopes, flags) que el mediador usa para autorización y saneamiento.
 - Reglas aplicadas en el mediador:
   - El mediador resuelve endpoints por `operationId` (fallback a nombre/alias si no existe).
   - `x-permissions` define qué roles pueden invocar la operación y qué parámetros están permitidos.

   Nota de backup y trazabilidad:
   - Si necesitás consultar la versión histórica de la whitelist, existe un backup en el historial de Git (o en `RESUMEN_PROYECTO_PARA_COLABORADORES.md.old` con documentación histórica). No mantengas archivos `api-whitelist.yaml` locales en ramas nuevas: el flujo correcto es publicar `served-openapi.json` desde el repo API y que el mediador lo consuma vía CI/artifact.

Ejemplo mínimo de `x-permissions` (para incluir en la API al definir endpoints):

```json
"x-permissions": {
   "roles": ["Admin_academia", "Profesor_academia"],
   "scopes": ["academiaId"],
   "requireAuth": true
}
```

## Flujo de publicación y consumo
   El mediador valida peticiones del cliente Android, orquesta llamadas a OpenAI (function-calling) y ejecuta, cuando procede, llamadas a la API de academia. El control de qué operaciones están permitidas y las reglas de autorización provienen de la spec `served-openapi.json` (operationId + `x-*` metadata).
- En el mediador (este repo): preferir que la CI descargue automáticamente `served-openapi.json` desde el artifact del API. Mientras tanto, flujo temporal manual:
   1. Descarga `served-openapi.json` desde el CI del API o desde el zip/artifact.
PowerShell temporal (ejemplo):


---
## Pruebas automáticas E2E y dashboard de flujos

```

## Conventions y buenas prácticas
- `operationId` debe ser estable y semántico (p. ej. `listAcademias`, `getUsuarioById`). Evitar renombrarlo salvo para versiones mayores: cualquier renombrado debe acompañarse de un cambio coordinado en el mediador.
- Incluir `x-permissions` en cada operación con roles y scopes mínimos necesarios.
- Mantener los ejemplos y la documentación de la API alineados con el spec publicado; los cambios a la API requieren publicar una nueva versión de `served-openapi.json`.

## Tests y CI
- Se añadió un test unitario en el mediador que verifica la resolución por `operationId`. Ejecutar localmente:

```powershell
El proyecto incluye un sistema de pruebas automáticas E2E y un dashboard visual para consultar el histórico de ejecuciones y trazas de cada flujo:
- Recomendación CI: crear job que descargue `served-openapi.json` del repo API (requiere PAT o permisos) y ejecute tests focales de validación.

## Migración y checklist para añadir un endpoint (resumido)
1. En `api-workers-profesores`: añadir endpoint, definir `operationId` y `x-permissions` en el OpenAPI generator o en la anotación.
2. Ejecutar la pipeline del API que genera y publica `served-openapi.json` como artifact/release.
3. En el mediador: actualizar `served-openapi.json` (automáticamente via CI o manualmente) y ejecutar los tests.
4. Verificar E2E: levantar mediador y ejecutar pruebas que cubran el caso.

## Documentación histórica
- El contenido anterior con detalles operativos, ejemplos, y scripts antiguos se ha guardado en `RESUMEN_PROYECTO_PARA_COLABORADORES.md.old`.

---

Si quieres, aplico ahora una versión ampliada con ejemplos concretos de `x-permissions`, snippet de PowerShell para descargar artifacts desde GitHub Actions, y un script de validación `scripts/validate-served-openapi.ps1` que chequea la presencia de `operationId` y `x-permissions` en el JSON.

   - Cada ejecución genera un identificador de flujo único (`X-Flow-Id`) que se envía en todas las peticiones, permitiendo agrupar y trazar todos los pasos de la prueba.
   - Los logs de cada flujo se guardan en formato XML y HTML en la carpeta `documentacion/flows/`, con el detalle de cada paso (Controller, Service, ApiProxy, etc.).
- **Dashboard de flows:**  
   - El archivo `documentacion/flows/index.html` muestra un índice navegable de todas las ejecuciones recientes, leyendo los datos de `documentacion/flows/flows.json` (actualizado automáticamente por el script de pruebas).
   - Cada fila del dashboard enlaza al HTML detallado de la ejecución correspondiente, permitiendo revisar el timeline completo del flujo.

- **Ejecución y visualización en un clic:**  
      - Ejecuta todas las pruebas E2E.
      - Al terminar, abre automáticamente el dashboard de flows en el navegador (vía backend si está expuesto, o localmente).
   - Así puedes validar el sistema y consultar los logs de cada ejecución de forma visual y sencilla.

- **Notas de despliegue:**  
   - La carpeta `documentacion/flows` debe existir y tener permisos de escritura para el backend y el usuario que ejecuta las pruebas.
   - Si quieres exponer el dashboard vía HTTP, añade en Spring Boot un `ResourceHandler` para `/flows/**` apuntando a `file:documentacion/flows/`.

Este sistema permite validar, auditar y depurar cualquier flujo de negocio o integración, con trazabilidad E2E y acceso visual inmediato a los resultados de cada prueba.

**Variables de entorno y personalización:**

   - `CHAT_BACKEND_BASE_URL`: URL base del backend (por defecto `http://localhost:8080`).
   - `CHAT_BACKEND_API_KEY`: API key si tu backend la requiere.
   - `CHAT_BACKEND_JWT`: Token JWT si es necesario para autenticación.
- Puedes descomentar y ajustar estas variables al principio del `.cmd` según tu entorno o necesidades de seguridad.

---


Se han añadido y ejecutado pruebas adicionales para endurecer la integración con OpenAI y el flujo LLM→tools→ApiProxyService. Resumen:

- `OpenAIClientWireMockIT` (tests de integración usando WireMock):
   - Casos añadidos:
      - Verificación de `tool_calls` presentes en la respuesta.
      - Escenario donde OpenAI devuelve HTTP 500 y se espera excepción específica.
      - Flujo orquestado LLM → tool_call (`call_api`) → `ApiProxyService` (mock): prueba que la tool_call se transforma en llamada a `ApiProxyService` y que su resultado se incorpora en la conversación.

- `OpenAICallApiServiceTest` (unit tests reforzados):
   - Se arregló un test que devolvía NullPointerException al mockear `RestTemplate` para que devuelva siempre un `ResponseEntity` no-nulo.
   - Se añadió comprobación defensiva en `OpenAICallApiService.callChatWithTools` para detectar `ResponseEntity` nula o body vacío y lanzar `IllegalStateException` con mensaje claro (evita NPE y hace los errores más diagnósticos).

- Para evitar dependencias faltantes de Jetty/Servlet con WireMock en tests IT, se añadió la dependencia standalone de WireMock (`wiremock-jre8-standalone`) y la API de servlet en scope test.
- Los castings inseguros en el servicio se substituyeron por `ObjectMapper.convertValue(..., new TypeReference<...(){})` para eliminar warnings y evitar ClassCastException en tiempo de ejecución.

- `ChatServiceTotalsTest` (tests unitarios dirigidos):
   - Caso no paginado (`getTurnosLibres`): el test simula una `tool_call` y que `ApiProxyService` devuelve un array; la respuesta final esperada es la longitud del array (`"2"`).
   - Estos tests garantizan que el flujo LLM → tool_call → ApiProxyService produzca respuestas concisas cuando el usuario pregunta únicamente por un total.

Cambios funcionales relevantes relacionados con estas pruebas:

   - Añadida una regla corta en el `promptBase` que instruye al modelo a usar `page=0,size=1` cuando el endpoint está marcado como `paginated` y a responder SOLO con el número si la pregunta es exclusivamente por el total.
   - Ajustada la lógica interna para no tratar respuestas paginadas (aquellas con `totalElements`, `items`, `page`, `size`) como un "single object" que se renderiza como ficha, permitiendo que el flujo complete la segunda llamada al modelo y devuelva el número correcto.

- `src/main/resources/api-whitelist.yaml`:
   - Añadido el flag `paginated: true` y `aliases` al endpoint `getAlumnos`.
   - Añadidos aliases a `listCollection`.
- Front-end y scripts:
   - `src/components/ChatArea.tsx`: ajuste para normalizar respuestas (forzar `responseType: 'text'` y parsear JSON si procede) y manejar respuestas del backend de forma robusta.
   - Scripts PowerShell en `documentacion/` actualizados (`run-pruebas.ps1`, `run-validation-and-save-flows.ps1`, `flows/Update-FlowsIndex.ps1`) para generar HTML por run, guardar resultados en `documentacion/flows/` y actualizar el índice.

- Ejecuté la suite de tests unitarios tras los cambios: 20 tests ejecutados, BUILD SUCCESS (tests verdes). Esto incluye los nuevos tests `ChatServiceTotalsTest`.

Próximos pasos recomendados (corto plazo):
- Ejecutar una validación E2E levantando la aplicación y preguntando a `/chat` "¿Cuántos alumnos hay?" para verificar el comportamiento con un modelo real o stub (WireMock). Esto requiere clave de OpenAI para pruebas con el servicio real o disponer de un stub en WireMock.
- Añadir un IT que combine WireMock + ChatService para simular la respuesta del modelo con `tool_call` real y validar el flujo completo sin necesidad de acceso a la API de OpenAI.
- Documentar en el README el procedimiento y ejemplos de petición para validar manualmente el caso de totales.

## Comandos ejecutados durante la validación
Estos son los comandos exactos que ejecuté y para qué sirve cada uno. Están escritos para PowerShell (Windows), en el directorio raíz del proyecto `c:\chat-backend-springboot-workers-profesores`.

mvn -DskipITs test
```
Propósito: ejecutar los tests unitarios bajo Surefire y validar cambios rápidos en clases y tests.
2) Ejecutar la suite completa (unit + integration tests):
```powershell
mvn -DskipTests=false verify
```
o equivalente explícito:
```powershell
mvn verify
```
```powershell
mvn -Dtest=com.workers.profesores.chat.service.OpenAICallApiServiceTest test
```
Propósito: ejecutar únicamente el test indicado para depuración rápida.

4) Comandos auxiliares ejecutados mientras depuraba:
```powershell
# Ejecutar sin tests para compilar rápidamente
mvn -DskipTests package

# Ejecutar pruebas y ver salida detallada (sin -q)
mvn -DskipTests=false verify
```

## Qué revisé y por qué

- Revisión de `OpenAICallApiService` para reemplazar impresiones a stderr por SLF4J y usar conversiones seguras con Jackson (TypeReference). Esto reduce ruido y mejora robustez al parsear payloads JSON/YAML.
- Añadí la capacidad de configurar la URL de OpenAI (`openaiApiUrl`) vía `@Value` para poder apuntar a WireMock en ITs.
- Reescribí y amplié `OpenAIClientWireMockIT` para que use WireMockServer en puerto dinámico y simule respuestas deterministas de OpenAI.
- Ajusté `pom.xml` (dependencias de test) y `src/test/resources/application.properties` para que los tests de integración arranquen correctamente en el entorno local.

## Recomendaciones y próximos pasos

- Añadir tests que verifiquen explícitamente las nuevas excepciones defensivas (p. ej. mockear `RestTemplate` para devolver null y esperar `IllegalStateException`).
- Limpiar las supresiones de warnings en tests cuando sea posible.
- Añadir una sección en el README con los comandos para ejecutar únicamente los ITs (por ejemplo, `mvn -DskipTests=false -DfailIfNoTests=false verify -Dgroups=integration` o configuración similar si se desea separar más los scopes).

Si quieres, puedo añadir a este documento ejemplos de salidas relevantes (fragmentos de logs) para cada comando o crear un script PowerShell que ejecute en orden los comandos de validación y guarde los resultados en `documentacion/flows/`.


¿Quieres que añada ejemplos de salida de logs o que genere el script PowerShell que ejecute los comandos en orden y guarde resultados en flows?
