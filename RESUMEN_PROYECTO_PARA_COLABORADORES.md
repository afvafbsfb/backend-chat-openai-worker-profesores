```markdown
# RESUMEN DEL PROYECTO: chat-backend (mediador) para app Android

## Contexto y objetivo (actualizado)
- Este proyecto es un mediador (backend-chat) que recibe peticiones desde la app Android, valida el JWT/OAuth del usuario y, según su rol, orquesta la interacción con OpenAI y la API de academia.
   El flujo es:
   - El backend recibe el chat del usuario y construye el prompt (incluyendo la whitelist de endpoints permitidos).
   - Envía a OpenAI el chat, el prompt y la whitelist; OpenAI responde bien con texto plano (respuesta final) o bien indicando llamadas a la API que debe ejecutar (tool calls).
   - Si OpenAI solicita llamadas a la API, el backend las ejecuta contra la API de academia, recoge las respuestas y se las reenvía a OpenAI cuando sea necesario.
   - OpenAI devuelve entonces el texto o JSON definitivo; el backend aplica el formato necesario y lo entrega al cliente Android (texto plano o JSON sencillo preparado para RecyclerView/TextView).
   - En todo momento el backend aplica las políticas de la whitelist, validación de permisos y saneamiento de parámetros.

- Cliente: App Android. No se enviarán tablas Markdown en las respuestas; las salidas serán siempre JSON (arrays u objetos) o texto plano encapsulado en JSON para que la presentación en Android (RecyclerView/TextView) sea determinista y sencilla.

## Arquitectura recomendada

Android (Bearer access_token) → Backend-Chat (mediador) → OpenAI (function-calling) → API (OAuth) 

## Comandos prácticos (PowerShell) para crear rama, commit y push
Si quieres trabajar en una rama `ampliacion-proyecto` (recomendado) ejecuta en PowerShell desde la raíz del repo:

```powershell
# crear rama local y cambiar a ella
git checkout -b ampliacion-proyecto

# añadir cambios y commitear
git add .
git commit -m "feat(chat): adaptar backend-chat para Android + OAuth (mvp)"

# pushear la rama al remoto
git push -u origin ampliacion-proyecto
```

Si quieres que lo haga yo (crear la rama y pushearla), confírmalo y ejecutaré los comandos.

---

> Este archivo sirve como referencia para colaboradores. Actualízalo tras cualquier cambio relevante en la arquitectura, lógica de negocio o integraciones.
```

## Patrón genérico para listados grandes y exportación de colecciones

Desde agosto de 2025, el backend implementa un **patrón genérico para la agregación y exportación de colecciones** (por ejemplo: alumnos, academias, cursos), siguiendo las mejores prácticas para IA y frontend desacoplado.

Nota práctica sobre rutas y responsabilidades:

- El controller del mediador expone la API genérica bajo el prefijo `/academias` (configuración actual). Los endpoints relevantes son:
  - `GET /academias/{resource}` (modo paged/all/export, parámetro `mode`): listado paginado por defecto; `mode=all` devuelve todos los registros si el total ≤ MAX_ALL (constante `MAX_ALL = 1000` en `CollectionAggregateController`); si el total > MAX_ALL el backend responde `mode="export"` con un enlace de descarga.
  - `GET /academias/{resource}/export`: exporta la colección a CSV en streaming (implementado en el mediador).

- Importante: la API implementadora (`api-workers-profesores`) expone endpoints concretos como `GET /academias` y `GET /usuarios`. El mediador actúa como consumidor de esos endpoints (vía `ApiProxyService`) y ofrece la capa genérica de agregación/export por encima de ellos.

Patrón híbrido IA + backend:

- La IA (OpenAI) decide la intención del usuario (p. ej. `paged`, `all`, `export`) y sugiere el `mode` en la query. El backend valida la intención frente a límites de seguridad y rendimiento: si `mode=all` y el total es pequeño, devuelve los datos; si es grande, fuerza `export` y devuelve un enlace.

Prompt y contexto del usuario (UserClaims):

- El `system prompt` que se envía al modelo ahora incluye un resumen no sensible de `UserClaims` (roles y `academiaId` cuando esté presente). Esto permite que el modelo infiera si un usuario con rol `Admin_plataforma` pretende operar a nivel plataforma o sobre una academia concreta basándose en el contexto de la conversación y las últimas peticiones.
- Regla de ámbito aplicada en el prompt:
   - `Admin_academia` y `Profesor_academia` ⇒ siempre ámbito `academia` (las operaciones deben limitarse a la `academiaId` del claim).
   - `Admin_plataforma` ⇒ por defecto tiene permisos más amplios, pero el modelo debe intentar inferir si la operación se refiere a una academia concreta a partir del contexto. Si es ambiguo, debe pedir confirmación al usuario antes de ejecutar acciones con impacto (crear/borrar/editar) o antes de asumir un `academiaId` para la llamada.
