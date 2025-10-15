# Android — Ejemplos y contrato de respuesta unificado (POST /chat)

Este documento está alineado con la versión actual del backend-chat, que devuelve SIEMPRE un envelope JSON unificado en las respuestas de `POST /chat`.

Referencia del contrato: ver `docs/respuesta_chat_envelope_android.md`.

Resumen del envelope

- `status`: "success" | "error"
- `message`: texto humano breve (lo proporciona la IA). Puede venir vacío.
- `data`: `{ type, items, pagination?, hierarchy? }`
- `suggestions`: `["..."]` (opcional, IA)
- `error`: `{ code, details }` (solo si status=error)
- `messages`: trazas técnicas opcionales para diagnóstico

Ejemplos concretos (unificados)

1) Bienvenida sin datos

```json
{
    "status": "success",
    "message": "Hola Ángel, ¿en qué te ayudo hoy?",
    "data": { "type": "chat", "items": [] },
    "suggestions": []
}
```

2) Listado simple (academias)

```json
{
    "status": "success",
    "message": "Aquí tienes las academias registradas",
    "data": {
        "type": "academias",
        "items": [
            {"id":1,"nombre":"Academia X"},
            {"id":2,"nombre":"Academia Y"}
        ]
    },
    "suggestions": []
}
```

3) Listado paginado (usuarios)

```json
{
    "status": "success",
    "message": "Usuarios de tu academia (página 1)",
    "data": {
        "type": "usuarios",
        "items": [ {"id":11,"email":"a@a.com"}, {"id":12,"email":"b@b.com"} ],
        "pagination": {"page":1,"size":20,"returned":2,"has_more":false,"next_page":null,"prev_page":null,"total":2}
    },
    "suggestions": ["Exportar a CSV"]
}
```

4) Objeto singular (normalizado a lista con 1 ítem)

Entrada IA (posible):

```json
{ "academia": { "id": 42, "nombre": "Academia X" } }
```

Salida unificada del backend:

```json
{
    "status": "success",
    "message": "",
    "data": {
        "type": "academias",
        "items": [ { "id": 42, "nombre": "Academia X" } ]
    },
    "suggestions": []
}
```

5) Error estructurado (autorización)

```json
{
    "status": "error",
    "message": "No autorizado",
    "error": { "code": "unauthorized", "details": "Token inválido o expirado" },
    "suggestions": []
}
```

6) Respuesta de OpenAI vacía (fallback controlado)

```json
{
    "status": "success",
    "message": "",
    "data": { "type": "chat", "items": [] },
    "messages": [ { "level": "debug", "text": "raw_first_not_json" } ]
}
```

Kotlin — parsing del envelope (Gson)

Puedes usar Gson, Moshi o kotlinx.serialization. Ejemplo minimal con Gson:

```kotlin
data class Pagination(
        val page: Int?, val size: Int?, val returned: Int?,
        val has_more: Boolean?, val next_page: Int?, val prev_page: Int?, val total: Int?
)

data class DataSection(
        val type: String?,
        val items: List<Map<String, Any?>> = emptyList(),
        val pagination: Pagination? = null
)

data class ErrorInfo(val code: String?, val details: String?)
data class MessageEntry(val level: String?, val text: String?)

data class Envelope(
        val status: String,
        val message: String?,
        val data: DataSection?,
        val suggestions: List<String> = emptyList(),
        val error: ErrorInfo? = null,
        val messages: List<MessageEntry> = emptyList()
)

// Uso: val env = Gson().fromJson(jsonString, Envelope::class.java)
```

Buenas prácticas Android

- Mostrar `message` si no está vacío y reflejar `suggestions` como acciones.
- Renderizar `items` según `data.type`. Si es desconocido, fallback genérico.
- Si `data.pagination.has_more == true`, exponer acción de "siguiente página".
- Ignorar `messages` en UI (son de diagnóstico).

Fecha: 2025-10-15
