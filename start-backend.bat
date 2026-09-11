@echo off
title Student-Milk Backend (8090)
cd /d "%~dp0"

where mvn >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Maven not found in PATH. Please install Maven and add it to PATH.
  pause
  exit /b 1
)

echo ============================================
echo  Starting backend:  mvn spring-boot:run
echo  API base:          http://localhost:8090/api
echo  Stop:              close this window (or Ctrl+C)
echo ============================================
echo.
mvn spring-boot:run
