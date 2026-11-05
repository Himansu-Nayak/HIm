@echo off
echo 🚀 Starting HIm Enterprise Multi-Service Environment...

:: Detect JAVA_HOME if not set or invalid
if "%JAVA_HOME%"=="" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.10"
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

:: Detect Python
set "PYTHON_EXE=python"
where python >nul 2>&1
if errorlevel 1 (
    if exist "C:\Users\%USERNAME%\AppData\Local\Programs\Python\Python312\python.exe" (
        set "PYTHON_EXE=C:\Users\%USERNAME%\AppData\Local\Programs\Python\Python312\python.exe"
    ) else if exist "C:\Users\%USERNAME%\AppData\Local\Programs\Python\Python311\python.exe" (
        set "PYTHON_EXE=C:\Users\%USERNAME%\AppData\Local\Programs\Python\Python311\python.exe"
    )
)

:: 1. Core Java Backend
echo.
echo [1/5] Starting Java Backend (Core Orchestrator)...
start "HIm Backend" cmd /k "cd backend && mvn spring-boot:run "-Dspring-boot.run.profiles=local""

:: Wait for DB initialization
timeout /t 5

:: 2. Go Memory Service
echo.
echo [2/5] Starting Go Memory Service (Vector Storage)...
start "HIm Memory Service" cmd /k "cd services/memory-service && go run main.go"

:: 3. Python Agent Engine
echo.
echo [3/5] Starting Python Agent Engine (LangGraph)...
start "HIm Agent Engine" cmd /k "cd services/agent-engine && %PYTHON_EXE% app/main.py"

:: 4. Next.js 15 Frontend
echo.
echo [4/5] Starting Next.js 15 Frontend (Modern UI)...
start "HIm Next.js Frontend" cmd /k "cd apps/web && npm run dev"

:: 5. Legacy React Frontend
echo.
echo [5/5] Starting Legacy React Frontend (Vite)...
start "HIm Legacy Frontend" cmd /k "cd frontend && npm run dev"

echo.
echo ✅ ALL SERVICES INITIATED!
echo --------------------------------------------------
echo Legacy Frontend:  http://localhost:3000
echo New Frontend UI:  http://localhost:3001
echo Core API:         http://localhost:8080/api
echo Agent Engine:     http://localhost:8001
echo Memory Svc:       http://localhost:8002
echo --------------------------------------------------
echo Keep these windows open while developing.
echo.
pause
