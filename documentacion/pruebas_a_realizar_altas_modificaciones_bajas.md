# Pruebas E2E para Altas, Modificaciones y Bajas con uiContext

## Objetivo
Validar el comportamiento del backend-chat cuando recibe solicitudes de creación, modificación y eliminación de tarifas, considerando:
- Diferentes roles de usuario (Admin_plataforma, Admin_academia, Profesor_academia)
- Presencia/ausencia de contexto UI (academia o tarifa seleccionada)
- Permisos y ámbitos según el rol

## Contexto Técnico

### Feature uiContext (pendiente de implementar)

**Estructura del ChatRequest:**
```json
{
  "messages": [
    {"role": "user", "content": "crea una tarifa nueva"}
  ],
  "contextToken": "...",
  "uiContext": {
    "selectedAcademia": {
      "id": 308,
      "nombre": "Academia de Idiomas Madrid"
    },
    "selectedTarifa": {
      "id": 15,
      "nombre": "Tarifa Premium"
    }
  }
}
```

**Inyección en System Prompt:**
```
[CONTEXTO UI]: El usuario tiene seleccionada la academia 'Academia de Idiomas Madrid' (ID: 308).
```

Esto permite a OpenAI generar tool_calls con `academia_id: 308` sin preguntar al usuario.

---

## Escenarios de Prueba

### Archivo: `tests/chat/test_mediator_chat_alta_tarifa.py`

#### TEST 1: Admin_plataforma CON academia seleccionada (HAPPY PATH)

**Dado:**
- Usuario con rol: `Admin_plataforma`
- Academia seleccionada en UI: ID 308, "Academia de Idiomas Madrid"

**Cuando:**
- Usuario envía: "crea una tarifa nueva con descripción 'Tarifa Premium' y precio 80 euros"
- Request incluye:
  ```json
  {
    "uiContext": {
      "selectedAcademia": {"id": 308, "nombre": "Academia de Idiomas Madrid"}
    }
  }
  ```

**Entonces:**
- Backend-chat inyecta en system prompt: `[CONTEXTO UI]: Usuario seleccionó academia 'Academia de Idiomas Madrid' (ID: 308).`
- OpenAI genera tool_call:
  ```json
  {
    "name": "tarifas.crear_tarifa",
    "arguments": {
      "academia_id": 308,
      "descripcion": "Tarifa Premium",
      "precio_base": 80.0
    }
  }
  ```
- Backend-chat llama a `POST /tarifas` con JWT de Admin_plataforma
- API crea tarifa y devuelve 201 Created
- Chat responde: "✅ He creado la tarifa 'Tarifa Premium' (80€) para la academia 'Academia de Idiomas Madrid'."

---

#### TEST 2: Admin_plataforma SIN academia seleccionada (DEBE PREGUNTAR)

**Dado:**
- Usuario con rol: `Admin_plataforma`
- No hay academia seleccionada en UI

**Cuando:**
- Usuario envía: "crea una tarifa nueva"
- Request NO incluye `uiContext.selectedAcademia`

**Entonces:**
- Backend-chat NO inyecta contexto de academia
- OpenAI detecta que falta `academia_id` (requerido para Admin_plataforma)
- OpenAI responde: "¿Para qué academia quieres crear la tarifa? Necesito el nombre o ID de la academia."
- Chat NO genera tool_call hasta que usuario especifique la academia

---

#### TEST 3: Admin_academia (NO necesita selección, usa JWT)

**Dado:**
- Usuario con rol: `Admin_academia`
- JWT incluye: `academia_id: 42`
- Academia seleccionada en UI: ID 308 (diferente a la del JWT)

**Cuando:**
- Usuario envía: "crea una tarifa de 50 euros"
- Request incluye `uiContext.selectedAcademia: {id: 308}`

**Entonces:**
- Backend-chat IGNORA uiContext para Admin_academia (no tiene permisos sobre academia 308)
- OpenAI genera tool_call:
  ```json
  {
    "name": "tarifas.crear_tarifa",
    "arguments": {
      "descripcion": "Tarifa de 50 euros",
      "precio_base": 50.0
    }
  }
  ```
- Backend-chat llama a `POST /tarifas` SIN `academia_id` en body (API usa JWT)
- API valida que Admin_academia crea tarifa para su propia academia (ID 42)
- Respuesta: "✅ He creado la tarifa 'Tarifa de 50 euros' (50€) para tu academia."

