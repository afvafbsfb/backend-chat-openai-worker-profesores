```markdown
# RESUMEN DEL PROYECTO: chat-backend (mediador) para app Android

## Contexto y objetivo (actualizado)
- Este proyecto es un mediador (backend-chat) que recibe peticiones desde la app Android, valida el JWT/OAuth del usuario y, según su rol, consulta la API de academia o responde localmente.
- Cliente: App Android (solo pantalla de login por ahora). No se enviarán tablas Markdown en las respuestas; las respuestas serán texto plano o listas sencillas (JSON) pensadas para renderizar en Android (RecyclerView o TextView).

## Arquitectura recomendada

Android (Bearer access_token) → Backend-Chat (mediador) → API (OAuth) → OpenAI (function-calling)

... (mismo contenido que RESUMEN_PROYECTO.md, adaptado para colaboradores)

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

Desde agosto de 2025, el backend implementa un **patrón genérico para la agregación y exportación de cualquier colección** (alumnos, tarifas, empresas, turnos, etc.), siguiendo las mejores prácticas para IA y frontend desacoplado:

- **Endpoints genéricos:**
   - `/vlodeiro/secretaria/{resource}/all`: Devuelve todos los registros de la colección indicada (`resource`), si el total es ≤ 1000. Si hay más de 1000, responde con `mode="export"` y un enlace de descarga.
   - `/vlodeiro/secretaria/{resource}/export`: Exporta la colección a CSV en streaming.
- **Patrón híbrido IA + backend:** La IA interpreta el lenguaje natural y decide la intención (paged, all, export) a través del parámetro `mode` en la query. El backend valida y ajusta según límites de seguridad, coste y rendimiento: si la petición es razonable (por ejemplo, “all” con pocos registros), la cumple; si no, fuerza “export” para evitar problemas de rendimiento o abuso. Así, la IA propone y el backend dispone, garantizando flexibilidad, seguridad y experiencia de usuario homogénea.
- **Whitelist:** Ambos endpoints están definidos en `api-whitelist.yaml` como `listAll` y `exportCollectionCsv`.
- **Prompt de la IA:**
   - Si el usuario pide “todos” o “sin paginar”, la IA usa `listAll`.
   - Si la respuesta es `mode="all"`, muestra la tabla completa.
   - Si es `mode="export"`, solo muestra el mensaje y el enlace de descarga.
   - Para listados normales, usa los endpoints específicos (ej. `getAlumnos`).
   - Nunca concatena páginas manualmente: la agregación la hace el backend.
- **Ventajas:**
   - Código único y mantenible para todas las colecciones.
   - El frontend sigue simple y desacoplado.
   - Seguridad: solo recursos permitidos en el enum `ResourceType`.
   - Escalabilidad: añadir una nueva colección solo requiere actualizar el enum y la whitelist.

Este patrón está documentado en los archivos:
- `/src/main/java/com/workers/profesores/chat/aggregate/ResourceType.java`
- `/src/main/java/com/workers/profesores/chat/aggregate/CollectionAggregateController.java`
- `/src/main/resources/api-whitelist.yaml`
- `/src/main/java/com/workers/profesores/chat/service/ChatService.java` (prompt)

Ante cualquier duda, consulta estos archivos o pregunta a los responsables del backend.

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

### Clases principales

- **ChatController**: Recibe las peticiones HTTP POST `/chat` del frontend. Extrae el mensaje del usuario y lo pasa a `ChatService`.
- **ChatService**: Orquesta el flujo principal. Construye el prompt, prepara los mensajes, llama a OpenAI y procesa la respuesta. Si es necesario, realiza llamadas a la API de academia a través de `ApiProxyService`.
- **IntentInterpreterService**: (opcional) Interpreta la intención del usuario usando OpenAI para descomponer peticiones complejas en acciones concretas.
- **OpenAICallApiService**: Gestiona la comunicación con OpenAI, renderiza la whitelist y ayuda a construir los mensajes para el modelo.
- **ApiProxyService**: Realiza llamadas HTTP a la API de academia, resolviendo rutas, parámetros y autenticación. ApiProxyService → es tu cliente que sabe llamar a la API de academia (resuelve URL, headers, auth)
- **ToolsDispatcher**: Ejecuta las "tool calls" generadas por OpenAI, llamando a los métodos apropiados del backend o la API.
- **ToolsRegistry**: Define los esquemas y metadatos de las herramientas/endpoints disponibles para el asistente.

### Flujo de funcionamiento

1. **El usuario envía un mensaje** desde el frontend al endpoint `/chat`.
2. **ChatController** recibe la petición y la pasa a **ChatService**.
3. **ChatService**:
   - Construye el prompt dinámico (con reglas, whitelist, etc.).
   - Prepara los mensajes para OpenAI.
   - Llama a **OpenAICallApiService** para obtener la respuesta del modelo.
   - Si OpenAI solicita una "tool call" (ej: `call_api`), delega en **ToolsDispatcher**.
   - **ToolsDispatcher** ejecuta la acción (ej: consulta a la API de academia vía **ApiProxyService**).
   - El resultado se procesa y se devuelve al usuario, formateado según las reglas del prompt.
4. **IntentInterpreterService** puede intervenir para descomponer peticiones complejas en varias acciones.
5. **El usuario recibe la respuesta** ya procesada y formateada.

### Ejemplo de flujo (según logs de pruebas)

1. `ChatController`: Recibe POST `/chat` con mensaje del usuario.
2. `ChatService`: Inicia el flujo, construye el prompt, añade la whitelist.
3. `OpenAICallApiService`: Llama a OpenAI, recibe respuesta (puede incluir tool_calls).
4. Si hay tool_call:
   - `ToolsDispatcher` ejecuta la acción solicitada.
   - `ApiProxyService` realiza la llamada real a la API de academia.
   - El resultado se devuelve a `ChatService` y se formatea.
5. `ChatController`: Devuelve la respuesta final al frontend.

Este flujo está reflejado en los archivos HTML de la carpeta `documentacion/`, donde cada paso queda registrado con timestamp y descripción.

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
