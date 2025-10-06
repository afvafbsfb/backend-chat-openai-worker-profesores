# RESUMEN DEL PROYECTO: chat-backend (mediador) para app Android

## Contexto y objetivo (actualizado)
- Este proyecto es un mediador (backend-chat) que recibe peticiones desde la app Android, valida el JWT/OAuth del usuario y, según su rol, consulta la API de academia o responde localmente.
- Cliente: App Android (solo pantalla de login por ahora). No se enviarán tablas Markdown en las respuestas; las respuestas serán texto plano o listas sencillas (JSON) pensadas para renderizar en Android (RecyclerView o TextView).

## Arquitectura recomendada

Android (Bearer access_token) → Backend-Chat (mediador) → API (OAuth) → OpenAI (function-calling)

- Secuencia mínima segura:
  1. Android: envía Authorization: Bearer <access_token> al backend-chat.
  2. Backend-Chat: valida firma/exp/claims del token localmente (no necesita consultar DB para cada petición).
  3. Si rol == Admin_plataforma → Backend-Chat llama a GET /academias en la API usando el mismo access_token (o, en fase avanzada, usando worker_token mediante exchange interno).
  4. Admin_academia / Profesor_academia → Backend-Chat devuelve una respuesta localizada de bienvenida usando datos de claims (academy_id / profesor_id).
  5. Opcional: Backend-Chat llama a OpenAI para orquestación avanzada (function-calling) y usa tools que llaman a la API.

## Reglas operativas clave
- Nunca enviar tokens a OpenAI ni incluirlos en prompts o logs.
- No usar tablas Markdown en las respuestas: el backend devolverá JSON o texto plano adecuado para Android.
- Cuando la API responde 401/403, el backend reenvía 401/403 a Android para que el cliente haga refresh o re-login.
- El backend respeta claims del token para filtrar (`roles`, `academia_id`, `profesor_usuario_id`).

## Endpoints mínimos sugeridos (MVP)
- POST /chat : entrada única del chat; recibe {"mensaje": "..."} y Authorization header.
- GET /internal/worker-token (opcional, interno): devuelve worker_token TTL corto para llamadas a la API con privilegios restringidos.
- (Necesarios en la API que ya existe) GET /academias, GET /calendario/academia?fecha=YYYY-MM-DD, GET /calendario/mis-cursos?fecha=YYYY-MM-DD

## Flujo rápido por rol (MVP sin OpenAI)
- Admin_plataforma: POST /chat devuelve la lista de academias (propia llamada a GET /academias).
- Admin_academia: POST /chat devuelve "Bienvenido a <nombre_academia>" usando `academia_id` del token.
- Profesor_academia: POST /chat devuelve "Bienvenido a <nombre_academia>" (y opcionalmente su id_profesor) usando claims.

## Mejoras posteriores (fácil iteración)
- Añadir OpenAI / function-calling con 1 tool: listar_academias() → GET /academias.
- Implementar endpoint interno /internal/worker-token para emitir tokens efímeros (TTL 1–5 min) con scopes mínimos; cambiar llamadas al API a usar worker_token.
- Añadir logs de auditoría por petición y por tool-call.

## Qué verificar antes de empezar
1. Que los access_tokens que emite tu API incluyan los claims: `roles` (lista), `academia_id` (int cuando aplique) y `profesor_usuario_id` (opcional para profesor).
2. Que el endpoint GET /academias exista y acepte el Bearer token.

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

## Siguiente paso recomendado (rápido)
- Implementar un esqueleto mínimo del backend-chat (FastAPI o Spring Boot). Si quieres, te doy el esqueleto en la siguiente respuesta:
  - POST /chat (valida JWT, switch por rol, llama a API o devuelve bienvenida)
  - README con cómo probar desde Android (cURL / Postman)

---

Actualiza este archivo si cambiamos autoría del proyecto o el público objetivo (ej. añadir otro cliente distinto a Android).