---

#### TEST 4: Profesor_academia (DENEGADO)

**Dado:**
- Usuario con rol: `Profesor_academia`
- Academia seleccionada en UI: ID 308

**Cuando:**
- Usuario envía: "crea una tarifa nueva"

**Entonces:**
- OpenAI detecta que `Profesor_academia` NO tiene permiso `tarifas.crear_tarifa` (x-permissions en OpenAPI)
- Chat responde: "❌ No tienes permisos para crear tarifas. Solo administradores pueden gestionar tarifas."
- NO se genera tool_call

---

### TEST 5: Admin_plataforma modifica tarifa CON tarifa seleccionada

**Dado:**
- Usuario con rol: `Admin_plataforma`
- Tarifa seleccionada en UI: ID 15, "Tarifa Premium" (academia_id: 308)

**Cuando:**
- Usuario envía: "cambia el precio a 90 euros"
- Request incluye:
  ```json
  {
    "uiContext": {
      "selectedTarifa": {"id": 15, "nombre": "Tarifa Premium"},
      "selectedAcademia": {"id": 308, "nombre": "Academia de Idiomas Madrid"}
    }
  }
  ```

**Entonces:**
- Backend-chat inyecta: `[CONTEXTO UI]: Usuario seleccionó tarifa ID 15 ('Tarifa Premium') de academia 'Academia de Idiomas Madrid' (ID: 308).`
- OpenAI genera tool_call:
  ```json
  {
    "name": "tarifas.actualizar_tarifa_patch",
    "arguments": {
      "tarifa_id": 15,
      "precio_base": 90.0
    }
  }
  ```
- Backend-chat llama a `PATCH /tarifas/15` con JWT de Admin_plataforma
- API valida permisos y actualiza tarifa
- Respuesta: "✅ He actualizado el precio de 'Tarifa Premium' a 90€."

---

### TEST 6: Admin_plataforma modifica tarifa SIN tarifa seleccionada

**Dado:**
- Usuario con rol: `Admin_plataforma`
- No hay tarifa seleccionada en UI

**Cuando:**
- Usuario envía: "cambia el precio a 90 euros"
- Request NO incluye `uiContext.selectedTarifa`

**Entonces:**
- OpenAI detecta que falta el identificador de la tarifa
- Responde: "¿Qué tarifa quieres modificar? Por favor, indícame el nombre o ID de la tarifa."
- NO genera tool_call hasta que usuario especifique

---

### TEST 7: Admin_academia modifica tarifa de su academia

**Dado:**
- Usuario con rol: `Admin_academia`
- JWT incluye: `academia_id: 42`
- Tarifa seleccionada: ID 20 (pertenece a academia 42)

**Cuando:**
- Usuario envía: "actualiza la descripción a 'Tarifa Estándar 2025'"
- Request incluye:
  ```json
  {
    "uiContext": {
      "selectedTarifa": {"id": 20, "nombre": "Tarifa Estándar"}
    }
  }
  ```

**Entonces:**
- OpenAI genera tool_call:
  ```json
  {
    "name": "tarifas.actualizar_tarifa_patch",
    "arguments": {
      "tarifa_id": 20,
      "descripcion": "Tarifa Estándar 2025"
    }
  }
  ```
- Backend-chat llama a `PATCH /tarifas/20` con JWT de Admin_academia
- API valida que tarifa 20 pertenece a academia 42 (del JWT)
- Respuesta: "✅ He actualizado la descripción de la tarifa."

---

### TEST 8: Profesor_academia intenta modificar tarifa (DENEGADO)

**Dado:**
- Usuario con rol: `Profesor_academia`
- Tarifa seleccionada: ID 20

**Cuando:**
- Usuario envía: "cambia el precio a 100"

**Entonces:**
- OpenAI detecta falta de permisos (x-permissions en OpenAPI)
- Responde: "❌ No tienes permisos para modificar tarifas."
- NO genera tool_call

---

## Implementación Pendiente

### 1. Backend-chat (Java Spring Boot)

**Archivos a crear/modificar:**

```
src/main/java/com/workers/profesores/chat/dto/
├── UiContext.java (CREAR)
├── SelectedEntity.java (CREAR)
└── ChatRequest.java (MODIFICAR - añadir campo uiContext)
```