- Nota de seguridad: esta inferencia NO sustituye las comprobaciones del backend. El `ApiProxyService` y `AuthorizationService` siguen siendo la fuente de verdad: todas las llamadas a la API se validan y sanean en el backend antes de ejecutarse.

-Whitelist y nombres prácticos:

- Las entradas de la whitelist deben mapear a los endpoints concretos que la API implementa (por ejemplo `listAcademias`, `listUsuarios`, `getAcademiaById`, `getUsuarioById`). En la documentación se usan nombres conceptuales como `listAll` o `exportCollectionCsv` para explicar el patrón, pero verifica siempre `src/main/resources/api-whitelist.yaml` para ver los nombres exactos y aliases que el mediador acepta.

Prompt de la IA (resumen operativo):

- Si el usuario pide “todos” o “sin paginar”, la IA propone la operación equivalente en la whitelist (p. ej. `listAcademias`/`listUsuarios`).
- Si el flujo queda con `mode="all"`, el mediador devolverá la lista completa solo si el total ≤ MAX_ALL (constante `MAX_ALL = 1000` en `CollectionAggregateController`).
- Si `mode="export"`, el mediador devolverá un mensaje y un enlace de descarga (CSV) hacia `GET /academias/{resource}/export`.
- Para listados normales/paginados, la IA debe sugerir los endpoints específicos (por ejemplo `listUsuarios` o `getAcademiaById`), no intentar paginar/concatenar páginas por su cuenta.
- La agregación de páginas (cuando procede) la realiza el backend; la IA no debe concatenarlas manualmente.

Puntos operativos y ventajas:

- El mediador expone la capa genérica bajo `/academias`:
  - `GET /academias/{resource}` → modes paged/all/export.
  - `GET /academias/{resource}/export` → CSV streaming.
- Seguridad: solo los recursos permitidos en el enum `ResourceType` están disponibles (actualmente `academias` y `usuarios`).
- Escalabilidad: añadir una colección requiere actualizar tanto `ResourceType` como `api-whitelist.yaml`, y asegurarse de que el endpoint exista en `api-workers-profesores`.

Este patrón está documentado en los archivos:
- `/src/main/java/com/workers/profesores/chat/aggregate/ResourceType.java`
- `/src/main/java/com/workers/profesores/chat/aggregate/CollectionAggregateController.java`
- `/src/main/resources/api-whitelist.yaml`
- `/src/main/java/com/workers/profesores/chat/service/ChatService.java` (prompt)


---

## ¿Qué es la whitelist?

La **whitelist** (lista blanca) es un mecanismo de seguridad y control que define explícitamente qué endpoints de la API de academia puede utilizar el asistente ("secretaria") a través del backend de chat.

- Se encuentra en el archivo `/src/main/resources/api-whitelist.yaml`.
- Cada entrada especifica:
   - El nombre lógico del endpoint.
   - El método HTTP permitido (GET, POST, etc.).
   - La ruta y los parámetros permitidos (path, query, body).
   - Una descripción funcional.
- Solo los endpoints y parámetros definidos en la whitelist pueden ser invocados mediante la función `call_api` por el asistente.
- Si el usuario solicita una operación fuera de la whitelist, el asistente debe informar que no está permitido.
- La whitelist se utiliza para construir el prompt dinámico y para validar todas las llamadas a la API, garantizando seguridad y control sobre las operaciones expuestas.

**Importante:** Si se añaden nuevos endpoints o se modifican los existentes, es imprescindible actualizar la whitelist y, si procede, el prompt del sistema para reflejar los cambios.
 
## Esquema de clases y flujo principal

### Clases principales (implementación real)

