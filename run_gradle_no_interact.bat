@echo off
REM Non-interactive Gradle runner — sets JAVA_HOME and runs gradlew
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"
echo Running gradlew with JAVA_HOME=%JAVA_HOME% > build-output.txt
gradlew.bat clean build --no-daemon --info --stacktrace >> build-output.txt 2>> build-error.txt
exit /b %errorlevel%
