@rem Gradle startup script for Windows (standard Gradle wrapper).
@rem Requires gradle\wrapper\gradle-wrapper.jar, which is NOT committed in this skeleton.
@rem Generate it once with a local Gradle 8.7:  gradle wrapper --gradle-version 8.7
@rem or open the project in Android Studio, which restores the wrapper jar.

@if "%DEBUG%"=="" @echo off
setlocal

set DIRNAME=%~dp0
set APP_HOME=%DIRNAME%
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if not exist "%CLASSPATH%" (
    echo gradle-wrapper.jar is missing. Run "gradle wrapper --gradle-version 8.7" or open in Android Studio.
    exit /b 1
)

if defined JAVA_HOME (set JAVACMD="%JAVA_HOME%\bin\java.exe") else (set JAVACMD=java)

%JAVACMD% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

endlocal
