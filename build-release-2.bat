@echo off
cd /d A:\workspace\repos\rikkahub-agents
set "JAVA_HOME=A:\workspace\tools\jdk17\jdk-17.0.20+8"
set "GRADLE_OPTS=-Xmx4096m -XX:MaxMetaspaceSize=512m"
call gradlew.bat :app:assembleRelease --offline --no-daemon > A:\workspace\repos\rikkahub-agents\build-release-2.log 2>&1
