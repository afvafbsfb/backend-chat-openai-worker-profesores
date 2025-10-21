# Análisis de Cambios en el Contrato de Respuesta del Chat

**Fecha:** 2025-10-21  
**Autor:** Angel fernández 
**Estado:** Pendiente de actualización en AcademiaAPP

## 🎯 Resumen Ejecutivo

El backend-chat ha evolucionado significativamente en su contrato de respuesta. El cambio **MÁS IMPORTANTE** es la sustitución del campo `suggestions: List<String>` por la estructura tipada **`uiSuggestions: List<Suggestion>`** con soporte completo para paginación, acciones de registro y sugerencias genéricas.

---

## 📋 Cambios en el Contrato (Backend Actual)

### 1. **ResponseEnvelope - Cambios Principales**

#### ✅ NUEVO: Campo `uiSuggestions` (reemplaza `suggestions`)

**Antes (Android actual):**
```kotlin
data class Envelope<T>(
    val status: String,
    val message: String?,
    val data: DataSection<T>?,
    val suggestions: List<String>?,  // ❌ OBSOLETO - solo texto plano
    val error: Any? = null
)
```

**Ahora (Backend actual - Java):**
```java
public class ResponseEnvelope {
    private String status;
    private String message;
    private DataSection data;
    private List<Suggestion> uiSuggestions = new ArrayList<>();  // ✅ NUEVO - tipado
    private Integer uiSuggestionsVersion;  // ✅ NUEVO - versionado
    private ErrorInfo error;
    private List<MessageEntry> messages = new ArrayList<>();
}
```

**Ejemplo JSON real:**
```json
{
  "status": "success",
  "message": "Se han encontrado academias en la plataforma.",
  "data": {
    "type": "academias",
    "items": [ ... ]
  },
  "uiSuggestions": [
    {
      "id": "sg-r1",
      "displayText": "Ver detalle de la academia",
      "type": "Registro",
      "recordAction": "Consulta"
    },
    {
      "id": "sg-g1",
      "displayText": "Buscar por nombre",
      "type": "Generica"
    },
    {
      "id": "pg-next",
      "displayText": "Siguiente",
      "type": "Paginacion",
      "pagination": {
        "direction": "next",
        "page": 2,
        "size": 50
      },
      "contextToken": "eyJ0eXBlIjoi..."
    }
  ],
  "uiSuggestionsVersion": 1
}
```

---

### 2. **Nueva Clase: `Suggestion` (Java)**

```java
public class Suggestion {
    private String id;              // Identificador único
    private String displayText;     // Texto a mostrar en UI
    private String type;            // "Paginacion" | "Registro" | "Generica"
    
    // Solo si type="Registro"
    private String recordAction;    // "Consulta" | "Modificacion" | "Baja" | "Alta"
    private RecordRef record;       // { resource: string, id: string }
    
    // Solo si type="Paginacion"
    private PaginationSuggestion pagination;  // { direction, page, size }
    private String contextToken;    // Token para mantener contexto de paginación
}
```

#### Tipos de Sugerencias:

| Tipo | Descripción | Campos Adicionales |
|------|-------------|-------------------|
| **`Paginacion`** | Navegación entre páginas | `pagination` (obligatorio), `contextToken` |
| **`Registro`** | Acciones sobre un ítem específico | `recordAction`, `record` (opcional) |
| **`Generica`** | Acciones generales (filtros, exportar) | Ninguno adicional |

---

### 3. **Cambios en `DataSection`**

#### ✅ NUEVO: Campo `summaryFields`

**Antes:**
```kotlin
data class DataSection<T>(
    val type: String?,
    val items: List<T>?,
    val pagination: PaginationInfo? = null
)
```

**Ahora (Backend):**
```java
public class DataSection {
    private String type;
    private List<JsonNode> items = new ArrayList<>();
    private PaginationInfo pagination;
    private HierarchyInfo hierarchy;
    private List<String> summaryFields;  // ✅ NUEVO - campos relevantes para resumen
}
```

---

### 4. **Cambios en `MessageEntry`**

**Antes:** No existía o tenía estructura diferente

**Ahora (Backend):**
```java
public class MessageEntry {
    private String type;     // "info" | "warning" | "system" | "debug"
    private String content;  // Contenido del mensaje
}
```

---

### 5. **`PaginationInfo` - Sin cambios de estructura**

✅ La estructura es compatible, solo cambia el naming de Java (camelCase) vs Kotlin:

