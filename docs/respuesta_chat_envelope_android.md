# Contrato de respuesta del endpoint POST /chat (Android)

Este documento describe la estructura de la respuesta (envelope) que devuelve el backend-chat para la capa de presentación Android.

## Resumen
- La respuesta SIEMPRE es un objeto JSON válido.
- Campo `status` indica si la operación fue correcta (`success`) o falló (`error`).
- Campo `message` contiene un texto breve y humano. Este texto lo proporciona la IA.
- Campo `data` contiene los datos estructurados (listados u objetos), más metadatos de paginación cuando aplique.
- Campo `suggestions` es una lista de sugerencias accionables (opcional), también proporcionadas por la IA.
- Campo `error` aparece solo si `status` es `error`.
- Campo `messages` es una traza opcional con anotaciones técnicas (debug, info) que puede omitirse en UI.

## Estructura

```json
{
  "status": "success",          // "success" | "error"
  "message": "string",           // Texto humano breve (IA). Puede ser vacío si la IA no lo proporcionó.
  "data": {
    "type": "string",            // Tipo de recurso (p. ej. "usuarios", "academias", "cursos", "alumnos", "profesores", "chat", "object")
    "items": [ { ... } ],          // Lista de elementos (array). Puede ser vacío.
    "pagination": {                // Opcional, solo si la respuesta usa paginación
      "page": 1,
      "size": 20,
      "returned": 20,
      "has_more": true,
      "next_page": 2,
      "prev_page": null,
      "total": 132
    },
    "hierarchy": {                 // Reservado para futuras jerarquías (opcional)
    }
  },
  "suggestions": ["string"],      // Opcional. Sugerencias de la IA. Puede ser []
  "error": {                       // Solo si status == "error"
    "code": "string",
    "details": "string"
  },
  "messages": [                    // Traza opcional (debug/info)
    { "level": "debug", "text": "string" }
  ]
}
```

Notas importantes:
- `message` y `suggestions` vienen de la IA. Si la IA no los envía, aparecerán vacíos.
- No se hace "humanización" en Java: el texto mostrado en UI debe ser exactamente el `message` que venga del modelo.
- Para respuestas de "objeto singular" la IA puede devolver una clave singular (p. ej. `academia: { ... }`). El backend lo normaliza a `data.type = "academias"` e `items = [ { ... } ]`.
- Para listados grandes, la IA puede sugerir en `suggestions` acciones como "Siguiente página", "Exportar a CSV".

## Casos típicos

1) Bienvenida simple (sin datos):
```json
{
  "status": "success",
  "message": "Hola Ángel, ¿en qué te ayudo hoy?",
  "data": { "type": "chat", "items": [] },
  "suggestions": []
}
```

2) Listado de academias (no paginado):
```json
{
  "status": "success",
  "message": "Aquí tienes las academias registradas",
  "data": {
    "type": "academias",
    "items": [ {"id":1,"nombre":"Academia X"}, {"id":2,"nombre":"Academia Y"} ]
  },
  "suggestions": []
}
```

3) Listado paginado de usuarios:
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

4) Error de autorización:
```json
{
  "status": "error",
  "message": "No autorizado",
  "error": { "code": "unauthorized", "details": "Token inválido o expirado" },
  "suggestions": []
}
```

## Comportamiento ante respuestas vacías de OpenAI
- Si la primera respuesta del modelo llega vacía o con error, el backend devuelve `status=success` con `message=""` y añade una entrada de debug en `messages`. La UI puede tratar esto como un fallback visual minimalista.

## Recomendaciones para Android
- Mostrar siempre `message` si no está vacío.
- Renderizar `items` según `data.type`. Si el tipo es desconocido, mostrar una lista genérica de pares clave/valor o un JSON plano.
- Si `data.pagination.has_more == true`, ofrecer una acción para pedir la siguiente página.
- `suggestions` son botones de acción opcionales (texto plano) que puedes mapear a intents/acciones UI.
- Ignorar `messages` en UI; son útiles para diagnósticos (logs/desarrolladores).
