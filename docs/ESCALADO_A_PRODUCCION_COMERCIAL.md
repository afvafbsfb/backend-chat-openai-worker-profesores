# Estrategia de Escalado: De MVP Gratuito a Producción Comercial

## Índice

1. [Estado Actual vs Comercial](#1-estado-actual-vs-comercial)
2. [Cambios Técnicos Necesarios](#2-cambios-técnicos-necesarios)
3. [Costos por Fase](#3-costos-por-fase)
4. [Plan de Migración Paso a Paso](#4-plan-de-migración-paso-a-paso)
5. [Optimización de Costos](#5-optimización-de-costos)
6. [Métricas para Decidir Cuándo Escalar](#6-métricas-para-decidir-cuándo-escalar)

---

## 1. Estado Actual vs Comercial

### Arquitectura MVP (Actual - $0/mes)

```
┌─────────────────────────────────────────────────────────────┐
│                      ARQUITECTURA MVP                        │
│                    (Gratuita - Desarrollo)                   │
└─────────────────────────────────────────────────────────────┘

Android App (Múltiples usuarios)
       │
       │ HTTPS
       ↓
┌──────────────────────────────────┐
│  Backend Chat Spring Boot        │
│  ─────────────────────────────   │
│  • EC2 t3.micro (Single)         │ ← $0 (Free Tier)
│  • Let's Encrypt (SSL)           │ ← $0 (gratis)
│  • Nginx                         │
│  • Sin balanceo                  │
│  • Sin escalado                  │
│  • Dominio: *.elasticbeanstalk   │ ← $0 (gratis)
└──────────────────────────────────┘
       │
       │ HTTPS
       ↓
┌──────────────────────────────────┐
│  API Workers (Cloudflare)        │
│  • HTTPS automático              │ ← $0 (hasta 100k req/día)
│  • Sin límites de escalado       │
└──────────────────────────────────┘
       │
       ↓
┌──────────────────────────────────┐
│  MySQL 8.0                       │
│  • Cloudflare D1 o PlanetScale   │ ← $0 (Free tier)
└──────────────────────────────────┘

LIMITACIONES:
⚠️ Una sola instancia (Single Point of Failure)
⚠️ Sin auto-scaling
⚠️ Dominio no profesional (*.elasticbeanstalk.com)
⚠️ Caídas si instancia falla
⚠️ ~50-100 usuarios concurrentes máximo
```

### Arquitectura Comercial (Escalable - ~$50-80/mes)

```
┌─────────────────────────────────────────────────────────────┐
│                  ARQUITECTURA COMERCIAL                      │
│              (Producción - Alta Disponibilidad)              │
└─────────────────────────────────────────────────────────────┘

Android App (Miles de usuarios)
       │
       │ HTTPS
       ↓
┌──────────────────────────────────┐
│  Dominio Personalizado           │
│  • chat.tuacademia.com           │ ← $12/año (~$1/mes)
│  • Route 53 DNS                  │ ← $0.50/mes
└──────────────────────────────────┘
       │
       ↓
┌──────────────────────────────────┐
│  Application Load Balancer       │
│  • HTTPS (ACM Certificate)       │ ← $16/mes
│  • Balanceo automático           │
│  • SSL Termination               │
│  • Health checks                 │
└──────────────────────────────────┘
       │
       ├──────────────┬──────────────┐
       ↓              ↓              ↓
   ┌────────┐    ┌────────┐    ┌────────┐
   │ EC2 AZ1│    │ EC2 AZ2│    │ EC2 AZ3│  ← Auto-scaling
   │t3.small│    │t3.small│    │t3.small│     2-5 instancias
   │Backend │    │Backend │    │Backend │     $30-75/mes
   └────────┘    └────────┘    └────────┘
       │              │              │
       └──────────────┴──────────────┘
                     │
                     ↓
           ┌──────────────────────┐
           │  API Workers         │
           │  • Cloudflare        │ ← $0 (hasta 100k req/día)
           │  • Auto-escalado     │   $5/mes después
           └──────────────────────┘
                     │
                     ↓
           ┌──────────────────────┐
           │  MySQL Gestionado    │
           │  • RDS db.t3.micro   │ ← $15-25/mes
           │  • Multi-AZ opcional │   (+ $15/mes multi-AZ)
           │  • Backups auto      │
           └──────────────────────┘

VENTAJAS:
✅ Alta disponibilidad (99.9% uptime)
✅ Auto-scaling según demanda
✅ Dominio profesional personalizado
✅ Sin Single Point of Failure
✅ Miles de usuarios concurrentes
✅ Backups automáticos
✅ Monitoreo CloudWatch incluido
```

---

## 2. Cambios Técnicos Necesarios

### 2.1 Infraestructura

#### Cambio 1: Dominio Personalizado

**Antes (MVP):**
```
https://production-env.us-east-1.elasticbeanstalk.com
```

**Después (Comercial):**
```
https://chat.tuacademia.com
```

**Acciones:**
1. Comprar dominio en Route 53 (o transferir existente)
2. Crear Hosted Zone en Route 53
3. Configurar registro A (Alias) apuntando al Load Balancer
4. Solicitar certificado SSL en ACM para dominio personalizado

**Costo:** $12/año dominio + $0.50/mes Route 53 = **~$1.50/mes**

---

#### Cambio 2: Application Load Balancer

**Antes (MVP):**
- Single instance sin balanceo
- Let's Encrypt manual

**Después (Comercial):**
```powershell
# Migrar a entorno con ALB
eb create production-commercial `
  --elb-type application `
  --instance-types t3.small `
  --scale 2  # Mínimo 2 instancias para HA
```

**Acciones:**
1. Crear nuevo entorno con ALB
2. Configurar listener HTTPS con certificado ACM
3. Configurar health checks
4. Migrar DNS de old → new (zero downtime)

**Costo:** **$16/mes**

---

#### Cambio 3: Auto-Scaling

**Antes (MVP):**
- 1 instancia fija
- Si cae, servicio caído

**Después (Comercial):**
```yaml
# .ebextensions/02-autoscaling.config
option_settings:
  aws:autoscaling:asg:
    MinSize: 2          # Mínimo 2 instancias (alta disponibilidad)
    MaxSize: 5          # Máximo 5 instancias
  
  aws:autoscaling:trigger:
    MeasureName: CPUUtilization
    Statistic: Average
    Unit: Percent
    UpperThreshold: 70  # Escalar arriba si CPU > 70%
    LowerThreshold: 20  # Escalar abajo si CPU < 20%
    BreachDuration: 5   # Minutos
```

**Comportamiento:**
- **Tráfico bajo:** 2 instancias (~$30/mes)
- **Tráfico medio:** 3-4 instancias (~$45-60/mes)
- **Tráfico alto:** 5 instancias (~$75/mes)

**Costo:** **$30-75/mes** (promedio $45/mes)

---

#### Cambio 4: Base de Datos Gestionada

**Antes (MVP):**
- MySQL en Cloudflare D1 o PlanetScale Free Tier
- Sin backups automáticos
- Limitado a 10GB

**Después (Comercial):**

**Opción A: RDS MySQL (Recomendado)**
```
Configuración:
- db.t3.micro (1 vCPU, 1 GB RAM)
- Single-AZ: $15/mes
- Multi-AZ (HA): $30/mes ← Recomendado para producción
- 20 GB SSD incluido
- Backups automáticos (7 días retención)
- Snapshots manuales ilimitados
```

**Opción B: PlanetScale Pro**
```
- $29/mes
- 10 GB incluido
- Escalado automático
- Backups incluidos
- No necesitas gestionar servidor
```

**Costo:** **$15-30/mes** (RDS) o **$29/mes** (PlanetScale)

---

### 2.2 Código y Configuración

#### Cambio 1: Variables de Entorno

```powershell
# Actualizar para producción comercial
eb setenv `
  SPRING_PROFILES_ACTIVE=production `
  ALLOWED_ORIGINS=https://chat.tuacademia.com,https://app.tuacademia.com `
  DB_HOST=tuacademia-db.xxxxxx.us-east-1.rds.amazonaws.com `
  DB_NAME=academia_prod `
  DB_USER=admin_prod `
  DB_PASSWORD=xxxxx `
  OPENAI_API_KEY=sk-xxx `
  JWT_SECRET_KEY=xxxxx
```

#### Cambio 2: Connection Pooling (RDS)

```properties
# application-production.properties (actualizar)

# Conexión a RDS
spring.datasource.url=jdbc:mysql://${DB_HOST}:3306/${DB_NAME}?useSSL=true&requireSSL=true
spring.datasource.username=${DB_USER}
spring.datasource.password=${DB_PASSWORD}

# Connection Pool optimizado para RDS
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.max-lifetime=1800000
```

#### Cambio 3: Logging para CloudWatch

```properties
# Logs estructurados para CloudWatch Insights
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n
logging.level.root=INFO
logging.level.com.backendworkersprofesores=INFO
logging.level.org.springframework.web=WARN

# Métricas para CloudWatch
management.metrics.export.cloudwatch.enabled=true
management.metrics.export.cloudwatch.namespace=ChatBackend
management.metrics.export.cloudwatch.batch-size=20
```

#### Cambio 4: Rate Limiting

```java
// Nuevo: RateLimitingFilter.java
@Component
public class RateLimitingFilter implements Filter {
    private final RateLimiter rateLimiter = RateLimiter.create(100.0); // 100 req/s global
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
            throws IOException, ServletException {
        if (!rateLimiter.tryAcquire()) {
            ((HttpServletResponse) response).setStatus(429); // Too Many Requests
            return;
        }
        chain.doFilter(request, response);
    }
}
```

---

### 2.3 Android App

#### Cambio: Actualizar URL Base

```kotlin
// NetworkConfig.kt
object NetworkConfig {
    private const val BASE_URL_DEV = "http://10.0.2.2:8080"
    
    // Actualizar a dominio comercial
    private const val BASE_URL_PROD = "https://chat.tuacademia.com"  // ← Cambio aquí
    
    val BASE_URL = if (BuildConfig.DEBUG) BASE_URL_DEV else BASE_URL_PROD
}
```

**¡Importante!** Con dominio personalizado, actualizar:
- Google Play Store listing
- Términos de servicio
- Política de privacidad (URL del backend)

---

## 3. Costos por Fase

### Fase 1: MVP (Actual) - **$0/mes**

| Recurso | Costo |
|---------|-------|
| EC2 t3.micro | $0 (Free Tier 750h/mes) |
| Let's Encrypt | $0 |
| Dominio AWS | $0 |
| Cloudflare Workers | $0 (hasta 100k req/día) |
| MySQL (D1/PlanetScale) | $0 (Free Tier) |
| **TOTAL** | **$0/mes** |

**Límites:**
- 50-100 usuarios concurrentes
- ~10,000 peticiones/día
- 99% uptime (sin garantía)

---

### Fase 2: Comercial Básico - **$50-60/mes**

| Recurso | Costo Mensual |
|---------|---------------|
| **Dominio personalizado** | $1.50 |
| **Application Load Balancer** | $16 |
| **EC2 t3.small (2 instancias)** | $30 |
| **RDS db.t3.micro (Single-AZ)** | $15 |
| **Route 53** | $0.50 |
| **Cloudflare Workers** | $0 (hasta 100k) |
| **Data Transfer** | $1-3 |
| **CloudWatch Logs** | $1-2 |
| **TOTAL** | **~$65/mes** |

**Capacidad:**
- 500-1,000 usuarios concurrentes
- ~100,000 peticiones/día
- 99.9% uptime (garantizado por ALB)
- Alta disponibilidad (2 AZs)

---

### Fase 3: Comercial Avanzado - **$100-150/mes**

| Recurso | Costo Mensual |
|---------|---------------|
| Dominio personalizado | $1.50 |
| **Application Load Balancer** | $16 |
| **EC2 t3.small (3-4 instancias promedio)** | $45-60 |
| **RDS db.t3.small (Multi-AZ)** | $45 |
| Route 53 | $0.50 |
| **Cloudflare Workers** | $5 (>100k req) |
| **ElastiCache Redis** (sesiones) | $15 |
| Data Transfer | $5-10 |
| CloudWatch | $3-5 |
| **S3** (backups, logs) | $1-2 |
| **TOTAL** | **~$135/mes** |

**Capacidad:**
- 2,000-5,000 usuarios concurrentes
- ~500,000 peticiones/día
- 99.95% uptime
- Multi-AZ completo
- Redis para sesiones (mejor rendimiento)

---

### Fase 4: Escala Empresarial - **$300-500/mes**

| Recurso | Costo Mensual |
|---------|---------------|
| Dominio + CDN | $50 (Cloudflare Pro) |
| **Application Load Balancer** | $16 |
| **EC2 t3.medium (5-10 instancias)** | $150-300 |
| **RDS db.t3.medium (Multi-AZ + Read Replica)** | $90 |
| Route 53 | $0.50 |
| **Cloudflare Workers Pro** | $20 |
| **ElastiCache Redis (cache.t3.small)** | $30 |
| **OpenAI API** | $50-200 (según uso) |
| Data Transfer | $20-50 |
| CloudWatch + X-Ray | $10-20 |
| S3 | $5-10 |
| **TOTAL** | **~$400-750/mes** |

**Capacidad:**
- 10,000+ usuarios concurrentes
- Millones de peticiones/día
- 99.99% uptime
- Monitoreo avanzado (X-Ray)
- CDN global

---

## 4. Plan de Migración Paso a Paso

### Estrategia: Blue-Green Deployment (Zero Downtime)

```
Paso 1: Entorno VERDE (actual MVP) sigue funcionando
Paso 2: Crear entorno AZUL (comercial) en paralelo
Paso 3: Probar entorno AZUL completamente
Paso 4: Cambiar DNS de VERDE → AZUL
Paso 5: Monitorear, si hay problemas → rollback a VERDE
Paso 6: Después de 24h sin problemas → terminar VERDE
```

---

### Paso 1: Comprar Dominio (Día 1)

```bash
# AWS Console → Route 53 → Register Domain
# Dominio: tuacademia.com
# Costo: $12/año
# Tiempo de activación: 15 minutos - 3 días
```

**Mientras esperas activación del dominio:**
- Puedes avanzar con otros pasos
- Crear certificado ACM para dominio (validación pendiente)

---

### Paso 2: Crear Entorno Comercial (Día 1-2)

```powershell
cd "c:\Users\Angel FV\Desktop\FORMACION\chat_backend_academia\backend-chat-openai-worker-profesores"

# Crear nuevo entorno comercial
eb create production-commercial `
  --elb-type application `
  --instance-types t3.small `
  --scale 2 `
  --database `
  --database.engine mysql `
  --database.size 20 `
  --database.instance db.t3.micro `
  --tags "Environment=Commercial,Project=ChatBackend"

# Configurar variables de entorno
eb setenv -e production-commercial `
  SPRING_PROFILES_ACTIVE=production `
  ALLOWED_ORIGINS=https://chat.tuacademia.com `
  OPENAI_API_KEY=sk-xxx `
  JWT_SECRET_KEY=xxx
```

**Resultado:**
- URL temporal: `production-commercial.us-east-1.elasticbeanstalk.com`
- Load Balancer creado
- RDS MySQL creado
- Auto-scaling configurado

---

### Paso 3: Solicitar Certificado SSL (Día 2)

```bash
# AWS Console → Certificate Manager
# 1. Request certificate
# 2. Domain: chat.tuacademia.com, *.tuacademia.com (wildcard)
# 3. DNS validation
# 4. Create records in Route 53 (automático)
# 5. Esperar validación (5-15 minutos)
```

---

### Paso 4: Configurar Dominio en Load Balancer (Día 2)

```bash
# AWS Console → EC2 → Load Balancers
# 1. Seleccionar Load Balancer de production-commercial
# 2. Listeners → HTTPS:443 → Edit
# 3. Cambiar certificado a: chat.tuacademia.com (ACM)
# 4. Save
```

---

### Paso 5: Migrar Base de Datos (Día 3)

```bash
# Exportar datos del entorno MVP
eb ssh production-env  # Entorno viejo
mysqldump -u usuario -p academia_db > backup.sql
exit

# Importar a RDS nuevo
mysql -h production-commercial-db.xxx.rds.amazonaws.com -u admin -p academia_prod < backup.sql
```

**Alternativa:** Usar AWS Database Migration Service (DMS) para migración sin downtime.

---

### Paso 6: Probar Entorno Comercial (Día 3-4)

```bash
# Probar backend directamente (antes de cambiar DNS)
curl -H "Host: chat.tuacademia.com" \
  https://production-commercial.us-east-1.elasticbeanstalk.com/actuator/health

# Debe responder: {"status":"UP"}

# Probar desde Android (local.properties):
# BASE_URL_TEST=https://production-commercial.us-east-1.elasticbeanstalk.com
```

**Checklist de pruebas:**
- [ ] Login funciona
- [ ] Chat funciona
- [ ] Sesiones funcionan
- [ ] Base de datos accesible
- [ ] Health checks OK
- [ ] CORS configurado correctamente
- [ ] SSL Labs rating A

---

### Paso 7: Cambiar DNS (Día 5 - Go Live!)

```bash
# AWS Console → Route 53 → Hosted Zone → tuacademia.com
# 1. Create record
# 2. Name: chat
# 3. Type: A - IPv4 address
# 4. Alias: Yes
# 5. Route traffic to: Application Load Balancer → production-commercial
# 6. Create
```

**Propagación DNS:** 5-15 minutos (puede tardar hasta 48h globalmente)

**Verificar:**
```powershell
nslookup chat.tuacademia.com
# Debe resolverse a IP del Load Balancer
```

---

### Paso 8: Monitorear (Día 5-6)

```bash
# Ver logs en tiempo real
eb logs --stream -e production-commercial

# Métricas en CloudWatch
# AWS Console → CloudWatch → Dashboards
# Monitorear:
# - CPU utilization
# - Request count
# - Target response time
# - Unhealthy host count
```

**Si todo funciona bien durante 24 horas → Paso 9**

---

### Paso 9: Terminar Entorno MVP (Día 7)

```powershell
# Backup final del entorno viejo
eb snapshot -e production-env

# Terminar entorno MVP (libera recursos y deja de usar Free Tier)
eb terminate production-env

# Confirmar: yes
```

✅ **Migración completa!**

---

## 5. Optimización de Costos

### Estrategia 1: Reserved Instances (hasta 72% descuento)

Si ya tienes tráfico constante (después de 3-6 meses):

```
EC2 Reserved Instances (1 año):
- t3.small On-Demand: $15/mes
- t3.small Reserved:   $8/mes   ← 47% ahorro
- Ahorro anual: $84 por instancia

2 instancias reservadas:
- Costo sin reserva: $360/año
- Costo con reserva: $192/año
- AHORRO: $168/año
```

---

### Estrategia 2: Spot Instances (hasta 90% descuento)

Para cargas no críticas o auto-scaling:

```yaml
# .ebextensions/03-spot-instances.config
option_settings:
  aws:ec2:instances:
    EnableSpot: true
    SpotFleetOnDemandBase: 1        # Mínimo 1 On-Demand
    SpotFleetOnDemandAboveBasePercentage: 0  # Resto Spot
    SpotMaxPrice: 0.015              # Máximo $0.015/hora (t3.small ~$0.0208)
```

**Riesgo:** Instancias Spot pueden ser terminadas con 2 minutos de aviso.
**Solución:** Mantener 1-2 On-Demand + resto Spot.

---

### Estrategia 3: Auto-Scaling Agresivo

```yaml
# Escalar más agresivamente para usar menos instancias
option_settings:
  aws:autoscaling:trigger:
    UpperThreshold: 80  # Antes: 70
    LowerThreshold: 15  # Antes: 20
```

**Ahorro:** Mantener 2 instancias más tiempo en lugar de escalar a 3-4.

---

### Estrategia 4: CloudFront CDN (mejorar rendimiento, reducir costo)

```
Problema: Backend sirve assets estáticos (imágenes, etc.)
Solución: Usar CloudFront (CDN de AWS)

Costo CloudFront:
- Primer 10 TB/mes: $0.085/GB
- Primer 1 TB gratis con Free Tier

Ahorro:
- Reduce tráfico al backend (menos data transfer)
- Mejora latencia global (CDN edge locations)
- Reduce carga en EC2 (menos CPU)
```

---

## 6. Métricas para Decidir Cuándo Escalar

### ¿Cuándo Migrar de MVP ($0) a Comercial Básico ($65)?

**Indicadores:**
- ✅ Tienes **>50 usuarios activos diarios**
- ✅ Generas **>10,000 peticiones/día**
- ✅ MVP se cae frecuentemente (uptime <99%)
- ✅ Necesitas **dominio profesional** para confianza del cliente
- ✅ Vas a **cobrar a usuarios** (necesitas alta disponibilidad)
- ✅ Free Tier de AWS se acaba (después de 12 meses)

---

### ¿Cuándo Escalar de Comercial Básico ($65) a Avanzado ($135)?

**Indicadores:**
- ✅ **>500 usuarios activos diarios**
- ✅ **>100,000 peticiones/día**
- ✅ CPU utilization constantemente **>70%**
- ✅ Response time **>500ms** (debería ser <200ms)
- ✅ Errores 5xx frecuentes (backend sobrecargado)
- ✅ Base de datos RDS CPU **>80%** (necesitas escalar a t3.small)
- ✅ Tienes **ingresos >$500/mes** (justifica inversión)

---

### ¿Cuándo Escalar a Empresarial ($400+)?

**Indicadores:**
- ✅ **>5,000 usuarios activos diarios**
- ✅ **>1 millón peticiones/día**
- ✅ **Clientes pagando >$5,000/mes** en total
- ✅ SLA 99.99% requerido (contratos empresariales)
- ✅ Expansión internacional (necesitas CDN global)
- ✅ Múltiples equipos trabajando (necesitas staging/QA environments)

---

## 7. Resumen Comparativo

| Aspecto | MVP ($0) | Comercial Básico ($65) | Avanzado ($135) | Empresarial ($400+) |
|---------|----------|------------------------|-----------------|---------------------|
| **Costo mensual** | $0 | ~$65 | ~$135 | ~$400-750 |
| **Usuarios concurrentes** | 50-100 | 500-1,000 | 2,000-5,000 | 10,000+ |
| **Peticiones/día** | ~10k | ~100k | ~500k | Millones |
| **Uptime garantizado** | ~99% | 99.9% | 99.95% | 99.99% |
| **Alta disponibilidad** | ❌ | ✅ (2 AZs) | ✅ (Multi-AZ full) | ✅ (Multi-región) |
| **Auto-scaling** | ❌ | ✅ (2-5 instancias) | ✅ (3-10 instancias) | ✅ (ilimitado) |
| **Dominio personalizado** | ❌ | ✅ | ✅ | ✅ + CDN |
| **Base de datos HA** | ❌ | ⚠️ (Single-AZ) | ✅ (Multi-AZ) | ✅ (Multi-AZ + Replicas) |
| **Monitoreo avanzado** | Básico | CloudWatch | CloudWatch + Alarmas | CloudWatch + X-Ray + APM |
| **Backups automáticos** | ❌ | ✅ (7 días) | ✅ (30 días) | ✅ (personalizado) |
| **Rollback rápido** | ❌ | ⚠️ (manual) | ✅ (eb deploy --version) | ✅ (Blue-Green) |
| **Cache distribuido** | ❌ | ❌ | ✅ (Redis) | ✅ (Redis Cluster) |
| **Mejor para** | Desarrollo, MVP | Startup inicial | Producto establecido | Empresa |

---

## 8. Recomendación Estratégica

### Ruta Sugerida

```
Mes 1-3: MVP ($0/mes)
├─ Validar idea
├─ Primeros 50-100 usuarios
├─ Iterar producto
└─ Sin riesgo financiero

Mes 4-6: Comercial Básico ($65/mes)
├─ Dominio profesional comprado
├─ Alta disponibilidad configurada
├─ 100-500 usuarios
└─ Empezar a cobrar ($10/usuario/mes = $1,000-5,000/mes ingresos)

Mes 7-12: Optimizar Costos
├─ Comprar Reserved Instances (ahorro 47%)
├─ Añadir CloudFront CDN
├─ Costo real: ~$45/mes (con optimizaciones)
└─ Ingresos: $3,000-8,000/mes

Año 2: Comercial Avanzado ($135/mes) SI y SOLO SI:
├─ Ingresos >$10,000/mes
├─ Usuarios >1,000 activos/día
└─ ROI: Gastas $135, ganas $10,000+ = 7,400% ROI
```

---

## 9. Checklist de Migración Final

### Pre-Migración

- [ ] Comprar dominio (tuacademia.com)
- [ ] Solicitar certificado ACM
- [ ] Crear entorno comercial (production-commercial)
- [ ] Configurar RDS MySQL
- [ ] Configurar variables de entorno
- [ ] Migrar base de datos
- [ ] Probar entorno completo

### Go-Live

- [ ] Configurar DNS (Route 53 → ALB)
- [ ] Verificar HTTPS funcionando
- [ ] Actualizar Android app con nuevo dominio
- [ ] Publicar update en Play Store
- [ ] Comunicar a usuarios (si aplica)

### Post-Migración

- [ ] Monitorear 24h continuamente
- [ ] Verificar CloudWatch alarmas
- [ ] Confirmar costos en AWS Billing
- [ ] Terminar entorno MVP (después de 7 días sin problemas)
- [ ] Configurar backups automáticos
- [ ] Documentar cambios para equipo

---

## 10. Conclusión

**Para tu caso (rúbrica educativa):**
- ✅ Usa MVP ($0/mes) ahora
- ✅ Cumple 100% requisitos de seguridad
- ✅ Aprende el stack completo

**Si comercializas después:**
- ✅ Migración simple y probada
- ✅ Zero downtime con Blue-Green
- ✅ Inversión $65/mes para producto serio
- ✅ Escalable hasta millones de usuarios

**ROI esperado:**
- Gastas: $65/mes
- Cobras: $10/usuario × 100 usuarios = $1,000/mes
- **Beneficio neto: $935/mes** (1,438% ROI)

¡La infraestructura está diseñada para crecer contigo! 🚀