**Java (backend):**
```java
private Boolean hasMore;  // camelCase
private Integer nextPage;
private Integer prevPage;
```

**Kotlin (Android - compatible):**
```kotlin
val hasMore: Boolean?  // camelCase también
val nextPage: Int?
val prevPage: Int?
```

---

## 🔴 Incompatibilidades Detectadas

### 1. **Campo `suggestions` vs `uiSuggestions`**

| Aspecto | Android Actual | Backend Actual | Impacto |
|---------|---------------|----------------|---------|
| Nombre del campo | `suggestions` | `uiSuggestions` | ⚠️ **CRÍTICO** - El campo no se deserializa |
| Tipo | `List<String>` | `List<Suggestion>` | ⚠️ **CRÍTICO** - Tipos incompatibles |
| Información | Solo texto plano | Objetos tipados con acciones | ⚠️ Pérdida de funcionalidad |

**Comportamiento actual en Android:**
- El campo `suggestions: List<String>?` no recibe datos (siempre será `null`)
- Las sugerencias del backend (`uiSuggestions`) se pierden completamente
- No se puede manejar paginación, acciones de registro, etc.

---

### 2. **Campo `summaryFields` faltante**

El backend envía `data.summaryFields: List<String>` para indicar qué campos son relevantes en listados grandes, pero Android no lo captura.

**Impacto:** Menor - No crítico pero útil para optimizar UI en listados grandes.

---

### 3. **Campo `messages` con estructura nueva**

Android no tiene definido `MessageEntry` con la estructura `{ type, content }`.

**Impacto:** Menor - Son mensajes de diagnóstico, opcionales en UI.

---

## ✅ Plan de Actualización para AcademiaAPP

### Fase 1: Actualizar DTOs (Kotlin)

1. **Crear `Suggestion.kt`:**

```kotlin
data class Suggestion(
    val id: String,
    val displayText: String,
    val type: String,  // "Paginacion" | "Registro" | "Generica"
    val recordAction: String? = null,  // "Consulta" | "Modificacion" | "Baja" | "Alta"
    val record: RecordRef? = null,
    val pagination: PaginationSuggestion? = null,
    val contextToken: String? = null
)

data class RecordRef(
    val resource: String,
    val id: String
)

data class PaginationSuggestion(
    val direction: String,  // "next" | "prev" | "goto"
    val page: Int?,
    val size: Int?
)
```

2. **Actualizar `Envelope.kt`:**

```kotlin
data class Envelope<T>(
    val status: String,
    val message: String?,
    val data: DataSection<T>?,
    
    // ✅ NUEVO - reemplaza suggestions
    val uiSuggestions: List<Suggestion>? = null,
    val uiSuggestionsVersion: Int? = null,
    
    // ❌ DEPRECADO - mantener temporalmente para compatibilidad
    @Deprecated("Usar uiSuggestions en su lugar")
    val suggestions: List<String>? = null,
    
    val error: ErrorInfo? = null,
    val messages: List<MessageEntry>? = null
)
```

3. **Actualizar `DataSection.kt`:**

```kotlin
data class DataSection<T>(
    val type: String?,
    val items: List<T>?,
    val summaryFields: List<String>? = null,  // ✅ NUEVO
    val pagination: PaginationInfo? = null,
    val hierarchy: Any? = null
)
```

4. **Crear/Actualizar `MessageEntry.kt`:**

```kotlin
data class MessageEntry(
    val type: String,     // "info" | "warning" | "system" | "debug"
    val content: String
)
```

5. **Crear/Actualizar `ErrorInfo.kt`:**

```kotlin
data class ErrorInfo(
    val code: String,
    val details: String
)
```

---

### Fase 2: Actualizar Lógica UI

1. **En `ChatViewModel.kt`:** Procesar `uiSuggestions` en lugar de `suggestions`

```kotlin
// Antes
val suggestions = envelope.suggestions ?: emptyList()

// Ahora
val uiSuggestions = envelope.uiSuggestions ?: emptyList()
```

2. **Renderizar según tipo de sugerencia:**

```kotlin
when (suggestion.type) {
    "Paginacion" -> renderPaginationButton(suggestion)
    "Registro" -> renderRecordAction(suggestion)
    "Generica" -> renderGenericAction(suggestion)
}
```

3. **Manejar contextToken en paginación:**

