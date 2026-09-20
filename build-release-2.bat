@echo off
rem 本脚本入公开仓库，故不写死本机绝对路径。
rem 依赖环境变量：REPO_DIR（本仓库根目录）、JDK17_HOME（JDK 17 目录）。
cd /d "%REPO_DIR%"
set "JAVA_HOME=%JDK17_HOME%"
set "GRADLE_OPTS=-Xmx4096m -XX:MaxMetaspaceSize=512m"
call gradlew.bat :app:assembleRelease --offline --no-daemon > "%REPO_DIR%\build-release-2.log" 2>&1
