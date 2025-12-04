# Backend Chat OpenAI Worker - Mediador Conversacional

> **Backend mediador entre cliente móvil y OpenAI GPT-4 para chat conversacional con gestión de academias**  
> Proyecto Final de Ciclo - FP DAM | Ángel Fernández Vidal | 2025

## 📋 Descripción

Backend mediador Java/Spring Boot que orquesta las conversaciones entre el cliente Android y OpenAI GPT-4, gestionando la autenticación JWT, delegación de tokens, construcción dinámica de prompts, y ejecución de tool calls contra la API REST de academias.

### Características Principales

- 🤖 **Integración OpenAI GPT-4** (gpt-4o-2024-08-06) con function calling
- 🔐 **JWT Delegation** - Recibe token del cliente y genera token delegado para API calls
- 🎯 **Ingeniería de Prompts Avanzada** - Sistema de prompts multi-turno con 90% cumplimiento best practices OpenAI
- 🔄 **Orquestación Multi-Turno** - Gestión de conversaciones con resolución de Foreign Keys
- 📝 **Whitelisting Dinámico** - Filtrado de endpoints por rol desde OpenAPI spec (x-permissions)
- 🛠️ **Tool Calling** - `call_api` y `call_api_batch` para operaciones CRUD via lenguaje natural
- 📊 **Validación de Respuestas** - Schema validation y formateo automático a JSON
- 🧪 **Testing Completo** - Tests unitarios y de integración con JUnit 5 + Mockito
- 🎨 **UI Suggestions** - Generación automática de sugerencias contextuales tipadas
- 🔍 **Observabilidad** - Logs estructurados con contexto de conversación

## 🏗️ Stack Tecnológico

| Componente | Tecnología | Versión |
|------------|------------|---------|
| **Lenguaje** | Java | 21 |
| **Framework** | Spring Boot | 3.2.0 |
| **Cliente OpenAI** | OpenAI Java SDK | 0.18.2 |
| **Autenticación** | JWT (com.auth0.java-jwt) | 4.4.0 |
| **Cliente HTTP** | Spring WebFlux WebClient | - |
| **Serialización JSON** | Jackson | 2.15.3 |
| **Build Tool** | Maven | 3.9+ |
| **Testing** | JUnit 5 + Mockito | 5.10.1 |
| **Logging** | SLF4J + Logback | - |

## 🎯 Arquitectura del Sistema

```
┌─────────────────────────────────────────────────────────────┐
│                   Cliente Android                            │
│              (AcademiaAPP - Jetpack Compose)                 │
└──────────────────────┬──────────────────────────────────────┘
                       │ POST /chat/send
                       │ Authorization: Bearer <access_token>
                       │ Body: { "message": "Listar usuarios" }
                       ▼
┌─────────────────────────────────────────────────────────────┐
│           Backend Chat OpenAI Worker (ESTE REPO)             │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  ChatController                                         │ │
│  │  - Valida JWT del cliente                              │ │
│  │  - Extrae UserClaims (roles, academia_id)              │ │
│  └──────────────────┬─────────────────────────────────────┘ │
│                     │                                        │
│                     ▼                                        │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  ChatService (Orquestador Principal)                   │ │
│  │  1. Construye system prompt (PromptOpenAi)             │ │
│  │  2. Filtra whitelist por rol (OpenAPICallApiService)   │ │
│  │  3. Llama a OpenAI GPT-4 (gpt-4o-2024-08-06)          │ │
│  │  4. Procesa tool_calls si existen                      │ │
│  │  5. Ejecuta API calls via ApiProxyService              │ │
│  │  6. Reinyecta resultados a GPT-4 (turno 2)            │ │
│  │  7. Valida y formatea respuesta final                  │ │
│  └──────────────────┬─────────────────────────────────────┘ │
│                     │                                        │
│      ┌──────────────┼──────────────┐                        │
│      │              │              │                        │
│      ▼              ▼              ▼                        │
│  ┌────────┐  ┌─────────────┐  ┌──────────────┐            │
│  │Prompt  │  │OpenAICall   │  │ApiProxy      │            │
│  │OpenAi  │  │ApiService   │  │Service       │            │
│  └────────┘  └─────────────┘  └──────────────┘            │
│      │              │              │                        │
└──────┼──────────────┼──────────────┼────────────────────────┘
       │              │              │
       │              ▼              ▼
       │      ┌──────────────┐  ┌──────────────────────┐
       │      │  OpenAI API  │  │   API REST Python    │
       │      │   GPT-4o     │  │  (api-workers)       │
       │      └──────────────┘  │  - GET /usuarios     │
       │                        │  - POST /usuarios    │
       │                        │  - GET /roles        │
       │                        │  - etc...            │
       │                        └──────────────────────┘
       │
       └──> System Prompt Dinámico:
            • Rol: "Eres un asistente (secretaria)..."
            • Whitelist filtrada por rol
            • User context (roles, academia_id, perfil)
            • Reglas de FK resolution (GET antes de POST)
            • Contrato JSON (text, ui_suggestions, summary_fields)
            • Ejemplos few-shot (✅/❌)
```

