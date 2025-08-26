# RESUMEN DEL PROYECTO: chat-backend-springboot-workers-profesores

## Estado actual
- Backend Spring Boot funcionando como intermediario entre el frontend del chat y la API de academia.
- Lógica de paginación y presentación de resultados gestionada íntegramente en el backend y el prompt del asistente ("secretaria").
- El frontend del chat solo muestra la respuesta recibida, sin lógica de paginación propia.

## Decisiones clave
- **Paginación:** Solo el endpoint `/vlodeiro/secretaria/alumnos` soporta paginación (parámetros `page` y `size`). El resto de endpoints devuelven todos los resultados.
- **Prompt del asistente:**
  - Si el usuario pide solo el total de registros, se responde solo con el número.
  - Si pide la lista y el total, se muestran ambos.
  - Listados grandes: solo los primeros 50 registros, con aviso y opción de pedir "siguiente".
  - Exportaciones: solo se muestra el enlace de descarga, nunca el contenido del archivo.
- **Frontend:** No debe implementar lógica de paginación ni manipular directamente los parámetros de la API de academia.

## Cómo contribuir o continuar
- Mantener la lógica de negocio y presentación en el backend.
- Si se añaden nuevos endpoints con paginación, actualizar el prompt y la whitelist.
- Documentar cualquier cambio relevante en este archivo.

## Documentación útil
- `/documentacion/run-pruebas.ps1`: script de pruebas automáticas.
- `/src/main/resources/api-whitelist.yaml`: endpoints permitidos y parámetros.
- `/src/main/java/com/workers/profesores/chat/service/ChatService.java`: lógica principal y prompt del sistema.

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
- **ApiProxyService**: Realiza llamadas HTTP a la API de academia, resolviendo rutas, parámetros y autenticación.
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