- ChatController: expone `POST /chat`, valida JWT y crea el logger de flujo (si se solicita). Pasa la petición a `ChatService`.
- ChatService: construye el prompt (incluye la whitelist renderizada), prepara los mensajes y coordina el diálogo. Llama a `OpenAICallApiService` y aplica una normalización mínima de salida para la app Android: devuelve JSON crudo (arrays/objetos) cuando procede o envuelve texto plano en {"text": "..."}. No realiza renderizado a Markdown ni genera fichas en texto para enviar al cliente.
- OpenAICallApiService: gestiona el ciclo de function-calling con OpenAI. Si el modelo devuelve `tool_calls`, procesa los argumentos; para `call_api` valida el endpoint contra `api-whitelist.yaml` y delega la ejecución en `ApiProxyService`. También construye y reinyecta los mensajes `tool` al modelo hasta obtener la respuesta final.
- ApiProxyService: ejecuta las llamadas HTTP permitidas hacia la API de academia (resolución de rutas, headers, autenticación y saneamiento de parámetros). Devuelve siempre resultados normalizados (JSON en errores/control de fallos).
- ToolsDispatcher: ejecuta herramientas internas del backend (operaciones locales como healthChecks u otras funciones que no implican llamada HTTP a la API externa). No sustituye a `OpenAICallApiService` en el loop de function-calling.
- ToolsRegistry: contiene esquemas de funciones (tool schemas) que pueden exponerse al modelo. Puede contener ejemplos legacy; su contenido debe sincronizarse con `api-whitelist.yaml` si se quiere coherencia entre nombres/aliases.
- IntentInterpreterService: servicio opcional para descomponer peticiones complejas en varias acciones mediante LLM.

Nota operativa: el token `Authorization` (Bearer) que recibe `ChatController` se propaga a `ChatService` → `OpenAICallApiService` y puede enviarse a `ApiProxyService` para que las llamadas a la API de academia se ejecuten con el contexto del usuario cuando procede.

### Flujo de funcionamiento (qué hace cada componente)

1. Cliente → `POST /chat` (ChatController). ChatController valida JWT y prepara `RequestFlowXmlLogger` si se pide trazabilidad.
2. ChatController llama a `ChatService.runChat(...)` pasando los mensajes y el `Authorization` si existe.
3. ChatService construye el sistema prompt (incluye la whitelist renderizada por `OpenAICallApiService`) y envía los mensajes a `OpenAICallApiService.callChatWithTools(...)`.
4. OpenAICallApiService llama a OpenAI. Si OpenAI devuelve `tool_calls`:
   - Para `call_api`: extrae argumentos, busca el endpoint en `api-whitelist.yaml` y ejecuta `ApiProxyService.executeWhitelistedCall(...)` (pasando `authorization` cuando procede).
   - Para otras tools internas, puede delegar en `ToolsDispatcher` si corresponde.
   - Construye mensajes `tool` con los outputs y los reinyecta al modelo; repite hasta que no queden tool_calls o se alcance el límite de iteraciones.
5. OpenAICallApiService devuelve la respuesta final (choices/message) a ChatService. Cuando se han ejecutado tool_calls, los outputs de las tools se inyectan como mensajes `tool` y el modelo genera la respuesta final.
6. ChatService normaliza la salida para el cliente móvil: si la respuesta final es JSON válido se entrega tal cual; si es texto plano se envuelve en {"text": "..."}; si una tool devolvió un único objeto no paginado, ChatService puede devolver ese JSON crudo directamente. En ningún caso el mediador transforma la salida a tablas Markdown o fichas en texto destinados al cliente.
7. ChatController devuelve la respuesta JSON al cliente Android.

### Ejemplo de flujo (registro real en los logs)

Un ejemplo real (registrado en `documentacion/flows/*.html`) muestra esta secuencia exacta:

1. ChatController recibe POST `/chat` y crea el logger.
2. ChatService construye prompt y realiza la primera llamada a OpenAI (`OpenAICallApiService`).
3. OpenAI responde con `tool_calls` (ej.: `call_api` con `name=listAcademias`).
4. OpenAICallApiService valida `listAcademias` contra `api-whitelist.yaml` y llama a `ApiProxyService.executeWhitelistedCall`.
5. ApiProxyService efectúa el GET a la API de academia (`/academias`), devuelve JSON; el resultado se añade como mensaje `tool` y se reinyecta al modelo.
6. OpenAI genera la respuesta final (segunda llamada) y OpenAICallApiService la devuelve a ChatService.
7. ChatService post-procesa (p. ej. convierte un objeto en ficha o arregla tablas Markdown) y ChatController envía la respuesta al cliente.

Este ejemplo se refleja en los HTML de `documentacion/flows/` con timestamps por cada paso.

Ejemplos de respuestas esperadas por la app Android

- Listado simple (array):

```json
[{"id":1,"nombre":"A"},{"id":2,"nombre":"B"}]
```

- Paginado (forma usada por el agregador):

