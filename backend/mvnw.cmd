@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    https://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup batch script, version 3.3.2
@REM
@REM Required ENV vars:
@REM ------------------
@REM   JAVA_HOME - location of a JDK home dir
@REM
@REM Optional ENV vars
@REM -----------------
@REM   MAVEN_OPTS - parameters passed to the Java VM when running Maven
@REM     e.g. to debug Maven itself, use
@REM       set MAVEN_OPTS=-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8000
@REM   MAVEN_SKIP_RC - flag to disable loading of mavenrc files
@REM ----------------------------------------------------------------------------

@IF "%MAVEN_SKIP_RC%" == "" (
  @IF EXIST "%USERPROFILE%\mavenrc_pre.cmd" CALL "%USERPROFILE%\mavenrc_pre.cmd" %*
)

@setlocal

SET ERROR_CODE=0

@IF NOT "%JAVA_HOME%" == "" (
  @IF EXIST "%JAVA_HOME%\bin\java.exe" GOTO init
)

@ECHO Error: JAVA_HOME is not defined correctly. 1>&2
@ECHO   We cannot execute %JAVA_HOME%\bin\java.exe 1>&2
@GOTO error

:init

SET MAVEN_WRAPPER_JAR="%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar"
IF "%MAVEN_PROJECTBASEDIR%"=="" (
  SET MAVEN_WRAPPER_JAR="%~dp0.mvn\wrapper\maven-wrapper.jar"
  SET MAVEN_PROJECTBASEDIR=%~dp0
)

@IF EXIST %MAVEN_WRAPPER_JAR% (
    GOTO execute
)

SET DOWNLOAD_URL="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar"
FOR /F "tokens=1,2 delims==" %%A IN (.mvn\wrapper\maven-wrapper.properties) DO (
    IF "%%A"=="wrapperUrl" SET DOWNLOAD_URL=%%B
)

@ECHO Downloading from: %DOWNLOAD_URL%
@powershell -Command "(New-Object System.Net.WebClient).DownloadFile('%DOWNLOAD_URL%', '%MAVEN_WRAPPER_JAR%')" 2>NUL
@IF "%ERRORLEVEL%"=="0" GOTO execute

@ECHO Failed to download maven-wrapper.jar, please try downloading from https://github.com/apache/maven-wrapper 1>&2
GOTO error

:execute
@CALL "%JAVA_HOME%\bin\java.exe" ^
  %MAVEN_OPTS% ^
  %MAVEN_DEBUG_OPTS% ^
  -classpath %MAVEN_WRAPPER_JAR% ^
  "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" ^
  org.apache.maven.wrapper.MavenWrapperMain %MAVEN_CONFIG% %*

IF ERRORLEVEL 1 GOTO error
GOTO end

:error
SET ERROR_CODE=1

:end
@endlocal & SET ERROR_CODE=%ERROR_CODE%

@IF NOT "%MAVEN_SKIP_RC%"=="" (
  @IF EXIST "%USERPROFILE%\mavenrc_post.cmd" CALL "%USERPROFILE%\mavenrc_post.cmd" %*
)

@EXIT /B %ERROR_CODE%
