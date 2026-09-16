@echo off
setlocal
cd /d "%~dp0"

rem Always prefer a fresh source build. The packaged JAR may be older than src, and a stale
rem class file is exactly how an already-fixed audio bug can appear to come back.
set "BUILD_DIR=%TEMP%\TankTrouble-current"
set "CLASSES=%BUILD_DIR%\classes"
where javac >nul 2>nul
if not errorlevel 1 (
  if not exist "%CLASSES%" mkdir "%CLASSES%"
  dir /s /b "src\main\java\*.java" > "%BUILD_DIR%\sources.txt"
  javac -encoding UTF-8 -cp "target\lib\*" -d "%CLASSES%" @"%BUILD_DIR%\sources.txt" > "%BUILD_DIR%\build.log" 2>&1
  if errorlevel 1 (
    echo Build failed. See "%BUILD_DIR%\build.log".
    type "%BUILD_DIR%\build.log"
    pause
    exit /b 1
  )
  if exist "runtime\bin\javaw.exe" (
    start "" "runtime\bin\javaw.exe" -cp "%CLASSES%;src\main\resources;target\lib\*" tanktrouble.App
    exit /b
  )
  java -cp "%CLASSES%;src\main\resources;target\lib\*" tanktrouble.App
  if errorlevel 1 pause
  exit /b
)

rem javafx-media must be present in target\lib for audio and the opening video. A jar built
rem before it was added to the classpath is missing it, and the game would start without sound.
if not exist "target\lib\javafx-media-17.0.12-win.jar" (
  echo Building: the JavaFX Media runtime is missing from target\lib.
  echo Java 17, Maven, and network access are required for this first build.
  call mvn -B package
  if errorlevel 1 (
    echo Build failed. Run "mvn -B package" for details.
    pause
    exit /b 1
  )
)

if exist "runtime\bin\javaw.exe" (
  start "" "runtime\bin\javaw.exe" -cp "target\tanktrouble-javafx-3.0.2.jar;target\lib\*" tanktrouble.App
  exit /b
)
where java >nul 2>nul
if errorlevel 1 (
  echo Java 17 or newer is required. Use the portable Windows release instead.
  pause
  exit /b 1
)
if not exist "target\tanktrouble-javafx-3.0.2.jar" (
  call mvn -B package
  if errorlevel 1 (
    pause
    exit /b 1
  )
)
java -cp "target\tanktrouble-javafx-3.0.2.jar;target\lib\*" tanktrouble.App
if errorlevel 1 pause
