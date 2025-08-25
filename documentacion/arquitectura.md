# Arquitectura de la Solución - Back Office Chat (Spring Boot)

## Situación actual
- El back office está desarrollado en Spring Boot (Java).
- La web externa envía mensajes al back office, que orquesta llamadas a OpenAI (tu “secretaria”) y a la API de la academia.
- El objetivo es replicar el comportamiento de la “secretaria” de ChatGPT, pero desde tu propia web, usando function calling y herramientas oficiales de OpenAI.

## Propuesta de arquitectura

### 1. Frontend (tu web)
- Envía mensajes al endpoint `/chat` del back office.

### 2. Back Office (Spring Boot)
- Recibe mensajes y los envía a OpenAI con tools definidas (function calling).
- Si el modelo pide una acción (tool_call), ejecuta la llamada real a la API de la academia.
- Devuelve el resultado al modelo y finalmente la respuesta al frontend.

### 3. API Academia
- Backend real con los datos de alumnos, pagos, etc.

## Flujo de una petición (end-to-end)
1. La web envía `{messages:[...]}` → `/chat` (Spring Boot).
2. Spring envía la conversación a OpenAI con tools declaradas (por ejemplo, getAlumnoById, listAlumnos, crearPago, etc.).
3. El modelo puede responder directo o solicitar una tool_call (función).
4. Si hay tool_call, Spring:
   - Ejecuta la llamada real contra API Academia.
   - Devuelve el resultado al modelo como tool output.
   - Pide al modelo que redacte la respuesta final para el usuario.
5. Spring responde al frontend (streaming o no-streaming).

## Estructura mínima recomendada
```
backoffice-chat/
 ├─ src/main/java/com/tuempresa/chat
 │   ├─ controller/ChatController.java
 │   ├─ service/ChatService.java
 │   ├─ service/ToolsDispatcher.java
 │   ├─ client/OpenAIClient.java
 │   ├─ client/AcademiaClient.java
 │   ├─ model/openai/*   (DTOs para requests/responses)
 │   └─ config/AppConfig.java (WebClient, CORS, etc.)
 └─ application.yml
```

## Siguientes pasos recomendados
1. Definir tools (acciones) en el back office: ejemplo, getAlumnoById, listAlumnos, etc.
2. Implementar el bucle de tool calling:
   - Detectar si OpenAI pide una función.
   - Ejecutar la función real.
   - Devolver el resultado como tool output y pedir al modelo que redacte la respuesta final.
3. Configurar clientes HTTP:
   - Uno para OpenAI (con tu API Key).
   - Otro para la API de la academia (con API Key/JWT si es necesario).
4. Exponer el endpoint `/chat` para la web.
5. (Opcional) Añadir streaming/SSE para experiencia tipo ChatGPT.

## Notas de seguridad y extensibilidad
- CORS: permite tu dominio web.
- API Keys en application.yml y variables de entorno.
- Autenticación: token de sesión del usuario de tu web → tu Back Office valida y solo entonces llama al LLM/Academia.
- Rate limiting y quotas por usuario.
- Observabilidad: logs por conversación, tool calls, latencias a API Academia.
- Streaming (Fase II): expón `/chat/stream` con SSE (WebFlux) para tokens al vuelo.
- Más destinos: añade nuevos ToolSchema y nuevos clientes (Stripe, Gmail, “GPT-5 con web search”, etc.).
- ToolsDispatcher es el punto único de orquestación.

---

Este documento resume la arquitectura y los siguientes pasos para la integración del back office de chat con OpenAI y la API de la academia.


# nueva arquitectura y enfoque 23/08/25
Ese enfoque es excelente y es el más simple, robusto y escalable para tu caso! Es el patrón “function calling passthrough” moderno, y es exactamente como funcionan los plugins oficiales de ChatGPT y los asistentes avanzados.

Ventajas de este enfoque:
Solo una tool genérica (call_api): No necesitas definir 20 funciones, solo una, y la IA decide cómo y cuándo llamarla.
Lista blanca de endpoints: Seguridad total, solo se permite lo que tú defines.
El modelo razona y decide: La IA interpreta el mensaje, decide qué endpoint usar, con qué método y parámetros, y tu backend solo ejecuta y valida.
Respuesta natural: El modelo puede combinar varias llamadas y redactar la respuesta final, igual que ChatGPT Plugins.
Fácil de mantener y extender: Añadir un endpoint es solo añadirlo a la whitelist YAML, no tocar código Java.
¿Quieres que te genere el esqueleto Spring Boot para este flujo?
Incluye:

Endpoint /chat que reenvía el mensaje y la tool call_api al modelo.
Whitelist YAML de endpoints permitidos.
Validación y ejecución HTTP segura.
Cliente OpenAI (openai-java).
Manejo de tool_call y tool_output.
Ejemplo de system prompt y tabla de endpoints.
¿Te lo genero ya, “modo mínimo”, para que puedas probarlo y luego iteramos? (¡Es la arquitectura más moderna y productiva para bots conectados a APIs!)

---

## Detalle: ¿Cómo y dónde se carga el YAML de la API de Academia?

El backend carga automáticamente la whitelist de endpoints permitidos para function calling desde el archivo `api-whitelist.yaml` ubicado en `src/main/resources`. Este archivo define los endpoints que la IA puede invocar mediante la tool genérica `call_api`.

### Proceso real de carga (código Java)

El servicio `OpenAICallApiService` es responsable de cargar y parsear el YAML al iniciar la aplicación. El fragmento relevante es:

```java
public OpenAICallApiService(InputStream whitelistInputStream) {
   try {
      ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
      InputStream is = whitelistInputStream != null ? whitelistInputStream : new ClassPathResource("api-whitelist.yaml").getInputStream();
      Map<String, Object> yaml = mapper.readValue(is, new TypeReference<Map<String, Object>>(){});
      Object endpointsObj = yaml.get("endpoints");
      whitelist = new ArrayList<>();
      if (endpointsObj instanceof List<?>) {
         for (Object item : (List<?>) endpointsObj) {
            if (item instanceof Map) {
               @SuppressWarnings("unchecked")
               Map<String, Object> endpoint = (Map<String, Object>) item;
               whitelist.add(endpoint);
            }
         }
      }
   } catch (Exception e) {
      whitelist = List.of();
      System.err.println("[ERROR] No se pudo cargar la whitelist: " + e.getMessage());
   }
}
```

**Explicación paso a paso:**

1. Al instanciarse el servicio, se crea un `ObjectMapper` con soporte para YAML (`YAMLFactory`).
2. Se abre el archivo `api-whitelist.yaml` desde el classpath (`src/main/resources`).
3. Se parsea el YAML a un `Map<String, Object>`.
4. Se extrae la lista de endpoints bajo la clave `endpoints`.
5. Cada endpoint se añade a la lista interna `whitelist`.
6. Si ocurre algún error, la whitelist queda vacía y se imprime un error.

De este modo, la whitelist de endpoints está disponible en memoria y se utiliza para exponer las tools a OpenAI y validar las llamadas entrantes.

---