```json
{"totalElements":123,"items":[{"id":1,...}],"page":0,"size":50}
```

- Objeto único (detalle):

```json
{"id":42,"nombre":"Academia X","direccion":"..."}
```

- Texto plano generado por el modelo (envuelto):

```json
{"text":"Operación realizada correctamente"}
```

Nota sobre totales y tests

- Para evitar ambigüedades y facilitar la integración con la UI y los tests automáticos, se recomienda un contrato estándar para respuestas de totales: el modelo o el backend deberían devolver {"total":123} o, en el caso de la ruta agregadora, usar {"totalElements":123,...}. Esto simplifica la comprobación en Android y en tests unitarios/IT.
- Si hay tests existentes que esperan un simple texto (por ejemplo "123"), deben actualizarse para esperar JSON (por ejemplo {"text":"123"} o preferiblemente {"total":123}). Alternativamente, el prompt del sistema puede instruir al modelo a devolver JSON estructurado cuando el usuario pregunte por un total.

---

> Este archivo sirve como referencia rápida para desarrolladores y asistentes automáticos. Actualízalo tras cualquier cambio relevante en la arquitectura, lógica de negocio o integración.


---

## Pruebas automáticas E2E y dashboard de flujos

El proyecto incluye un sistema de pruebas automáticas E2E y un dashboard visual para consultar el histórico de ejecuciones y trazas de cada flujo:

- **Ejecución de pruebas:**  
   - Usa el script PowerShell `documentacion/run-pruebas.ps1` para lanzar pruebas automáticas sobre el backend.
   - Cada ejecución genera un identificador de flujo único (`X-Flow-Id`) que se envía en todas las peticiones, permitiendo agrupar y trazar todos los pasos de la prueba.
   - Los logs de cada flujo se guardan en formato XML y HTML en la carpeta `documentacion/flows/`, con el detalle de cada paso (Controller, Service, ApiProxy, etc.).

- **Dashboard de flows:**  
   - El archivo `documentacion/flows/index.html` muestra un índice navegable de todas las ejecuciones recientes, leyendo los datos de `documentacion/flows/flows.json` (actualizado automáticamente por el script de pruebas).
   - Cada fila del dashboard enlaza al HTML detallado de la ejecución correspondiente, permitiendo revisar el timeline completo del flujo.

- **Ejecución y visualización en un clic:**  
   - En la raíz del proyecto tienes el batch `run-pruebas-y-abrir-indice.cmd`.  
      - Ejecuta todas las pruebas E2E.
      - Al terminar, abre automáticamente el dashboard de flows en el navegador (vía backend si está expuesto, o localmente).
   - Así puedes validar el sistema y consultar los logs de cada ejecución de forma visual y sencilla.

- **Notas de despliegue:**  
   - La carpeta `documentacion/flows` debe existir y tener permisos de escritura para el backend y el usuario que ejecuta las pruebas.
   - Si quieres exponer el dashboard vía HTTP, añade en Spring Boot un `ResourceHandler` para `/flows/**` apuntando a `file:documentacion/flows/`.

**Resumen:**  
Este sistema permite validar, auditar y depurar cualquier flujo de negocio o integración, con trazabilidad E2E y acceso visual inmediato a los resultados de cada prueba.


**Variables de entorno y personalización:**

- El script `run-pruebas-y-abrir-indice.cmd` permite definir variables de entorno antes de ejecutar las pruebas:
   - `CHAT_BACKEND_BASE_URL`: URL base del backend (por defecto `http://localhost:8080`).
   - `CHAT_BACKEND_API_KEY`: API key si tu backend la requiere.
   - `CHAT_BACKEND_JWT`: Token JWT si es necesario para autenticación.
- Puedes descomentar y ajustar estas variables al principio del `.cmd` según tu entorno o necesidades de seguridad.
- Así puedes reutilizar el mismo script en local, integración o producción, cambiando solo las variables de entorno.

---

## Nuevas pruebas añadidas (agosto 2025)

Se han añadido y ejecutado pruebas adicionales para endurecer la integración con OpenAI y el flujo LLM→tools→ApiProxyService. Resumen:

- `OpenAIClientWireMockIT` (tests de integración usando WireMock):
   - Casos añadidos:
      - Parseo básico de la respuesta de OpenAI (choices/message/content).
      - Verificación de `tool_calls` presentes en la respuesta.
      - Escenario donde OpenAI devuelve HTTP 500 y se espera excepción específica.
      - Flujo orquestado LLM → tool_call (`call_api`) → `ApiProxyService` (mock): prueba que la tool_call se transforma en llamada a `ApiProxyService` y que su resultado se incorpora en la conversación.

