@echo off
rem 本脚本入公开仓库，故不写死本机绝对路径。
rem 依赖环境变量：REPO_DIR（本仓库根目录）、JDK17_HOME（JDK 17 目录）。
cd /d "%REPO_DIR%"
set JAVA_HOME=%JDK17_HOME%
call gradlew.bat -g "%GRADLE_CACHE_DIR%" -Dorg.gradle.jvmargs=-Xmx4g -Dorg.gradle.daemon=false :app:assembleRelease --console=plain > "%REPO_DIR%\release-build.log" 2>&1
