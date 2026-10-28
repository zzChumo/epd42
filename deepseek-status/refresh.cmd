@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ============================================
echo  DeepSeek 状态墨水屏 - Windows 启动器
echo ============================================
echo.
echo 提示：要指定 Python 路径，可以这样传参：
echo    refresh.cmd -Python "C:\Python311\python.exe"
echo    或用环境变量 DS_EPD_PYTHON，或在同目录建 python.txt
echo.
echo 连不上墨水屏时，先运行诊断：
echo    refresh.cmd -Diagnose
echo.
where powershell >nul 2>nul
if errorlevel 1 (
  echo [错误] 未找到 Windows PowerShell
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0refresh.ps1" %*
echo.
echo （脚本已结束）
pause >nul
