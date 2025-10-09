# Android — Ejemplos y contractos de respuesta del backend-chat

Propósito

- Este documento recoge los contratos de respuesta que el *backend-chat* devuelve al cliente Android y ejemplos prácticos de parsing/uso en Kotlin.
- El objetivo es evitar ambigüedades: la app debe esperar siempre JSON (array u objeto) o un objeto que contenga un texto bajo la clave `text`.

Resumen rápido (contrato)

- Respuesta tipo list/array: JSON array de objetos -> `[{...}, {...}]`.
- Respuesta paginada: objeto con metadatos y `items` -> `{"totalElements":123,"items":[...],"page":0,"size":50}`.
- Objeto único: JSON objeto -> `{"id":42,...}`.
- Texto plano: el backend envuelve respuestas de texto en `{"text":"..."}`.
- Errores: el backend normaliza errores como JSON, por ejemplo `{"error":"authorization_failure","message":"..."}`.

Recomendación de contrato para totales

- Para llamadas que devuelven un total explícito, preferir `{"total":123}` o el patrón de agregador `{"totalElements":123,...}`. Esto facilita tests y parsing en Android.

Ejemplos concretos

1) Listado simple (array)

```json
[{"id":1,"nombre":"A"},{"id":2,"nombre":"B"}]
```

2) Paginado (agregador)

```json
{"totalElements":123,"items":[{"id":1,"nombre":"A"},{"id":2,"nombre":"B"}],"page":0,"size":50}
```

3) Objeto único (detalle)

```json
{"id":42,"nombre":"Academia X","direccion":"Calle Falsa 123"}
```

4) Texto plano envuelto

```json
{"text":"Operación realizada correctamente"}
```

5) Error estructurado

```json
{"error":"authorization_failure","message":"Token inválido"}
```

Kotlin — snippets de parsing (Gson)

- Dependencia recomendada: `com.google.code.gson:gson` o `com.squareup.moshi:moshi` o `kotlinx.serialization`.
- Ejemplo con Gson (simple y robusto):

```kotlin
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

sealed class ChatResponse {
    data class ArrayResponse(val items: List<Map<String, Any>>) : ChatResponse()
    data class ObjectResponse(val obj: Map<String, Any>) : ChatResponse()
    data class TextResponse(val text: String) : ChatResponse()
    data class ErrorResponse(val error: String?, val message: String?) : ChatResponse()
}

fun parseChatResponse(raw: String): ChatResponse {
    val gson = Gson()
    val parser = JsonParser.parseString(raw)
    return when {
        parser.isJsonArray -> {
            val list = gson.fromJson(parser.asJsonArray, List::class.java) as List<Map<String, Any>>
            ChatResponse.ArrayResponse(list)
        }
        parser.isJsonObject -> {
            val obj = parser.asJsonObject
            // Error normalizado
            if (obj.has("error") || obj.has("message")) {
                val error = obj.get("error")?.asString
                val message = obj.get("message")?.asString
                return ChatResponse.ErrorResponse(error, message)
            }
            // Texto envuelto
            if (obj.has("text") && obj.entrySet().size == 1) {
                return ChatResponse.TextResponse(obj.get("text").asString)
            }
            // Paginado (forma conocida)
            if (obj.has("items") && obj.has("totalElements")) {
                val items = gson.fromJson(obj.getAsJsonArray("items"), List::class.java) as List<Map<String, Any>>
                return ChatResponse.ArrayResponse(items)
            }
            // Objeto único
            val map = gson.fromJson(obj, Map::class.java) as Map<String, Any>
            ChatResponse.ObjectResponse(map)
        }
        else -> ChatResponse.TextResponse(raw)
    }
}
```

Uso en UI (ejemplo simplificado)

- Para arrays: alimenta un RecyclerView/Adapter con la lista.
- Para paginación: si el backend devuelve `totalElements`+`items`, usa esos valores para controlar paginación local o integrar con Paging 3.
- Para texto: muestra `text` en un Toast o en un TextView.
- Para errores: mostrar diálogo con `message`.

Pager / rendimiento

- Para grandes listados usa Paging 3 (Android Jetpack). El backend expone `/academias/{resource}` con `mode=paged` y parámetros `page`/`size` — integra esta API con un `PagingSource`.
- Si el mediador responde `mode=export` y devuelve un enlace, descarga el fichero CSV/Excel en background y muestra un progreso al usuario.

Tests y mocking

- Para pruebas locales/integration tests puedes usar WireMock o stub del backend. En el repo se usan tests con WireMock para simular OpenAI y respuestas del `ApiProxyService`.

Ejemplo rápido con curl (simular petición al mediador local):

```powershell
# Obtener un detalle (suponiendo el backend corriendo en localhost:8080)
curl -H "Authorization: Bearer <token>" -X POST http://localhost:8080/chat -d '{"messages":[{"role":"user","content":"Dime la lista de academias"}]}' -H "Content-Type: application/json"
```

Buenas prácticas para el equipo Android

- Siempre parsear la respuesta como JSON primero; luego decidir si es array/objeto/text.
- Evitar dependencias en formato libre (Markdown). El backend no devolverá tablas Markdown.
- Estandarizar totales: preferir `{"total":123}` o `{"totalElements":123}`.
 - Documentar y versionar cualquier cambio en el contrato/openapi: el artifact canónico es `served-openapi.json` (publicado desde el repo de la API). No mantengas un `api-whitelist.yaml` localmente: usa la spec para generar documentación y validaciones.

Si queréis, puedo agregar:

- Un archivo `docs/android-parsing-snippets.kt` con utilidades completas y pruebas unitarias de parsing.
- Ejemplos con `kotlinx.serialization` o `Moshi` si los preferís sobre Gson.

---

Fecha: 2025-10-07
