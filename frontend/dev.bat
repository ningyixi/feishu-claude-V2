@echo off
:loop
echo [%date% %time%] Starting Vite dev server...
call npm run dev
echo [%date% %time%] Vite crashed, restarting in 3 seconds...
timeout /t 3 /nobreak >nul
goto loop
