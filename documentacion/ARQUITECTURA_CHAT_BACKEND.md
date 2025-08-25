# Resumen de arquitectura y flujo del backend de chat

## ¿Qué es este backend?
- Backend de chat que expone una API para interactuar con OpenAI (GPT-4o, etc).
- Permite a clientes externos enviar mensajes y recibir respuestas de OpenAI.
- OpenAI puede, mediante function calling, decidir si se debe invocar lógica adicional (por ejemplo, consultar datos en el sistema Academia).
- Este backend es un chat para hablar con OpenAI desde fuera de OpenAI, con integración segura a sistemas internos.

## Flujo principal
1. El usuario (cliente externo) envía un mensaje al backend usando el endpoint de chat.
2. El backend reenvía el mensaje a OpenAI usando la API de OpenAI (con soporte para function calling).
3. OpenAI responde:
   - Puede devolver solo texto (respuesta directa).
   - O puede devolver una instrucción para llamar a una función (por ejemplo, consultar datos en Academia).
4. El backend detecta si OpenAI solicita una función:
   - Si es así, el backend llama al API de Academia (u otro sistema) usando el cliente correspondiente.
   - Para esto, el backend carga automáticamente un archivo YAML con la especificación del API de Academia (por ejemplo, OpenAPI/Swagger) al arrancar.
   - Esta especificación se utiliza para registrar y exponer a OpenAI las funciones disponibles, sus parámetros y descripciones.
   - Así, OpenAI puede decidir cuándo y cómo invocar esos métodos, y el backend sabe cómo mapear y ejecutar la llamada real al API de Academia.
   - El resultado de esa llamada se reinyecta en la conversación con OpenAI, que puede generar una respuesta final más informada.
5. El backend devuelve la respuesta final al usuario.

## Componentes principales
- **ChatController**: Expone el endpoint REST para el chat.
- **ChatService**: Orquesta la lógica de conversación, reenvío a OpenAI y gestión de function calling.
- **OpenAIClient**: Cliente HTTP para interactuar con la API de OpenAI.
- **AcademiaClient**: Cliente HTTP para interactuar con el API de Academia.
- **ToolsDispatcher / ToolsRegistry**: Gestionan el registro y la ejecución de funciones que OpenAI puede invocar (function calling).
- **DTOs y modelos**: Estructuras para mensajes, requests y respuestas.

## Seguridad y control
- **Whitelist**: Controla qué funciones/endpoints pueden ser llamados por OpenAI.
- **Flags de configuración**: Permiten habilitar/deshabilitar features o integraciones.

## Resumen funcional
- Este backend es un proxy inteligente entre clientes externos y OpenAI, con capacidad de extender la conversación a sistemas internos (como Academia) bajo control y seguridad.
- El usuario nunca interactúa directamente con OpenAI ni con Academia, siempre lo hace a través de este backend.

---

*Actualizado: 2025-08-24*