## 🚀 Instalación y Configuración

### Requisitos Previos

- **Java 21** (OpenJDK o Oracle JDK)
- **Maven 3.9+** (incluido wrapper `mvnw`)
- **API REST Python** ejecutándose en `http://localhost:5000`
- **Clave API de OpenAI** (GPT-4 access)

### Configuración

#### 1. Clonar el repositorio

```bash
git clone https://github.com/afvafbsfb/backend-chat-openai-worker-profesores.git
cd backend-chat-openai-worker-profesores
```

#### 2. Configurar clave de OpenAI

Crear archivo `src/main/resources/application-local.properties` (git-ignored):

```properties
spring.profiles.active=dev
openai.api.key.dev=sk-XXXXXXXXXXXXXXXXXXXXXXXXXXXXX
```

#### 3. Verificar configuración en `application.properties`

```properties
# Puerto del mediador
server.port=8080

# URL de la API Python (ajustar según entorno)
academia.api.baseurl=http://localhost:5000

# JWT Delegation Secret (debe coincidir con API Python)
jwt.delegation.secret=mi_secret_delegacion_local_larga
jwt.delegation.expiration.minutes=5

# OpenAI Config
openai.model=gpt-4o-2024-08-06
openai.temperature=0.3
openai.max.tokens=2000

# Debug (desarrollo)
backend.debug=true
debug.api.proxy=true
```

### Ejecutar la Aplicación

#### Opción 1: Maven Wrapper (Recomendado)

**Modo normal:**
```bash
./mvnw spring-boot:run
```

**Modo debug con logs extendidos:**
```bash
./mvnw spring-boot:run -e -X
```

**Con mock de OpenAI (sin consumir API real):**
```bash
./mvnw spring-boot:run -Dopenai.mock=true -Dbackend.debug=true
```

**Con parámetros personalizados:**
```bash
./mvnw spring-boot:run \
  -Dopenai.mock=false \
  -Dacademia.api.baseurl=http://localhost:5000 \
  -Dbackend.debug=true \
  -Ddebug.api.proxy=true
```

#### Opción 2: IntelliJ IDEA

1. Abrir proyecto
2. Configurar SDK Java 21
3. Run → Edit Configurations → Spring Boot
4. Main class: `com.workers.profesores.chat.ChatApplication`
5. VM options: `-Dopenai.mock=false -Dbackend.debug=true`
6. Run/Debug

La aplicación estará disponible en: **http://localhost:8080**

## 📖 Documentación del Sistema de Prompts

El sistema de prompts es el núcleo de la inteligencia del chat. Documentación completa disponible:

### 📄 Documentación en GitHub Pages