- `OpenAICallApiServiceTest` (unit tests reforzados):
   - Se arregló un test que devolvía NullPointerException al mockear `RestTemplate` para que devuelva siempre un `ResponseEntity` no-nulo.
   - Se añadió comprobación defensiva en `OpenAICallApiService.callChatWithTools` para detectar `ResponseEntity` nula o body vacío y lanzar `IllegalStateException` con mensaje claro (evita NPE y hace los errores más diagnósticos).

Notas técnicas relevantes:
- Para evitar dependencias faltantes de Jetty/Servlet con WireMock en tests IT, se añadió la dependencia standalone de WireMock (`wiremock-jre8-standalone`) y la API de servlet en scope test.
- Los castings inseguros en el servicio se substituyeron por `ObjectMapper.convertValue(..., new TypeReference<...(){})` para eliminar warnings y evitar ClassCastException en tiempo de ejecución.

Adiciones recientes de pruebas y validaciones:

- `ChatServiceTotalsTest` (tests unitarios dirigidos):
   - Caso paginado (`getAlumnos` en la whitelist con `paginated: true`): el test simula que el modelo devuelve un `tool_call` y que `ApiProxyService` devuelve un objeto paginado con `totalElements` — la respuesta final esperada del `ChatService` es SOLO el número total (por ejemplo, `"123"`).
   - Caso no paginado (`getTurnosLibres`): el test simula una `tool_call` y que `ApiProxyService` devuelve un array; la respuesta final esperada es la longitud del array (`"2"`).
   - Estos tests garantizan que el flujo LLM → tool_call → ApiProxyService produzca respuestas concisas cuando el usuario pregunta únicamente por un total.

Cambios funcionales relevantes relacionados con estas pruebas:

- `ChatService.java`:
   - Añadida una regla corta en el `promptBase` que instruye al modelo a usar `page=0,size=1` cuando el endpoint está marcado como `paginated` y a responder SOLO con el número si la pregunta es exclusivamente por el total.
   - Ajustada la lógica interna para no tratar respuestas paginadas (aquellas con `totalElements`, `items`, `page`, `size`) como un "single object" que se renderiza como ficha, permitiendo que el flujo complete la segunda llamada al modelo y devuelva el número correcto.

- `src/main/resources/api-whitelist.yaml`:
   - Añadido el flag `paginated: true` y `aliases` al endpoint `getAlumnos`.
   - Añadidos aliases a `listCollection`.

- Front-end y scripts:
   - `src/components/ChatArea.tsx`: ajuste para normalizar respuestas (forzar `responseType: 'text'` y parsear JSON si procede) y manejar respuestas del backend de forma robusta.
   - Scripts PowerShell en `documentacion/` actualizados (`run-pruebas.ps1`, `run-validation-and-save-flows.ps1`, `flows/Update-FlowsIndex.ps1`) para generar HTML por run, guardar resultados en `documentacion/flows/` y actualizar el índice.

Validación ejecutada:

- Ejecuté la suite de tests unitarios tras los cambios: 20 tests ejecutados, BUILD SUCCESS (tests verdes). Esto incluye los nuevos tests `ChatServiceTotalsTest`.

Próximos pasos recomendados (corto plazo):

- Ejecutar una validación E2E levantando la aplicación y preguntando a `/chat` "¿Cuántos alumnos hay?" para verificar el comportamiento con un modelo real o stub (WireMock). Esto requiere clave de OpenAI para pruebas con el servicio real o disponer de un stub en WireMock.
- Añadir un IT que combine WireMock + ChatService para simular la respuesta del modelo con `tool_call` real y validar el flujo completo sin necesidad de acceso a la API de OpenAI.
- Documentar en el README el procedimiento y ejemplos de petición para validar manualmente el caso de totales.

## Comandos ejecutados durante la validación

Estos son los comandos exactos que ejecuté y para qué sirve cada uno. Están escritos para PowerShell (Windows), en el directorio raíz del proyecto `c:\chat-backend-springboot-workers-profesores`.

1) Ejecutar solo los tests unitarios (rápido):
```powershell
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
Propósito: compilar, ejecutar unit tests (Surefire), empaquetar, y ejecutar integration tests (Failsafe). Usado para validar el comportamiento end-to-end de los ITs (incluyendo WireMock-based tests).

3) Ejecutar un test concreto (ejemplo, solo `OpenAICallApiServiceTest`):
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
