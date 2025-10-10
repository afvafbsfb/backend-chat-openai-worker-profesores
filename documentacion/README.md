# Documentación de herramientas y scripts

#ejecutar el backend en local
cd 'C:\Users\Angel FV\Desktop\FORMACION\chat_backend_academia\backend-chat-openai-worker-profesores'; mvn "-Dopenai.mock=true" "-Dacademia.api.baseurl=http://localhost:5000" "-Dbackend.debug=true" spring-boot:run


#ejecutar los test desde el api-workers-profesores:

cd 'C:\Users\Angel FV\Desktop\FORMACION\api-workers-profesores'; if (Test-Path '.\.venv\Scripts\Activate.ps1') { . .\.venv\Scripts\Activate.ps1 } else { Write-Host 'No virtualenv activation script found, proceeding without activation' }; $env:MEDIATOR_URL = 'http://localhost:8080'; Write-Host "MEDIATOR_URL=$env:MEDIATOR_URL"; pytest -q tests/chat/test_mediator_chat.py -s