- 🤖 **[Ingeniería de Prompts y Optimización LLM](https://afvafbsfb.github.io/api-workers-profesores/INGENIERIA_PROMPTS_Y_OPTIMIZACION_LLM.html)** - Análisis completo del sistema de prompts
  - ✅ 90% cumplimiento de best practices OpenAI
  - 🔴 Problema identificado: Latencia 8-13s (whitelist completa)
  - ✅ Plan de optimización: 8 semanas, -81% latencia, -73% costo
  
- 📖 **[Memoria Completa del TFG](https://afvafbsfb.github.io/api-workers-profesores/MEMORIA_TFG_SISTEMA_CHAT_ACADEMIAS.html)**
- 🔗 **[Especificación OpenAPI](https://afvafbsfb.github.io/api-workers-profesores/served-openapi.json)** - Spec con x-permissions consumida por este backend
- 🏗️ **[Arquitectura Android MVVM](https://afvafbsfb.github.io/api-workers-profesores/ARQUITECTURA_ACADEMIAAPP_ANDROID.html)**

### Clase Principal: `PromptOpenAi.java`

**Ubicación:** `src/main/java/com/workers/profesores/chat/prompt/PromptOpenAi.java` (699 líneas)

**Tipos de prompts implementados:**

1. **System Prompt Completo** (`buildSystemPrompt()`) - ~15-20K tokens
   - Definición de rol (secretaria para academias en España)
   - Whitelist de endpoints (2,557 líneas desde served-openapi.json)
   - Contexto usuario autenticado (roles, academia_id, perfil)
   - Warnings extensos (NO confundir usuario logueado con usuarios a crear)
   - Reglas de resolución de FKs (GET obligatorio antes de POST/PUT)
   - Contrato JSON de respuesta (text, ui_suggestions, summary_fields)
   - Ejemplos few-shot (✅ CORRECTO vs ❌ ERROR)

2. **System Prompt Compacto** (`buildSecondTurnSystemPrompt()`) - ~3-5K tokens
   - Versión reducida para turno 2+ (sin whitelist ni ejemplos)
   - Mantiene reglas críticas y contrato JSON

3. **Instrucción Segundo Turno** (`buildSecondTurnInstruction()`)
   - Guía redacción de respuesta final tras tool_calls
   - Manejo de errores HTTP (400/403/404/409/500)
   - Echo trimming (optimización de payload)

4. **Instrucción de Reformateo** (`buildReformatInstruction()`)
   - Convierte texto plano a JSON estructurado (fallback)

## 🧪 Testing

### Ejecutar Tests

**Todos los tests:**
```bash
./mvnw test
```

**Tests con logs detallados:**
```bash
./mvnw test -Dsurefire.reportFormat=plain -Dsurefire.useFile=false -DtrimStackTrace=false
```

**Tests específicos:**
```bash
# Solo tests que contengan "Chat"
./mvnw -Dtest=*Chat*Test test

# Un test específico
./mvnw -Dtest=ChatServiceTest test
```

**Coverage report:**
```bash
./mvnw clean verify
# Ver report en: target/site/jacoco/index.html
```

### Estructura de Tests

```
src/test/java/
├── com/workers/profesores/chat/
│   ├── service/
│   │   └── ChatServiceTest.java       # Tests del orquestador principal
│   ├── prompt/
│   │   └── PromptOpenAiTest.java      # Tests de construcción de prompts
│   ├── controller/
│   │   └── ChatControllerTest.java    # Tests de endpoints REST
│   └── integration/
│       └── ChatIntegrationTest.java   # Tests end-to-end con mock OpenAI
```

## 🔧 Comandos Útiles

Ver lista completa en: [`documentacion/COMANDOS_UTILES.md`](documentacion/COMANDOS_UTILES.md)

**Compilación:**
```bash
# Compilar
./mvnw compile

# Limpiar y compilar
./mvnw clean compile

# Empaquetar (JAR)
./mvnw clean package

# Empaquetar sin tests
./mvnw clean package -DskipTests
```

**Desarrollo:**
```bash
# Levantar con hot reload
./mvnw spring-boot:run

# Con mock de OpenAI (sin consumir API)
./mvnw spring-boot:run -Dopenai.mock=true

# Con logs de debug
./mvnw spring-boot:run -Dbackend.debug=true -Ddebug.api.proxy=true
```

## 🏗️ Estructura del Proyecto

```
backend-chat-openai-worker-profesores/
├── src/
│   ├── main/
│   │   ├── java/com/workers/profesores/chat/
│   │   │   ├── ChatApplication.java         # Entry point
│   │   │   ├── controller/
│   │   │   │   └── ChatController.java      # REST endpoints
│   │   │   ├── service/
│   │   │   │   ├── ChatService.java         # Orquestador principal
│   │   │   │   ├── OpenAICallApiService.java # Cliente OpenAI
│   │   │   │   ├── ApiProxyService.java     # Proxy a API Python
│   │   │   │   └── JwtDelegationService.java # Generación tokens
│   │   │   ├── prompt/
│   │   │   │   └── PromptOpenAi.java        # Construcción de prompts (699 líneas)
│   │   │   ├── dto/                         # Request/Response DTOs
│   │   │   ├── config/                      # Configuración Spring
│   │   │   └── util/                        # Utilidades (JWT parsing, validación)
│   │   └── resources/
│   │       ├── application.properties       # Config principal
│   │       ├── application-local.properties # Config local (git-ignored)
│   │       └── served-openapi.json          # OpenAPI spec con x-permissions
│   └── test/
│       └── java/com/workers/profesores/chat/
│           ├── service/                     # Tests unitarios
│           ├── controller/                  # Tests de endpoints
│           └── integration/                 # Tests de integración
├── docs/                                    # Documentación técnica
├── documentacion/
│   ├── COMANDOS_UTILES.md                   # Cheatsheet de comandos Maven
│   └── run-pruebas.ps1                      # Script PowerShell para testing
├── pom.xml                                  # Dependencias Maven
├── mvnw, mvnw.cmd                           # Maven wrapper
└── README.md                                # Este archivo
```

## 🔐 Seguridad

- **JWT Validation**: Valida tokens del cliente Android con `com.auth0.java-jwt`
- **JWT Delegation**: Genera tokens de corta duración (5 min) para llamadas a API Python
- **Scoping**: Respeta scoping por `academia_id` del usuario autenticado
- **Whitelisting**: Solo endpoints permitidos por rol según x-permissions en OpenAPI
- **No secrets hardcoded**: Claves en `application-local.properties` (git-ignored)

## 🤝 Flujo de Conversación Completo

**Ejemplo: "Crear usuario: Angel Fernández, angel@test.com, rol profesor"**

```
1. Cliente Android → Backend Chat
   POST /chat/send
   Authorization: Bearer eyJ... (access token del usuario logueado)
   { "message": "Crear usuario: Angel Fernández, angel@test.com, rol profesor" }

2. Backend Chat → Validación JWT
   - Extrae UserClaims: { userId: 10, roles: ["Admin_academia"], academiaId: 2 }
   - Genera JWT delegado (5 min TTL) con mismo scope

3. Backend Chat → Construcción Prompt (Turno 1)
   PromptOpenAi.buildSystemPrompt() genera:
   - Rol base + whitelist filtrada por Admin_academia
   - Perfil usuario logueado: { nombre: "Juan Pérez", id: 10, academia_id: 2 }
   - Warnings: NO confundir con datos del MENSAJE
   - Regla: GET /roles OBLIGATORIO antes de POST /usuarios

4. Backend Chat → OpenAI GPT-4 (Turno 1)
   Messages: [
     { role: "system", content: "<15K tokens de system prompt>" },
     { role: "user", content: "Crear usuario: Angel Fernández, angel@test.com, rol profesor" }
   ]
   
   GPT-4 responde con tool_call:
   { name: "call_api", arguments: { 
       name: "roles.listar_roles", 
       method: "GET" 
   }}

5. Backend Chat → API Python (GET /roles)
   ApiProxyService ejecuta:
   GET http://localhost:5000/roles
   Authorization: Bearer <jwt_delegado>
   
   Respuesta: [{ id: 9, nombre: "Profesor_academia" }, ...]

6. Backend Chat → OpenAI GPT-4 (Turno 2)
   Reinyecta resultado del tool_call:
   Messages: [
     { role: "system", content: "<prompt compacto>" },
     { role: "user", content: "Crear usuario: Angel..." },
     { role: "assistant", content: null, tool_calls: [...] },
     { role: "tool", content: "[{id:9,nombre:Profesor_academia}...]" }
   ]
   
   GPT-4 responde con nuevo tool_call:
   { name: "call_api", arguments: { 
       name: "usuarios.crear_usuario",
       method: "POST",
       body: {
         nombre: "Angel Fernández",
         email: "angel@test.com",
         rol_id: 9,
         academia_id: 2
       }
   }}

7. Backend Chat → API Python (POST /usuarios)
   ApiProxyService ejecuta:
   POST http://localhost:5000/usuarios
   Authorization: Bearer <jwt_delegado>
   { nombre: "Angel Fernández", email: "angel@test.com", rol_id: 9, academia_id: 2 }
   
   Respuesta: { id: 123, nombre: "Angel Fernández", ... }

8. Backend Chat → OpenAI GPT-4 (Turno 3)
   Reinyecta resultado final y pide respuesta JSON:
   
   GPT-4 responde:
   {
     "text": "✅ Usuario Angel Fernández (ID: 123) creado correctamente como Profesor.",
     "ui_suggestions": [
       { "id": "sg1", "display_text": "Ver usuario creado", "type": "Generica" },
       { "id": "sg2", "display_text": "Crear otro usuario", "type": "Registro", "recordAction": "Alta" },
       { "id": "sg3", "display_text": "Listar todos los usuarios", "type": "Generica" }
     ]
   }

9. Backend Chat → Cliente Android
   HTTP 200 OK
   {
     "text": "✅ Usuario Angel Fernández (ID: 123) creado correctamente como Profesor.",
     "ui_suggestions": [...]
   }

Total: 3 llamadas a GPT-4 + 2 llamadas a API Python (~9-13 segundos)
```

## 🔗 Repositorios Relacionados

Este proyecto es parte de un ecosistema de 3 aplicaciones:

- 🐍 **[API REST Python](https://github.com/afvafbsfb/api-workers-profesores)** - Backend principal con autenticación, datos y permisos
- ☕ **[Backend Chat Java (este repo)](https://github.com/afvafbsfb/backend-chat-openai-worker-profesores)** - Mediador entre cliente y OpenAI GPT-4
- 📱 **[Cliente Android (Kotlin)](https://github.com/afvafbsfb/AcademiaAPP)** - App móvil con Jetpack Compose + MVVM

## 👨‍💻 Autor

**Ángel Fernández Vidal**  
Proyecto Final de Ciclo - FP Desarrollo de Aplicaciones Multiplataforma  
Diciembre 2025

**Email:** angel.fernandez@academia.es  
**GitHub:** [@afvafbsfb](https://github.com/afvafbsfb)

## 📄 Licencia

Este proyecto es parte de un Trabajo Final de Grado (TFG) y está disponible públicamente para fines educativos y de evaluación.

---

**🚀 Quick Start Recap:**

```bash
# 1. Configurar OpenAI key en application-local.properties
# 2. Asegurar API Python en http://localhost:5000
# 3. Levantar mediador
./mvnw spring-boot:run

# 4. Test desde cliente Android o curl
curl -X POST http://localhost:8080/chat/send \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"message":"Listar usuarios"}'
```
