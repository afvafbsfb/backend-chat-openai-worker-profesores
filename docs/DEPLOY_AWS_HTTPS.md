# Guía de Despliegue Seguro en AWS con HTTPS

## Backend Chat (Spring Boot) - Producción con Dominio Propio

Esta guía te llevará paso a paso para desplegar el Backend Chat en AWS Elastic Beanstalk con HTTPS usando tu propio dominio.

---

## Índice

1. [Requisitos Previos](#1-requisitos-previos)
2. [Comprar y Configurar Dominio](#2-comprar-y-configurar-dominio)
3. [Preparar el Proyecto](#3-preparar-el-proyecto)
4. [Instalar AWS CLI y EB CLI](#4-instalar-aws-cli-y-eb-cli)
5. [Crear Aplicación en Elastic Beanstalk](#5-crear-aplicación-en-elastic-beanstalk)
6. [Configurar Certificado SSL](#6-configurar-certificado-ssl)
7. [Configurar HTTPS en Load Balancer](#7-configurar-https-en-load-balancer)
8. [Deploy del Backend](#8-deploy-del-backend)
9. [Verificar HTTPS](#9-verificar-https)
10. [Actualizar Android para Usar HTTPS](#10-actualizar-android-para-usar-https)
11. [Troubleshooting](#11-troubleshooting)

---

## 1. Requisitos Previos

### Cuentas Necesarias

- ✅ **Cuenta AWS** (con permisos de administrador)
- ✅ **Tarjeta de crédito** (AWS requiere verificación, aunque usemos capa gratuita)
- ✅ **Email verificado**

### Software Instalado

- ✅ **Java 21** (verificar con `java -version`)
- ✅ **Maven** (verificar con `./mvnw -version`)
- ✅ **Python 3.7+** (para AWS CLI)
- ✅ **Git**

### Información que Necesitarás

- 📝 Tu API Key de OpenAI: `sk-proj-...`
- 📝 URL de tu API Workers: `https://tu-worker.workers.dev`
- 📝 JWT Secret Key (el mismo que uses en API Workers)

---

## 2. Dominio Gratuito de AWS (Sin Costo) ⭐

**¡No necesitas comprar dominio!** AWS Elastic Beanstalk te proporciona uno automáticamente.

### Ventajas del Dominio Gratuito

- ✅ **$0 de costo** - Completamente gratis
- ✅ **HTTPS incluido** - Certificado SSL automático
- ✅ **Sin configuración DNS** - Funciona inmediatamente
- ✅ **Perfecto para desarrollo** - No requiere tarjeta de crédito adicional
- ✅ **Válido para producción** - Totalmente funcional

### Formato del Dominio

Cuando crees tu entorno, AWS te asignará automáticamente:

```
https://tu-app-nombre.us-east-1.elasticbeanstalk.com
```

**Ejemplo real:**
```
https://chat-backend-production.us-east-1.elasticbeanstalk.com
```

### ¿Necesitas Dominio Personalizado Después?

Si en el futuro quieres un dominio propio como `chat.academiaapp.com`:
- Puedes comprarlo más tarde
- Agregarlo sin afectar el funcionamiento actual
- Mantener ambos dominios funcionando

**Para esta guía usaremos:** Dominio gratuito de AWS Elastic Beanstalk

---

## 3. Preparar el Proyecto

### 3.1 Verificar Archivos Creados

El proyecto ya tiene los archivos necesarios:

```
backend-chat-openai-worker-profesores/
├── src/main/resources/
│   └── application-production.properties  ✅ Creado
├── .ebextensions/
│   └── 01-app-config.config              ✅ Creado
├── .env.example                          ✅ Creado
└── pom.xml
```

### 3.2 Compilar el JAR

```powershell
cd "c:\Users\Angel FV\Desktop\FORMACION\chat_backend_academia\backend-chat-openai-worker-profesores"

# Compilar (saltar tests para velocidad)
./mvnw clean package -DskipTests

# Verificar JAR creado
ls target/*.jar

# Debe aparecer algo como:
# chat-backend-springboot-workers-profesores-0.0.1-SNAPSHOT.jar
```

### 3.3 Añadir .gitignore (si no existe)

```bash
# Crear .ebignore (archivos que NO subir a AWS)
echo "target/
.git/
.vscode/
*.log
.env" > .ebignore
```

---

## 4. Instalar AWS CLI y EB CLI

### 4.1 Instalar AWS CLI

**Windows (PowerShell como administrador):**

```powershell
# Descargar instalador
msiexec.exe /i https://awscli.amazonaws.com/AWSCLIV2.msi

# Verificar instalación
aws --version
# Debe mostrar: aws-cli/2.x.x
```

### 4.2 Configurar AWS CLI

```powershell
# Configurar credenciales
aws configure

# Responder las preguntas:
# AWS Access Key ID: [Tu Access Key de IAM]
# AWS Secret Access Key: [Tu Secret Key de IAM]
# Default region name: us-east-1
# Default output format: json
```

**¿Cómo obtener Access Keys?**

```
1. AWS Console → IAM → Users → Tu usuario
2. Security credentials → Create access key
3. Use case: Command Line Interface (CLI)
4. Copiar Access Key ID y Secret Access Key
5. ⚠️ GUARDAR en lugar seguro (no se puede recuperar después)
```

### 4.3 Instalar EB CLI

```powershell
# Instalar EB CLI usando pip
pip install awsebcli --upgrade --user

# Verificar instalación
eb --version
# Debe mostrar: EB CLI 3.x.x
```

---

## 5. Crear Aplicación en Elastic Beanstalk

### 5.1 Inicializar Proyecto

```powershell
cd "c:\Users\Angel FV\Desktop\FORMACION\chat_backend_academia\backend-chat-openai-worker-profesores"

# Inicializar EB
eb init

# Responder las preguntas:
# Select a default region: 3 (us-east-1)
# Enter Application Name: chat-backend-academia
# It appears you are using Java. Is this correct?: y
# Select a platform branch: 1 (Corretto 21)
# Do you want to set up SSH: n (opcional, para debugging)
```

### 5.2 Crear Entorno de Producción (Single Instance - GRATIS)

```powershell
# Crear entorno SIN Load Balancer (100% gratis con free tier)
eb create production-env `
  --single `
  --instance-types t3.micro `
  --tags "Environment=Production,Project=ChatBackend"

# Esto tomará 5-10 minutos
# Verás logs en tiempo real del proceso
```

**Resultado esperado:**

```
Creating application version archive "app-xxxx".
Uploading chat-backend-academia/app-xxxx.zip to S3.
Environment details for: production-env
  Application name: chat-backend-academia
  Region: us-east-1
  Deployed Version: app-xxxx
  Environment ID: e-xxxxxxxxxx
  Platform: arn:aws:elasticbeanstalk:us-east-1::platform/Corretto 21 running on 64bit Amazon Linux 2023
  Tier: WebServer-Standard-1.0
  CNAME: production-env.us-east-1.elasticbeanstalk.com
  Updated: 2025-10-24 12:34:56
  Status: Ready
  Health: Green
```

**Importante:** En este modo, la URL será HTTP por defecto:
```
http://production-env.us-east-1.elasticbeanstalk.com
```

Configuraremos HTTPS con Let's Encrypt en los siguientes pasos.

### 5.3 Configurar Variables de Entorno

```powershell
# Configurar todas las variables necesarias
eb setenv `
  SPRING_PROFILES_ACTIVE=production `
  OPENAI_API_KEY=sk-proj-tu-api-key-real-aqui `
  API_WORKERS_URL=https://tu-worker.workers.dev `
  JWT_SECRET_KEY=tu_secret_compartido_con_api_workers `
  ALLOWED_ORIGINS=https://academiaapp.com

# Esto reiniciará el entorno (toma 2-3 minutos)
```

---

## 6. Configurar HTTPS con Let's Encrypt (Gratis) 🔒

**Let's Encrypt** proporciona certificados SSL **gratis para siempre**, con renovación automática cada 90 días.

### 6.1 Habilitar SSH en Elastic Beanstalk

```powershell
# Reinicializar con soporte SSH
eb init

# Cuando pregunte "Do you want to set up SSH?": Y (yes)
# Select a keypair: 
# - Si tienes una: selecciónala
# - Si no: crear nueva (nombre: eb-ssh-key)
```

### 6.2 Conectar a la Instancia EC2

```powershell
# SSH a la instancia
eb ssh

# Ahora estás dentro del servidor EC2 como usuario ec2-user
```

### 6.3 Instalar Certbot (Let's Encrypt)

```bash
# Actualizar paquetes
sudo yum update -y

# Instalar Certbot y Nginx
sudo yum install -y certbot python3-certbot-nginx nginx

# Verificar instalación
certbot --version
# Debe mostrar: certbot 2.x.x
```

### 6.4 Configurar Nginx como Proxy Reverso

```bash
# Crear configuración de Nginx
sudo tee /etc/nginx/conf.d/springboot.conf > /dev/null <<'EOF'
server {
    listen 80;
    server_name production-env.us-east-1.elasticbeanstalk.com;

    location / {
        proxy_pass http://localhost:5000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /actuator/health {
        proxy_pass http://localhost:5000/actuator/health;
        access_log off;
    }
}
EOF

# Verificar sintaxis
sudo nginx -t

# Iniciar Nginx
sudo systemctl enable nginx
sudo systemctl start nginx
```

### 6.5 Obtener Certificado SSL con Certbot

```bash
# Solicitar certificado (reemplaza con tu URL real)
sudo certbot --nginx -d production-env.us-east-1.elasticbeanstalk.com --non-interactive --agree-tos --email tu-email@ejemplo.com

# Certbot automáticamente:
# 1. Valida el dominio
# 2. Obtiene el certificado
# 3. Configura Nginx para HTTPS
# 4. Redirige HTTP → HTTPS
```

**Salida esperada:**

```
Successfully received certificate.
Certificate is saved at: /etc/letsencrypt/live/production-env.../fullchain.pem
Key is saved at:         /etc/letsencrypt/live/production-env.../privkey.pem
This certificate expires on 2026-01-22.
These files will be updated when the certificate renews.
Certbot has set up a scheduled task to automatically renew this certificate in the background.

Deploying certificate
Successfully deployed certificate for production-env... to /etc/nginx/conf.d/springboot.conf
Congratulations! You have successfully enabled HTTPS
```

### 6.6 Configurar Renovación Automática

```bash
# Certbot ya configuró renovación automática, verificar:
sudo systemctl status certbot-renew.timer

# Debe mostrar: active (waiting)

# Probar renovación (dry-run, no renueva realmente)
sudo certbot renew --dry-run

# Debe mostrar: Congratulations, all simulated renewals succeeded
```

### 6.7 Abrir Puerto 443 (HTTPS) en Security Group

```bash
# Salir de SSH
exit

# Desde tu PC, abrir puerto HTTPS en AWS
```

**Desde AWS Console:**

```
1. AWS Console → EC2 → Security Groups
2. Buscar security group con nombre "awseb-e-..." (el de tu environment)
3. Pestaña "Inbound rules" → "Edit inbound rules"
4. "Add rule":
   - Type: HTTPS
   - Protocol: TCP
   - Port: 443
   - Source: 0.0.0.0/0 (Anywhere IPv4)
5. "Save rules"
```

### 6.8 Verificar HTTPS Funcionando

```bash
# Test HTTPS
curl -I https://production-env.us-east-1.elasticbeanstalk.com/actuator/health

# Debe responder:
# HTTP/2 200
# content-type: application/json
```

✅ **¡HTTPS configurado completamente GRATIS!**

---

## 7. Deploy del Backend

### 7.1 Desplegar Aplicación

```powershell
# Asegurarte de estar en el directorio del proyecto
cd "c:\Users\Angel FV\Desktop\FORMACION\chat_backend_academia\backend-chat-openai-worker-profesores"

# Compilar versión final
./mvnw clean package -DskipTests

# Deploy a AWS
eb deploy

# Monitorear logs en tiempo real
eb logs --stream
```

### 7.2 Obtener URL del Dominio Gratuito

```powershell
# Ver información del entorno
eb status

# Ejemplo de output:
# Environment details for: production-env
#   Application name: chat-backend-academia
#   Region: us-east-1
#   Deployed Version: app-xxxx
#   Environment ID: e-xxxxxxxxxx
#   Platform: arn:aws:elasticbeanstalk:us-east-1::platform/Corretto 21
#   Tier: WebServer-Standard-1.0
#   CNAME: production-env.us-east-1.elasticbeanstalk.com  ← Tu dominio gratuito
#   Updated: 2025-10-24 12:34:56
#   Status: Ready
#   Health: Green
```

**Tu URL HTTPS será (después de configurar Let's Encrypt):**
```
https://production-env.us-east-1.elasticbeanstalk.com
```

**¡Guarda esta URL!** La necesitarás para configurar Android.

---

## 8. Verificar HTTPS

### 8.1 Test desde Navegador

```
https://production-env.us-east-1.elasticbeanstalk.com/actuator/health
```

**Respuesta esperada:**

```json
{
  "status": "UP"
}
```

**Verificar certificado SSL:**
- Click en el candado 🔒 del navegador
- Certificado emitido por: Let's Encrypt
- Válido para: production-env.us-east-1.elasticbeanstalk.com
- Válido hasta: [90 días desde hoy]
- Protocolo: TLS 1.3 ✅

### 8.2 Test desde Terminal

```powershell
# Test HTTPS (reemplaza con tu URL real)
curl -I https://production-env.us-east-1.elasticbeanstalk.com/actuator/health

# Debe mostrar:
# HTTP/2 200
# content-type: application/json

# Test redirección HTTP → HTTPS
curl -I http://production-env.us-east-1.elasticbeanstalk.com/actuator/health

# Debe mostrar:
# HTTP/1.1 301 Moved Permanently
# Location: https://production-env.us-east-1.elasticbeanstalk.com/actuator/health
```

### 8.3 Verificar SSL Labs (Opcional)

```
https://www.ssllabs.com/ssltest/analyze.html?d=production-env.us-east-1.elasticbeanstalk.com
```

**Rating esperado:** A (Let's Encrypt con configuración por defecto)

---

## 9. Actualizar Android para Usar HTTPS

### 9.1 Actualizar URL Base

```kotlin
// app/src/main/java/com/example/academiaapp/data/remote/NetworkConfig.kt
object NetworkConfig {
    // Desarrollo local
    private const val BASE_URL_DEV = "http://10.0.2.2:8080"
    
    // Producción con HTTPS ✅ (reemplaza con tu URL real de AWS)
    private const val BASE_URL_PROD = "https://production-env.us-east-1.elasticbeanstalk.com"
    
    val BASE_URL = if (BuildConfig.DEBUG) {
        BASE_URL_DEV
    } else {
        BASE_URL_PROD
    }
}
```

**Importante:** Reemplaza `production-env.us-east-1.elasticbeanstalk.com` con tu URL real obtenida con `eb status`.

### 9.2 Verificar Network Security Config

```xml
<!-- res/xml/network_security_config.xml -->
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Forzar HTTPS en producción -->
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    
    <!-- Permitir HTTP SOLO en localhost (desarrollo) -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

### 9.3 Compilar APK de Producción

```bash
# Desde Android Studio
# 1. Build → Generate Signed Bundle / APK
# 2. Seleccionar APK
# 3. Release variant
# 4. Probar en dispositivo real

# Verificar que use HTTPS
# Logcat debe mostrar: "Request URL: https://chat.academiaapp.com/..."
```

---

## 10. Troubleshooting

### Problema: "Environment health is Red"

```powershell
# Ver logs detallados
eb logs

# Causas comunes:
# 1. Puerto incorrecto (debe ser 5000)
# 2. Health check falla (verificar /actuator/health)
# 3. Variables de entorno faltantes

# Solución:
eb setenv SERVER_PORT=5000
```

### Problema: "502 Bad Gateway"

```powershell
# Verificar que Spring Boot esté escuchando en puerto 5000
eb ssh
sudo tail -f /var/log/web.stdout.log

# Verificar en logs:
# "Tomcat started on port(s): 5000 (http)"
```

### Problema: "Certificate validation pending"

**No aplica con Let's Encrypt.** La validación es automática e instantánea.

### Problema: "Certbot failed to authenticate"

```bash
# Verificar que el dominio apunta a tu EC2
nslookup production-env.us-east-1.elasticbeanstalk.com

# Debe resolverse a la IP pública de tu EC2

# Verificar que puerto 80 está abierto
sudo netstat -tulpn | grep :80

# Si no está abierto, verificar Security Group en AWS Console
```

### Problema: "Certificate expires soon"

```bash
# Verificar timer de renovación automática
sudo systemctl status certbot-renew.timer

# Si está inactivo, habilitarlo
sudo systemctl enable certbot-renew.timer
sudo systemctl start certbot-renew.timer

# Renovar manualmente (si es urgente)
sudo certbot renew
sudo systemctl reload nginx
```

### Problema: "CORS error" en Android

```powershell
# Verificar variable ALLOWED_ORIGINS
eb printenv | grep ALLOWED_ORIGINS

# Debe incluir tu dominio Android
# Si no, actualizar:
eb setenv ALLOWED_ORIGINS=https://tu-dominio-android.com
```

### Problema: "JWT validation failed"

```powershell
# Verificar que JWT_SECRET_KEY sea el mismo en:
# 1. Backend Chat (AWS)
eb printenv | grep JWT_SECRET_KEY

# 2. API Workers (Cloudflare)
# Ver variables en dashboard de Cloudflare Workers

# Deben ser EXACTAMENTE iguales
```

---

## Resumen de Costos Mensuales (100% GRATIS)

### Opción GRATIS: Single Instance + Let's Encrypt 🎉

| Recurso | Costo Primer Año | Costo Después |
|---------|------------------|---------------|
| **EC2 t3.micro** (750 horas/mes) | **$0** ✅ | ~$10/mes |
| **Application Load Balancer** | **$0** ✅ (no usar) | - |
| **Dominio AWS** | **$0** ✅ (elasticbeanstalk.com) | $0 |
| **Certificado SSL (Let's Encrypt)** | **$0** ✅ (gratis siempre) | $0 |
| **Data Transfer** (primer 15 GB) | **$0** ✅ | ~$1-3/mes |
| **TOTAL** | **$0** 🎉 | **~$11-13/mes** |

### Configuración que Usaremos

```powershell
# Single Instance (sin Load Balancer) ← 100% GRATIS primer año
eb create production-env --single --instance-types t3.micro

# Después configuraremos HTTPS con Let's Encrypt (gratis para siempre)
```

### ⚠️ Diferencias vs Load Balancer

| Característica | Con ALB ($16/mes) | Single Instance (GRATIS) |
|----------------|-------------------|--------------------------|
| **Costo primer año** | $16/mes | **$0** ✅ |
| **HTTPS** | Automático | Manual (Let's Encrypt) |
| **Alta disponibilidad** | Sí (multi-AZ) | No |
| **Auto-scaling** | Sí | No |
| **Mejor para** | Producción | Desarrollo/MVP/Testing |

**Para tu caso (rúbrica educativa):** Single Instance + Let's Encrypt es **perfecto y 100% gratis**.

---

## Comandos Útiles de Mantenimiento

```powershell
# Ver estado del entorno
eb status

# Ver logs en tiempo real
eb logs --stream

# Ver configuración actual
eb config

# Ver variables de entorno
eb printenv

# Escalar instancias (aumentar capacidad)
eb scale 2

# Crear snapshot de configuración
eb config save production-config

# SSH a la instancia (para debugging avanzado)
eb ssh

# Terminar entorno (¡cuidado!)
eb terminate production-env
```

---

## Checklist Final ✅

Antes de considerar el despliegue completo, verifica:

- [ ] ✅ Elastic Beanstalk entorno creado (Single Instance)
- [ ] ✅ SSH habilitado (`eb ssh` funciona)
- [ ] ✅ Nginx instalado y funcionando
- [ ] ✅ Certbot instalado
- [ ] ✅ Certificado Let's Encrypt obtenido y configurado
- [ ] ✅ Puerto 443 abierto en Security Group
- [ ] ✅ Variables de entorno configuradas (OPENAI_API_KEY, etc.)
- [ ] ✅ Health check responde: `/actuator/health` → 200 OK
- [ ] ✅ `https://tu-app.elasticbeanstalk.com/actuator/health` accesible desde navegador
- [ ] ✅ Candado 🔒 verde en navegador (certificado Let's Encrypt válido)
- [ ] ✅ Redirección HTTP → HTTPS funcionando
- [ ] ✅ Timer de renovación automática activo (`sudo systemctl status certbot-renew.timer`)
- [ ] ✅ Android app actualizada con BASE_URL HTTPS
- [ ] ✅ Network Security Config fuerza HTTPS
- [ ] ✅ CORS configurado correctamente
- [ ] ✅ JWT validation funcionando

---

## Siguiente Paso: Documentación para Rúbrica

Con el Backend desplegado y securizado con HTTPS (Let's Encrypt), ya cumples el requisito de:

> ✅ **Encriptación de usuarios y contraseñas para autenticación**

**Evidencias para entregar:**
1. ✅ Contraseñas hasheadas con Argon2 (código en `api-workers-profesores/src/shared/security.py`)
2. ✅ HTTPS/TLS en producción (URL: `https://tu-app.us-east-1.elasticbeanstalk.com`)
3. ✅ Certificado SSL válido (Let's Encrypt - gratis y reconocido mundialmente)
4. ✅ Network Security Config forzando HTTPS en Android

**Documento de referencia:** `SEGURIDAD_ENCRIPTACION.md`

**Costo:** **$0/mes** el primer año con Free Tier de AWS 🎉

---

## Contacto y Soporte

Si encuentras problemas durante el despliegue:

1. **Revisar logs:** `eb logs --stream`
2. **Verificar health:** `eb health`
3. **Consultar CloudWatch:** AWS Console → CloudWatch → Log groups → `/aws/elasticbeanstalk/production-env`

---

**¡Despliegue completado! 🎉**

Tu Backend Chat ahora está:
- ✅ Desplegado en AWS
- ✅ Accesible vía HTTPS
- ✅ Certificado SSL válido
- ✅ Dominio personalizado configurado
- ✅ Listo para integrarse con Android
