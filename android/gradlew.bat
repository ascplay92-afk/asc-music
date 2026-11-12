@echo off
setlocal
set APP_HOME=%~dp0
if not defined JAVA_HOME goto findJavaFromPath
set JAVA_EXE=%JAVA_HOME%\bin\java.exe
if exist "%JAVA_EXE%" goto execute
echo ERROR: JAVA_HOME points to a missing Java installation. 1>&2
goto fail

:findJavaFromPath
set JAVA_EXE=java.exe
%JAVA_EXE% -version >nul 2>&1
if "%ERRORLEVEL%"=="0" goto execute
echo ERROR: Java was not found. Set JAVA_HOME or add java.exe to PATH. 1>&2
goto fail

:execute
"%JAVA_EXE%" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
if "%ERRORLEVEL%"=="0" goto end
:fail
exit /b 1
:end
endlocal
