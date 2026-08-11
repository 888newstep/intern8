@echo off
chcp 65001 >nul
title 推推P5 集群停止脚本

echo ============================================
echo    停止所有集群服务
echo ============================================
echo.

:: 停止所有容器
docker compose down

:: 清理数据卷（可选，注释掉以保留数据）
:: echo 是否清理数据卷？(y/n)
:: set /p CLEAN_VOLUMES=
:: if /i "!CLEAN_VOLUMES!"=="y" (
::     docker compose down -v
::     echo 数据卷已清理
:: )

echo.
echo [OK] 所有服务已停止
echo.
pause