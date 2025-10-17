# Segundo turno ligero: descriptor compacto + response_format

Este documento especifica un flujo alternativo para el 2º turno del LLM que reduce drásticamente tokens y latencia sin que el backend decida el copy. La idea: tras ejecutar la tool (call_api) y obtener los datos reales, el backend envía al LLM un descriptor compacto (resumen estructurado) y una instrucción estricta; el LLM devuelve únicamente `text`, `suggestions` y `summary_fields`. El backend fusiona esa salida con los ítems y la paginación reales en el envelope final.

Objetivo
- Mantener: la IA decide el texto, sugerencias y summary_fields.
- Reducir tokens: no reinyectar 20 ítems completos en el 2º turno.
- Evitar errores: restringir el formato de salida con `response_format`.

## Contrato de intercambio

### Descriptor que construye el backend
Objeto JSON breve que describe el resultado de la tool sin incluir la lista completa.

Campos:
- target: "usuarios" | "academias" | "cursos" | "alumnos" | "profesores"
- count: número de ítems devueltos en esta página
- fields_present: array con todas las claves top‑level detectadas en los ítems (solo informar)
- sample_items: 2–3 ejemplos representativos y truncados (sin anidar profundamente); strings recortadas a ~50 caracteres y PII saneada
- pagination: { page, size, returned, has_more, next_page, prev_page, total? }

Ejemplo (usuarios):
```json
{
  "target": "usuarios",
  "count": 20,
  "fields_present": ["id","nombre","email","rol","estado","academia_id","fecha_alta"],
  "sample_items": [
    {"id":1472,"nombre":"Usuario Activo","email":"activo@...","rol":"Admin_plataforma","estado":"Activo"},
    {"id":1473,"nombre":"Usuario Bloqueado","email":"bloqueado@...","rol":"Admin_plataforma","estado":"Bloqueado"}
  ],
  "pagination": {"page":1,"size":20,"returned":20,"has_more":true,"next_page":2,"prev_page":null}
}
```

Notas:
- No contiene la lista completa; solo 2–3 muestras y metadatos.
- `fields_present` informa claves detectadas; el backend no elige relevancia.

### Respuesta esperada del LLM (2º turno)
El LLM debe devolver únicamente:
- text: string (resumen humano breve en castellano)
- suggestions: array de 2–5 strings
- summary_fields: array de 1–2 strings con nombres de claves existentes en los ítems

Instrucción a enviar (castellano, directa y restrictiva):

> Usa únicamente el descriptor anterior para redactar: 1) "text": un resumen humano muy breve; 2) "suggestions": 2–5 próximas acciones (paginación si aplica); 3) "summary_fields": 1–2 claves más relevantes existentes en los ítems. No repitas ni inventes la lista de resultados. No devuelvas arrays de "usuarios"/"academias"/etc. Devuelve únicamente un objeto JSON válido con esas claves.

