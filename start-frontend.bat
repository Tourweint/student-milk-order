@echo off
title Student-Milk Frontend (Vite dev)
cd /d "%~dp0school-ui"

where node >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Node.js not found in PATH. Please install Node.js first.
  pause
  exit /b 1
)

if not exist "node_modules" (
  echo [INFO] node_modules not found, installing dependencies ...
  call npm install
  if errorlevel 1 (
    echo [ERROR] npm install failed.
    pause
    exit /b 1
  )
)

echo ============================================
echo  Starting frontend: npm run dev
echo  Dev server:        http://localhost:5173
echo  Stop:              close this window (or Ctrl+C)
echo ============================================
echo.
npm run dev
