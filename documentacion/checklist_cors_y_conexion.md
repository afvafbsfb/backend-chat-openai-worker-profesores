# Checklist para solucionar problemas de conexión y CORS entre frontend y backend

## 1. Verifica que el backend está levantado y en el puerto correcto
- Accede a http://localhost:8080 (o el puerto configurado) en el navegador.
- Si ves una página de error 404 o 405, el backend está activo.

## 2. Comprueba la URL del backend en el frontend
- Abre el archivo `.env` en la raíz del proyecto frontend (`web-workers-profesores`).
- Asegúrate de que la variable `VITE_CHAT_API_URL` apunte al backend correcto, por ejemplo:
  ```
  VITE_CHAT_API_URL=http://localhost:8080/chat
  ```

## 3. Limpia la caché del navegador o usa modo incógnito
- A veces el navegador guarda información antigua que puede causar errores.

## 4. Reinicia ambos servidores
- Detén y vuelve a arrancar tanto el backend como el frontend.

## 5. Verifica que el puerto no esté ocupado
- Ejecuta en PowerShell:
  ```powershell
  netstat -ano | findstr :8080
  ```
- Si ves alguna línea, anota el PID y finaliza el proceso si es necesario.

## 6. Comprueba la configuración de CORS en el backend
- Asegúrate de que el backend permite peticiones desde la URL del frontend (por ejemplo, `http://localhost:5173`).
- Si cambias el puerto del frontend, actualiza la configuración de CORS.

## 7. Si el error persiste, revisa firewalls, antivirus o proxies
- Algunos programas pueden bloquear puertos o tipos de peticiones.

---

# Script PowerShell para comprobar el puerto y la respuesta OPTIONS

```powershell
# Verifica si el puerto 8080 está en uso
netstat -ano | findstr :8080

# Prueba la respuesta OPTIONS del backend (CORS)
Invoke-WebRequest -Uri "http://localhost:8080/chat" -Method OPTIONS -Headers @{ "Origin" = "http://localhost:5173"; "Access-Control-Request-Method" = "POST" }
```

---

Si sigues teniendo problemas, revisa los logs del backend y del navegador, y asegúrate de que no hay errores de configuración en los archivos `.env` o `application.properties`.