**Lógica en PromptOpenAi.java:**
- En `buildSystemPrompt()`, después de línea 45 (profile injection)
- Detectar si `chatRequest.getUiContext() != null`
- Inyectar `[CONTEXTO UI]: ...` en el system prompt

### 2. Tests E2E (Python - api-workers-profesores)

**Archivo:** `tests/chat/test_mediator_chat_alta_tarifa.py`

**Estructura:**
```python
def test_admin_plataforma_with_selected_academia():
    # Setup: crear academia, obtener JWT de Admin_plataforma
    # Llamar a /chat con uiContext.selectedAcademia
    # Verificar que OpenAI genera tool_call con academia_id correcto
    # Verificar que tarifa se crea exitosamente
    pass

def test_admin_plataforma_without_selected_academia():
    # Verificar que OpenAI pregunta por la academia
    pass

def test_admin_academia_no_selection_needed():
    # Verificar que usa JWT academia_id, ignora uiContext
    pass

# ... resto de tests
```

### 3. Android App (Kotlin - futuro)

**Modificaciones:**
- Actualizar `ChatRequest` data class para incluir `uiContext`
- Capturar selecciones de listas (academias, tarifas, cursos)
- Enviar `uiContext` con los mensajes cuando hay selección activa

---

## Edge Cases a Considerar

1. **Academia eliminada después de selección:**
   - ¿Qué pasa si selectedAcademia.id ya no existe?
   - Solución: API devolverá 404, chat debe informar al usuario

2. **Academia inactiva (fecha_baja != NULL):**
   - ¿Debería backend-chat validar estado antes de generar tool_call?
   - Opción 1: Dejar que API valide y devuelva error
   - Opción 2: Pre-validar en backend-chat (requiere llamada adicional)

3. **Admin_academia con selectedAcademia diferente a JWT:**
   - **IMPORTANTE**: Admin_academia NO puede trabajar con otras academias
   - Backend debe IGNORAR `uiContext.selectedAcademia` si rol es Admin_academia
   - Siempre usar `academia_id` del JWT

4. **Permisos en cascada:**
   - Si Admin_plataforma selecciona academia sin permiso sobre ella (caso edge)
   - API debe validar permisos finales, no confiar ciegamente en uiContext

5. **Tarifa de otra academia:**
   - Admin_academia selecciona tarifa que NO pertenece a su academia
   - API debe rechazar con 403 Forbidden
   - Chat debe informar: "Esa tarifa no pertenece a tu academia"

---

## Validación de Implementación

### Checklist Backend-chat:

- [ ] DTOs creados: `UiContext.java`, `SelectedEntity.java`
- [ ] `ChatRequest.java` modificado con campo `uiContext`
- [ ] Lógica de inyección en `PromptOpenAi.buildSystemPrompt()`
- [ ] Compilación exitosa: `mvn clean compile -DskipTests`
- [ ] Tests unitarios para inyección de contexto

### Checklist Tests E2E:

- [ ] Archivo `test_mediator_chat_alta_tarifa.py` creado
- [ ] 8 tests implementados (alta + modificación + permisos)
- [ ] Tests pasan con mock de OpenAI
- [ ] Tests pasan con OpenAI real (API key configurada)
- [ ] Logs verificados: system prompt incluye `[CONTEXTO UI]`

### Checklist Android App (futuro):

- [ ] `ChatRequest` actualizado con `uiContext`
- [ ] UI captura selecciones (ListState, SelectedItem)
- [ ] Request incluye `uiContext` cuando hay selección
- [ ] Tests de integración con backend-chat local

---

## Notas Adicionales

### Rendimiento OpenAI:
- El `[CONTEXTO UI]` es **conciso** (1-2 líneas máximo)
- No penaliza significativamente el consumo de tokens
- Reduce turnos de conversación (usuario no re-especifica contexto)

### Seguridad:
- `uiContext` es **informativo**, NO autoritativo
- Backend-chat NO debe confiar ciegamente en IDs del cliente
- API valida permisos finales con JWT y database

### Escalabilidad:
- Mismo patrón se aplica a: `selectedCurso`, `selectedAlumno`, `selectedProfesor`
- Estructura genérica: `SelectedEntity { id, nombre }`
- Fácil extensión a nuevas entidades

---

**Fecha de creación:** 27 de octubre de 2025  
**Estado:** Pendiente de implementación  
**Prioridad:** Alta (bloqueante para UX rica en app Android)