### response_format JSON / JSON Schema
Para reducir divagaciones y forzar forma exacta, solicitar `response_format`:
- Opción mínima: `json` (objeto JSON)
- Recomendado: `json_schema` con el siguiente esquema

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "text": { "type": "string", "maxLength": 400 },
    "suggestions": {
      "type": "array",
      "items": { "type": "string", "maxLength": 120 },
      "minItems": 2,
      "maxItems": 5
    },
    "summary_fields": {
      "type": "array",
      "items": { "type": "string" },
      "minItems": 1,
      "maxItems": 2
    }
  },
  "required": ["text"]
}
```

## Reglas para construir el descriptor

- target: inferido a partir del nombre del endpoint ejecutado (contiene "usuarios"|"academias"|...). Si no se reconoce, no aplicar este flujo.
- fields_present: unión de claves top‑level presentes en los ítems (hasta, p. ej., los primeros 50 ítems para evitar costes; normalmente solo 20).
- sample_items: tomar 2–3 ítems representativos (los 2 primeros es suficiente). Para cada ítem:
  - Incluir solo claves top‑level con tipos primitivos (string/number/boolean); evitar objetos/arrays anidados.
  - Truncar strings a 50 caracteres.
  - Si una string parece email (regex simple), ofuscar el dominio: `usuario@...`.
  - Evitar valores claramente sensibles (teléfonos completos, DNI, etc.) aplicando truncado similar.
- pagination:
  - page, size: copiar si vienen; si no, null.
  - returned: longitud real de `items` devueltos por la API.
  - has_more: usar el boolean de la API si viene; si no, derivar de `total` y `page*size` cuando sea seguro; si no se puede, dejar null.
  - next_page, prev_page, total: copiar si vienen, si no, null.

Edge cases:
- count=0: `sample_items` vacío; `fields_present` vacío; el LLM debe responder con texto tipo "No hay resultados" y sugerencias alternativas.
- No paginado: omitir `pagination` o incluir solo `returned`.
- Múltiples tools: no aplicar este flujo; usar el 2º turno normal.

## Integración en ChatService (sin cambios de código aún)

Condición de uso:
- Exactamente 1 tool_call ejecutada, y el resultado es un listado conocido (`target` reconocible).
- Si no se cumple, mantener el flujo actual (2º turno normal) o fast‑path si está permitido para ese endpoint.

Pasos (propuestos):
1) Tras ejecutar la tool, construir `Descriptor` desde `ExecMeta`:
   - `buildCompactDescriptor(ExecMeta meta) -> ObjectNode`
2) Preparar el 2º turno con mensajes:
   - seed original + assistant echo (con tool_calls) + mensaje de usuario con la instrucción restrictiva + el descriptor serializado.
   - Incluir `response_format=json_schema` con el esquema anterior.
3) Parsear la respuesta del LLM `{text,suggestions,summary_fields}`.
4) Fusión para el envelope final:
   - `data.items` y `data.pagination`: de la tool (datos reales).
   - `message`, `suggestions`, `data.summaryFields`: de la respuesta del LLM.

Pseudocódigo:
```java
ExecMeta meta = executed.get(0);
ObjectNode desc = buildCompactDescriptor(meta);
List<Message> followup = new ArrayList<>(seed);
followup.add(assistantEchoWithToolCalls);
followup.add(User("Instrucción estricta + descriptor:" + desc.toString()));
JsonNode resp = openai.callChatWithTools(followup, responseFormatJsonSchema);
ObjectNode lite = (ObjectNode) respTextToJson(resp);
ResponseEnvelope out = envelopeFrom(meta.apiResultNode /*items+pagination*/);
out.setMessage(lite.text);
out.setSuggestions(lite.suggestions);
out.getData().setSummaryFields(lite.summary_fields);
return out;
```

### Criterios de elegibilidad (isListKnownTarget)

Usar el segundo turno ligero cuando, tras ejecutar las tools, se cumpla todo:

- tools.size == 1
- Método GET (listado “safe”)
- Respuesta sin error
- Target reconocible e items detectables

Detección del target (orden de preferencia):

1) Por nombre del endpoint (más fiable): último match en el nombre entre
   `usuarios | academias | cursos | alumnos | profesores`.
   - Si el endpoint contiene varios (p.ej. `usuarios/{id}/cursos`), usar el último (`cursos`).
2) Por estructura de la respuesta:
   - Si existe una clave con array exacta entre `[usuarios, academias, cursos, alumnos, profesores]`, ésa es el target.
   - Si existe `items` (array) genérico, usar el target inferido por el nombre del endpoint.
   - Caso especial academias: `{ ok: true, result: [...] }` con endpoint que contenga “academias”.

Detección de listado e `items`:

- Prioridad de arrays candidata a lista:
  1. `node[target]` si es array
  2. `node["items"]` si es array
  3. Si `target == "academias"` y `node["result"]` es array, usar `result`

Condiciones mínimas para aplicar “lite”:

- `items != null` y (`items.size() >= 0`) y (vacío permitido) y
  (o bien `items` está vacío o su primer elemento es objeto)
- `returned` determinable: `items.size()` o `pagination.returned`

Fallback automático:

- Si falla cualquiera de las condiciones (multi-tools, no-GET, target no reconocible, sin array, error), saltar al 2º turno normal (o fast‑path si estuviera explícitamente permitido) sin aplicar el flujo “lite”.

## Telemetría y flags
- Nuevo flag: `chat.secondTurnLite.enabled` (por defecto true).
- Métricas: tiempo de OpenAI 1, proxy, OpenAI 2, tamaño (tokens estimados) de followup normal vs lite.
- Convivencia con fast‑path: si el endpoint está marcado explícitamente como fast‑path (p. ej., por metadatos `x-fastpath` en `served-openapi.json`), aplicar fast‑path; en caso contrario, aplicar segundo turno ligero cuando proceda.

## Pruebas
- Unit: `buildCompactDescriptor` (usuarios/academias) con casos: normal, strings largas, emails, vacío.
- Integration: flujo completo welcome → usuarios p1 → segundo turno lite → envelope fusionado (assert de items preservados y copy proveniente del 2º turno).
- Regression: comparar latencia y tamaño del mensaje del 2º turno normal vs lite (tokens estimados).

## Notas de seguridad/privacidad
- No enviar PII completa en `sample_items` (ofuscar emails y truncar strings).
- No incluir estructuras anidadas grandes.
- `fields_present` es informativo, no revela valores.

## Próximos pasos (propuestos)
1) Implementar `buildCompactDescriptor(ExecMeta)` y la rama de 2º turno ligero en `ChatService` (detrás del flag).
2) Añadir soporte de `response_format` (`json`/`json_schema`) en `OpenAICallApiService` para el 2º turno.
3) Añadir telemetría y pruebas.
4) Evaluar metadatos `x-fastpath` en `served-openapi.json` para declarar endpoints aptos para fast‑path; el resto usaría este flujo ligero.