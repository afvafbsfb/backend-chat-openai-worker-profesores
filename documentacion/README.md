# Documentación de herramientas y scripts


#  para compilar el proyecto
mvn -v
mvn compile

# levantar el backend-chat openai en local
mvn spring-boot:run -e -X

spring-boot:run: arranca la app directamente desde el código fuente.
-e: muestra stack traces completos si falla algo.
-X: modo debug de Maven (muchísima traza). Úsalo solo cuando necesites diagnosticar.

# desde la raíz del repo api-workers-profesores. Levantar API (usa RDS dev: DB_ENV=developmentAWS)
$env:DB_ENV = 'developmentAWS';
$env:JWT_SECRET_KEY = 'mi_secret_app_local_larga';
$env:JWT_DELEGATION_SECRET = 'mi_secret_delegacion_local_larga';
$env:DEBUG = '1';
$env:FLASK_DEBUG = '1';
$env:APP_ENV = 'development';
if (Test-Path '.venv\Scripts\Activate.ps1') { . '.venv\Scripts\Activate.ps1' }
python -u main.py

o bien usar:

.\scripts\run_api_with_cleanup.ps1 -JwtSecret 'mi_secret_app_local_larga' -DelegationSecret 'mi_secret_delegacion_local_larga'


Levantar backend-chat (apuntar a http://localhost:5000)
coge las propiedades de los ficheros de properties, ya no es necesario definir antes las vbles de entorno:


ademas de tener el ficheor configurado  application-properties en resources del backend-chat --> ((openai.mock=false))
#$env:DEBUG = '1';
#$env:OPENAI_API_KEY = 'sk-proj-8j.....'
#$env:JWT_DELEGATION_SECRET="mi_secret_delegacion_local_larga";
#$env:JWT_DELEGATION_EXPIRATIONMINUTES = '5' 
#$env:DUMP_SECRETS = '1' 

PS C:\Users\Angel FV\Desktop\FORMACION\chat_backend_academia\backend-chat-openai-worker-profesores> mvn spring-boot:run -e -X
######mvn spring-boot:run -e -X




# fuerza mock para evitar llamadas a OpenAI, ponerlo en los ficheros de properties
.\mvnw.cmd spring-boot:run -Dopenai.mock=true -Dbackend.debug=true




# configura secret y TTL (temporal en la sesión de PowerShell). en los ficheros de properties


$env:DEBUG = '1'

# Asegúrate de que la carpeta logs existe
if (-not (Test-Path .\logs)) { New-Item -ItemType Directory -Path .\logs | Out-Null }

# Borrar log previo por si acaso
if (Test-Path .\logs\pytest_mediator_chat.log) { Remove-Item .\logs\pytest_mediator_chat.log -Force }


#ejecutar el backend en local

mvn --% spring-boot:run -Dspring-boot.run.jvmArguments="-Dbackend.debug=true -Djwt.delegation.secret=mi_secret_delegacion_local_larga" -Dspring-boot.run.arguments="--openai.mock=false --academia.api.baseurl=http://localhost:5000 --backend.debug=true --debug.api.proxy=true" 2>&1 | Tee-Object -FilePath .\logs\mediator_stdout.log



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




#ejecutar los test desde el api-workers-profesores:

cd 'C:\Users\Angel FV\Desktop\FORMACION\api-workers-profesores'; if (Test-Path '.\.venv\Scripts\Activate.ps1') { . .\.venv\Scripts\Activate.ps1 } else { Write-Host 'No virtualenv activation script found, proceeding without activation' }; $env:MEDIATOR_URL = 'http://localhost:8080'; Write-Host "MEDIATOR_URL=$env:MEDIATOR_URL"; pytest -q tests/chat/test_mediator_chat.py -s



levantar el api:
$env:JWT_SECRET_KEY = 'super-secret-key'
$env:DEBUG = '1' 
python .\app.py 

eejcutar los test
$env:MEDIATOR_URL = 'http://localhost:8080' 
pytest -q tests/chat/test_mediator_chat_admin_academia.py -s


