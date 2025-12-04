# Documentación de herramientas y scripts

C:\Users\Angel FV\Desktop\FORMACION\chat_backend_academia\backend-chat-openai-worker-profesores> 

#  para compilar el proyecto
mvn -v
mvn compile
# Limpiar y compilar
mvn clean compile
# Limpiar y ejecutar tests (compila)
mvn clean test
# Compilar + tests + empaquetar (jar)
mvn clean package
# Verificación completa (incluye tests; útil si hay ITs)
mvn clean verify
# (Opcional) construir sin tests
mvn clean package -DskipTests

# compilar ejecutando  los test y viendo el detalle de cada test

---> ejecuta todos los test desde powershell
mvn --% test -Dsurefire.reportFormat=plain -Dsurefire.useFile=false -DtrimStackTrace=false

        -Dsurefire.reportFormat=plain: imprime también los tests OK (no solo fallos).

        -Dsurefire.useFile=false: vuelca el reporte a la consola en lugar de solo a ficheros.

        -DtrimStackTrace=false: muestra stacktraces completos si hay fallo.

---> ejecutar solo 1 test llamado PlannerPhaseTest: 
mvn --% test -Dsurefire.reportFormat=plain -Dsurefire.useFile=false -DtrimStackTrace=false  -Dtest=PlannerPhaseTest


---> test que tengan ese patrón *Chat*Test  (test que contentan Chat y que terminen por Test)

mvn --% -Dtest=*Chat*Test test -Dsurefire.reportFormat=plain -Dsurefire.useFile=false -DtrimStackTrace=false


# levantar el backend-chat openai en local
mvn spring-boot:run -e -X

spring-boot:run: arranca la app directamente desde el código fuente.
-e: muestra stack traces completos si falla algo.
-X: modo debug de Maven (muchísima traza). Úsalo solo cuando necesites diagnosticar.


# fuerza mock para evitar llamadas a OpenAI, ponerlo en los ficheros de properties
.\mvnw.cmd spring-boot:run -Dopenai.mock=true -Dbackend.debug=true


# configura secret y TTL (temporal en la sesión de PowerShell). en los ficheros de properties

$env:DEBUG = '1'

# Asegúrate de que la carpeta logs existe
if (-not (Test-Path .\logs)) { New-Item -ItemType Directory -Path .\logs | Out-Null }

# Borrar log previo por si acaso
if (Test-Path .\logs\pytest_mediator_chat.log) { Remove-Item .\logs\pytest_mediator_chat.log -Force }


# Ejecutar pytest para el test que quiere verificar el flujo -s para ver prints
# Capturamos la salida y la guardamos a logs\pytest_mediator_chat.log

pytest -q tests/chat/test_mediator_chat.py::test_mediator_list_requests -s 2>&1 | Tee-Object -FilePath .\logs\pytest_mediator_chat.log


PS C:\Users\Angel FV\Desktop\FORMACION\chat_backend_academia\backend-chat-openai-worker-profesores> & mvn "-Dopenai.mock=true" "-Dacademia.api.baseurl=http://localhost:5000" "-Dbackend.debug=true" "-Ddebug.api.proxy=true" spring-boot:run



    -Dopenai.mock=true   --> No llamar a openai
        
    -Dopenai.mock=false (o omitido)
        En local ya no es obligatorio exportar la clave como variable de entorno.
        Crea `src/main/resources/application-local.properties` (ya git-ignored) con el contenido:

        spring.profiles.active=dev
        openai.api.key.dev=sk-<TU-OPENAI-KEY>

        Este repositorio incluye un script (`documentacion/run-pruebas.ps1`) que cargará
        automáticamente `application-local.properties` antes de arrancar el backend.
        Así puedes ejecutar:

        mvn --% spring-boot:run -Dspring-boot.run.arguments="--openai.mock=false --academia.api.baseurl=http://localhost:5000 --backend.debug=true --debug.api.proxy=true"




    -Dacademia.api.baseurl=...
        Indica la URL base que ApiProxyService usa para hacer las llamadas "tool" a la API de la academia (p. ej. http://localhost:5000 en tus pruebas).

    -Dbackend.debug=true   --> activa logs


Uso de display_name en JWT (oct-2025)
-------------------------------------
- Si el access token incluye `display_name` (o `name`/`preferred_username`), el backend-chat lo usará para personalizar el prompt y NO hará prefetch del endpoint de "mi perfil".
- Si no viene ese claim, mantiene el prefetch como antes.
- No se añade el nombre al token delegado que usa el backend-chat para llamar al API (no es necesario para autorización).




#ejecutar los test desde el api-workers-profesores:

cd 'C:\Users\Angel FV\Desktop\FORMACION\api-workers-profesores'; if (Test-Path '.\.venv\Scripts\Activate.ps1') { . .\.venv\Scripts\Activate.ps1 } else { Write-Host 'No virtualenv activation script found, proceeding without activation' }; $env:MEDIATOR_URL = 'http://localhost:8080'; Write-Host "MEDIATOR_URL=$env:MEDIATOR_URL"; pytest -q tests/chat/test_mediator_chat.py -s



levantar el api:
$env:JWT_SECRET_KEY = 'super-secret-key'
$env:DEBUG = '1' 
python .\app.py 

eejcutar los test
$env:MEDIATOR_URL = 'http://localhost:8080' 
pytest -q tests/chat/test_mediator_chat_admin_academia.py -s


