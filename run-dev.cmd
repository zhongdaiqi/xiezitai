@echo off
rem ============================================================
rem  写字台 (xiezitai) 本地开发启动脚本
rem  - H2 内存库，无需 MySQL，无需 Docker
rem  - 监听 8080
rem  - 双击即可运行；Ctrl+C 停止
rem ============================================================
if "%JAVA_HOME%"=="" set "JAVA_HOME=D:\Java\jdk-17"

rem 覆盖沙箱/环境注入的 SERVER__PORT，避免端口被改到随机值
set "SERVER__PORT=8080"
set "SERVER_PORT=8080"

cd /d "%~dp0"

if not exist "%~dp0target\xiezitai.jar" (
    echo [ERROR] 未找到 target\xiezitai.jar
    echo         请先执行:  mvn package -DskipTests
    pause
    exit /b 1
)

echo 正在启动写字台 ... 完成后访问 http://localhost:8080
"%JAVA_HOME%\bin\java.exe" -jar "%~dp0target\xiezitai.jar" --spring.profiles.active=dev --server.port=8080

pause
