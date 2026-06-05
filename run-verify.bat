@echo off
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
set SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/thedogs_test
set SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver
set SPRING_DATASOURCE_USERNAME=thedogs
set SPRING_DATASOURCE_PASSWORD=thedogs
cd /d C:\Users\mwppl\Desktop\Code\The-Dogs\backend
call mvnw.cmd -Dspotless.skip=true -Dit.datasource.url=jdbc:postgresql://localhost:5432/thedogs_test -Dit.datasource.driver=org.postgresql.Driver verify > C:\Users\mwppl\Desktop\Code\The-Dogs\backend\verify-out.txt 2>&1
echo Exit code: %ERRORLEVEL%
