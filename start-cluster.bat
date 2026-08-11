@echo off
chcp 65001 >nul
title 推推P5 集群启动脚本
setlocal enabledelayedexpansion

echo ============================================
echo    推推P5 - 一键启动集群环境
echo    Redis Sentinel + RabbitMQ 镜像集群
echo ============================================
echo.

:: ==================== 1. 检查 Docker ====================
echo [1/6] 检查 Docker 运行状态...
docker info >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Docker 未运行！请先启动 Docker Desktop
    pause
    exit /b 1
)
echo [OK] Docker 运行正常
echo.

:: ==================== 2. 校验环境变量并启动所有服务 ====================
echo [2/6] 校验 Compose 环境变量...
docker compose config --quiet >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Compose 配置校验失败，请检查 .env 或当前终端中的必填变量
    echo         不会回显具体凭证，请使用 docker compose config 单独排查变量缺失
    pause
    exit /b 1
)
echo [OK] Compose 环境变量已就绪
echo.

echo [3/6] 启动所有服务（MySQL + Redis集群 + RabbitMQ集群 + Prometheus + Grafana）...
echo       预计需要 30-60 秒，请耐心等待...
echo.

docker compose up -d

if %errorlevel% neq 0 (
    echo [ERROR] 启动失败，请检查 docker-compose.yml 配置
    pause
    exit /b 1
)
echo [OK] 所有容器已启动
echo.

:: ==================== 3. 等待服务就绪 ====================
echo [4/6] 等待所有服务就绪（健康检查）...
echo.

:: 等待 MySQL 健康
set MAX_RETRY=30
set RETRY_COUNT=0
:wait_mysql
set /a RETRY_COUNT+=1
docker inspect --format "{{.State.Health.Status}}" intern-mysql 2>nul | findstr /I /C:"healthy" >nul
if !errorlevel! equ 0 (
    echo [OK] MySQL 已就绪
) else (
    if !RETRY_COUNT! geq !MAX_RETRY! (
        echo [WARN] MySQL 启动超时，继续等待其他服务...
    ) else (
        timeout /t 2 /nobreak >nul
        goto wait_mysql
    )
)

:: 等待 Redis 主节点就绪
set RETRY_COUNT=0
:wait_redis
set /a RETRY_COUNT+=1
docker inspect --format "{{.State.Health.Status}}" intern-redis-master 2>nul | findstr /I /C:"healthy" >nul
if !errorlevel! equ 0 (
    echo [OK] Redis 主节点已就绪
) else (
    if !RETRY_COUNT! geq !MAX_RETRY! (
        echo [WARN] Redis 主节点启动超时
    ) else (
        timeout /t 2 /nobreak >nul
        goto wait_redis
    )
)

:: 等待 RabbitMQ 就绪
set RETRY_COUNT=0
:wait_rabbitmq
set /a RETRY_COUNT+=1
docker inspect --format "{{.State.Health.Status}}" intern-rabbitmq-1 2>nul | findstr /I /C:"healthy" >nul
if !errorlevel! equ 0 (
    echo [OK] RabbitMQ 已就绪
) else (
    if !RETRY_COUNT! geq !MAX_RETRY! (
        echo [WARN] RabbitMQ 启动超时
    ) else (
        timeout /t 3 /nobreak >nul
        goto wait_rabbitmq
    )
)

echo.
echo ============================================
echo    所有服务已就绪！
echo ============================================
echo.

:: ==================== 4. 验证集群状态 ====================
echo [5/6] 验证集群状态...
echo.

:: 4.1 验证 Redis Sentinel
echo --- Redis Sentinel 集群 ---
for /f "tokens=*" %%a in ('docker exec intern-redis-sentinel-1 redis-cli -p 26379 sentinel get-master-addr-by-name mymaster 2^>nul') do (
    set "line=%%a"
    if defined master_host (
        echo    主节点: !master_host!:!line!
        set "master_host="
    ) else (
        set "master_host=!line!"
    )
)
if not defined master_host (
    echo    哨兵未响应，请检查 sentinel 配置
)

:: 查看哨兵节点数
docker exec intern-redis-sentinel-1 redis-cli -p 26379 sentinel master mymaster 2>nul | findstr /C:"sentinel" >nul
if !errorlevel! equ 0 (
    echo    哨兵数量: 3
    echo    从节点数量: 2
)

:: 4.2 验证 RabbitMQ 集群
echo --- RabbitMQ 集群 ---
docker exec intern-rabbitmq-1 rabbitmqctl cluster_status 2>nul | findstr /C:"rabbit@" >nul
if !errorlevel! equ 0 (
    echo    集群节点数: 3
    echo    镜像策略: 已配置（所有队列自动镜像）
) else (
    echo    RabbitMQ 集群状态查询失败
)

echo.

:: ==================== 5. 输出访问地址 ====================
echo [6/6] 服务访问地址
echo.
echo    应用服务:       http://localhost:8080
echo    RabbitMQ 管理:  http://localhost:15672  （凭证由 .env 注入，不在终端回显）
echo    Prometheus:     http://localhost:9090
echo    Grafana:        http://localhost:3000   （凭证由 .env 注入，不在终端回显）
echo.
echo ============================================
echo    启动应用前的配置
echo ============================================
echo.
echo 在 IDEA 中启动应用时，添加 Program arguments:
echo   --spring.profiles.active=prod
echo.
echo 或者直接运行:
echo   java -jar intern-base-web/target/intern-base-web.jar --spring.profiles.active=prod
echo.
echo ============================================
echo    故障模拟命令
echo ============================================
echo.
echo Redis 主节点宕机测试:
echo   docker stop intern-redis-master
echo   docker exec intern-redis-sentinel-1 redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
echo   docker start intern-redis-master
echo.
echo RabbitMQ 节点宕机测试:
echo   docker stop intern-rabbitmq-1
echo   docker exec intern-rabbitmq-2 rabbitmqctl cluster_status
echo   docker start intern-rabbitmq-1
echo.
echo ============================================
echo    启动完成！按任意键关闭此窗口
echo ============================================
pause >nul
