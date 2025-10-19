# Políticas de paginación y tokens de navegación

## Defaults y límites
- `size` por defecto: 50
- `size` máximo: 50
- `page` mínimo: 1
- Si `returned >= size` se asume `has_more = true` cuando la API no lo indica.

## Token de contexto (contextToken)
El token encapsula el contexto de navegación para construir correctamente la siguiente petición sin exponer filtros completos al LLM.

Campos típicos del token (conceptual):
- `endpoint`: operationId (p.ej., `usuarios.listar_usuarios`)
- `method`: HTTP (p.ej., `GET`)
- `pathParams`: objeto con path params
- `queryBase`: query sin `page`/`size`
- `page`: página objetivo
- `size`: tamaño de página

El token se serializa (por ejemplo, en base64url) y se incluye en `uiSuggestions[].contextToken`.

## Sugerencias de navegación
- Siempre que `data.pagination` exista, el servidor genera sugerencias:
  - Primera página: solo `Siguiente`
  - Páginas intermedias: `Anterior` y `Siguiente`
  - Última página: solo `Anterior`
- Cada sugerencia incluye `pagination.direction` y el `contextToken` para el salto.

## Política "todos"
- Las peticiones tipo "todos" se satisfacen con paginado conservador. Si se requiere un total, el servidor puede hacer un `lazy total` limitado con estrategia exponencial+binaria.
