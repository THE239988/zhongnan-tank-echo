@echo off
rem Starts the authoritative server plus several player windows on this machine.
rem
rem Every player is a separate process: one window per player, all connecting to the single
rem simulation in the server. That is deliberate rather than one window drawing several tanks -
rem a network battle puts the world on the server, and running the host's own window as just
rem another client means the host cannot accidentally take a different code path.
rem
rem Usage:  play-multiplayer.bat [players]      (default 2, max 5)
rem
rem Each window writes a log to logs\. Those logs are the first place to look when a window
rem misbehaves, because a windowed Java process prints nothing to the console.
rem
rem To play across machines instead, run  run.bat server  on one PC and
rem   run.bat client <its address>  on the others.

setlocal
cd /d "%~dp0"

set PLAYERS=%1
if "%PLAYERS%"=="" set PLAYERS=2
if %PLAYERS% GTR 5 set PLAYERS=5
if %PLAYERS% LSS 2 set PLAYERS=2

set PORT=7777
set CLASSES=target\tanktrouble-javafx-3.0.2.jar
set RUNTIME=runtime\bin\javaw.exe
if not exist "%RUNTIME%" set RUNTIME=javaw

if not exist "%CLASSES%" (
  echo Build first: mvn -B package
  pause
  exit /b 1
)

if not exist "logs" mkdir "logs"
del /q "logs\*.log" >nul 2>&1

echo Starting server on port %PORT% ...
start "tank-server" /min "%RUNTIME%" -cp "%CLASSES%;target\lib\*" tanktrouble.net.GameServer %PORT% > "logs\server.log" 2>&1

rem Give the socket a moment to bind before the clients start dialling it.
timeout /t 2 /nobreak >nul

for /L %%i in (1,1,%PLAYERS%) do (
  echo Starting player %%i ...
  start "tank-player-%%i" "%RUNTIME%" -cp "%CLASSES%;target\lib\*" -Dtanktrouble.player=玩家%%i -Dtanktrouble.autoJoin=127.0.0.1:%PORT% tanktrouble.App > "logs\player%%i.log" 2>&1
)

echo.
echo %PLAYERS% player window(s) launched against 127.0.0.1:%PORT%.
echo In each window pick a tank, press 准备, then the host presses 开始对局.
echo Close the minimized "tank-server" window to stop the room.
echo If a window does not reach the battlefield, send logs\player1.log and logs\server.log.
endlocal