```kotlin
if (suggestion.type == "Paginacion" && suggestion.contextToken != null) {
    // Enviar contextToken en el siguiente mensaje del chat
    // para que el backend mantenga el contexto de paginación
}
```

---

### Fase 3: Testing

1. ✅ Verificar deserialización correcta de `uiSuggestions`
2. ✅ Probar navegación de paginación con `contextToken`
3. ✅ Verificar acciones de registro (Consulta, Modificacion, etc.)
4. ✅ Validar sugerencias genéricas (filtros, exportar)

---

## 📊 Compatibilidad con Versiones

### Estrategia de Migración

1. **Mantener ambos campos temporalmente:**
   - `suggestions: List<String>?` (deprecado)
   - `uiSuggestions: List<Suggestion>?` (nuevo)

2. **Priorizar `uiSuggestions`:**
   ```kotlin
   val activeSuggestions = envelope.uiSuggestions
       ?: envelope.suggestions?.map { text -> 
           Suggestion(
               id = UUID.randomUUID().toString(),
               displayText = text,
               type = "Generica"
           )
       }
       ?: emptyList()
   ```

3. **Eliminar `suggestions` en próxima versión mayor**

---

## 🔍 Ejemplos de Uso Real

### Ejemplo 1: Listado con Paginación

**Backend envía:**
```json
{
  "status": "success",
  "message": "Usuarios activos (página 1 de 5)",
  "data": {
    "type": "usuarios",
    "items": [...],
    "pagination": {
      "page": 1,
      "size": 20,
      "returned": 20,
      "hasMore": true,
      "nextPage": 2,
      "total": 94
    }
  },
  "uiSuggestions": [
    {
      "id": "pg-next",
      "displayText": "Siguiente",
      "type": "Paginacion",
      "pagination": {
        "direction": "next",
        "page": 2,
        "size": 20
      },
      "contextToken": "eyJxdWVyeSI6eyJlc3RhZG8iOiJhY3Rpdm8ifSwic29ydCI6eyJub21icmUiOiJhc2MifX0="
    }
  ]
}
```

**Android debe:**
1. Mostrar botón "Siguiente"
2. Al pulsar, enviar nuevo mensaje con `contextToken` incluido

---

### Ejemplo 2: Acción sobre Registro

**Backend envía:**
```json
{
  "uiSuggestions": [
    {
      "id": "sg-r1",
      "displayText": "Ver detalles",
      "type": "Registro",
      "recordAction": "Consulta",
      "record": {
        "resource": "usuarios",
        "id": "1496"
      }
    },
    {
      "id": "sg-r2",
      "displayText": "Modificar",
      "type": "Registro",
      "recordAction": "Modificacion",
      "record": {
        "resource": "usuarios",
        "id": "1496"
      }
    }
  ]
}
```

**Android debe:**
1. Renderizar botones de acción por ítem
2. Al pulsar "Ver detalles", navegar a pantalla de detalle
3. Al pulsar "Modificar", enviar mensaje al chat solicitando modificación

---

## 📝 Checklist de Implementación

- [ ] Crear `Suggestion.kt` con todos los subtipos
- [ ] Actualizar `Envelope.kt` con `uiSuggestions`
- [ ] Actualizar `DataSection.kt` con `summaryFields`
- [ ] Crear `MessageEntry.kt` y `ErrorInfo.kt`
- [ ] Modificar `ChatViewModel` para usar `uiSuggestions`
- [ ] Implementar renderizado de sugerencias tipadas en `ChatScreen`
- [ ] Manejar `contextToken` en navegación de paginación
- [ ] Testing completo con casos reales del backend
- [ ] Actualizar documentación Android
- [ ] Deprecar/eliminar `suggestions` en próxima versión

---

## 🚀 Próximos Pasos

1. **Revisión con el equipo:** Validar este análisis
2. **Priorización:** ¿Es bloqueante para alguna funcionalidad crítica?
3. **Estimación:** ¿Cuánto tiempo llevará la actualización? (Estimado: 4-6 horas)
4. **Implementación:** Seguir el plan de fase 1, 2, 3
5. **Actualizar documentación:** Sincronizar docs con código real

---

## 📚 Referencias

- Archivo backend: `ResponseEnvelope.java`
- Archivo backend: `Suggestion.java`
- Archivo backend: `DataSection.java`
- Archivo Android: `ChatDtos.kt`
- Ejemplo real: `prueba-20251021-163524-069.html`
