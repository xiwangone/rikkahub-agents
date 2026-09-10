@echo off
cd /d A:\workspace\repos\rikkahub-agents
set JAVA_HOME=A:\workspace\tools\jdk17\jdk-17.0.20+8
call gradlew.bat -g A:\workspace\tools\gradle-cache -Dorg.gradle.jvmargs=-Xmx4g -Dorg.gradle.daemon=false :app:assembleRelease --console=plain > A:\workspace\repos\rikkahub-agents\release-build.log 2>&1
