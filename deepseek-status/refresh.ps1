# DeepSeek 状态墨水屏 —— PowerShell 控制端
# 读取同目录 .dstoken（DeepSeek API Key），默认每 60 秒刷新一次。
#
# 指定 Python 的几种方式（优先级从高到低）：
#   1. 命令行参数：  .\refresh.ps1 -Python "C:\Python313\python.exe"
#   2. 环境变量：    set DS_EPD_PYTHON=C:\Python313\python.exe
#   3. 同目录 python.txt（第一行写完整路径）
#   4. PATH 里的 python / py -3（自动跳过 Windows 应用商店占位版）
#   5. 常见安装位置自动探测（同样跳过商店占位版）
#
# 双击运行请用 refresh.cmd（会带 -ExecutionPolicy Bypass 并暂停窗口）
param(
  [switch]$Once,
  [int]$Interval = 60,
  [switch]$Mock,
  [switch]$Preview,
  [ValidateSet("ble","none")]
  [string]$Transport = "ble",
  [string]$TokenFile = ".dstoken",
  [string]$StateFile = ".dsstate.json",
  [string]$Python = "",
  [switch]$Diagnose,
  [switch]$Debug
)

$ErrorActionPreference = "Stop"
$exitCode = 0

function Pause-IfNeeded {
  if ($Host.Name -eq "ConsoleHost" -and -not $env:DS_EPD_NO_PAUSE) {
    Write-Host ""
    Read-Host "按回车键退出" | Out-Null
  }
}

function Test-StoreAlias([string]$path) {
  if (-not $path) { return $false }
  return ($path -match "WindowsApps") -or ($path -match "Microsoft\\WindowsApps")
}

function Test-PythonDeps([string]$exe) {
  try {
    $code = "import sys; import requests; import PIL; print('OK')"
    $out = & $exe -c $code 2>$null
    return ("$out".Trim() -eq "OK")
  } catch { return $false }
}

function Normalize-PythonPath([string]$p) {
  if (-not $p) { return $null }
  $p = $p.Trim().Trim('"')
  if ($p -eq "") { return $null }
  if (Test-Path -LiteralPath $p -PathType Container) {
    foreach ($name in @("python.exe", "python3.exe")) {
      $cand = Join-Path $p $name
      if (Test-Path -LiteralPath $cand) { return $cand }
    }
    return $null
  }
  if (Test-Path -LiteralPath $p -PathType Leaf) { return $p }
  return $null
}

function Resolve-Python([string]$Explicit) {
  $tried = @()

  # ── 1/2/3：显式指定 / 环境变量 / python.txt（这些即使缺依赖也会用，并给出安装提示）──
  $preferred = $null
  if ($Explicit) { $preferred = Normalize-PythonPath $Explicit }
  if (-not $preferred -and $env:DS_EPD_PYTHON) { $preferred = Normalize-PythonPath $env:DS_EPD_PYTHON }
  if (-not $preferred) {
    $cfg = Join-Path $PSScriptRoot "python.txt"
    if (Test-Path -LiteralPath $cfg) {
      $line = (Get-Content -LiteralPath $cfg -TotalCount 1)
      $preferred = Normalize-PythonPath $line
      if (-not $preferred) { Write-Warning "python.txt 里的路径无效，继续自动查找：$line" }
    }
  }
  if ($preferred) {
    return @{ Exe = $preferred; Base = @(); Deps = (Test-PythonDeps $preferred); Source = "手动指定" }
  }

  # ── 4/5：自动探测，跳过商店占位版，优先带依赖的 ──
  $candidates = @()
  foreach ($name in @("python3", "python")) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd -and -not (Test-StoreAlias $cmd.Source)) { $candidates += @{ Exe = $cmd.Source; Base = @() } }
    elseif ($cmd) { $tried += "跳过商店占位版：$($cmd.Source)" }
  }
  $py = Get-Command py -ErrorAction SilentlyContinue
  if ($py) { $candidates += @{ Exe = $py.Source; Base = @("-3") } }

  $patterns = @(
    "$env:LOCALAPPDATA\Programs\Python\Python*\python.exe",
    "C:\Python*\python.exe",
    "$env:ProgramFiles\Python*\python.exe",
    "${env:ProgramFiles(x86)}\Python*\python.exe"
  )
  foreach ($pat in $patterns) {
    Get-ChildItem -Path $pat -ErrorAction SilentlyContinue |
      Sort-Object FullName -Descending |
      ForEach-Object { $candidates += @{ Exe = $_.FullName; Base = @() } }
  }

  $firstUsable = $null
  foreach ($c in $candidates) {
    if (Test-PythonDeps $c.Exe) {
      return @{ Exe = $c.Exe; Base = $c.Base; Deps = $true; Source = "自动探测" }
    }
    if (-not $firstUsable) { $firstUsable = $c }
  }
  if ($firstUsable) {
    return @{ Exe = $firstUsable.Exe; Base = $firstUsable.Base; Deps = $false; Source = "自动探测（缺依赖）" }
  }

  $hint = ($tried -join "`n")
  throw "未找到可用的 Python 3。`n$hint`n`n请安装 Python 3（https://www.python.org/downloads/ ，安装时勾选 Add to PATH），`n或用 -Python ""C:\完整路径\python.exe"" 指定，或设置环境变量 DS_EPD_PYTHON。`n如果 PATH 里只有 Windows 应用商店的 python 占位程序，请到 设置 → 应用 → 高级应用设置 → 应用执行别名 里关掉它。"
}

try {
  $pyInfo = Resolve-Python $Python
  $pyExe = $pyInfo.Exe
  $pyBase = $pyInfo.Base

  if ($pyInfo.Source -like "跳过*" -or (Test-StoreAlias $pyExe)) {
    Write-Warning "检测到 Windows 应用商店的 Python 占位程序：$pyExe"
  }
  Write-Host "Python: $pyExe $($pyBase -join ' ')  [$($pyInfo.Source)]" -ForegroundColor DarkGray

  if (-not $pyInfo.Deps) {
    Write-Host ""
    Write-Host "这个 Python 缺少依赖，请先执行：" -ForegroundColor Yellow
    Write-Host "    `"$pyExe`" -m pip install pillow requests bleak" -ForegroundColor Yellow
    Write-Host ""
    if (Test-StoreAlias $pyExe) {
      Write-Host "提示：这是 Windows 应用商店的占位版 python，通常没有 pip。" -ForegroundColor Yellow
      Write-Host "建议安装 python.org 的正式版，或关掉 设置 → 应用 → 高级应用设置 → 应用执行别名 里的 python 占位项。" -ForegroundColor Yellow
    }
    throw "缺少 Python 依赖（requests / Pillow / bleak）"
  }

  if ($Diagnose) {
    Write-Host "=== 运行 BLE 诊断 ===" -ForegroundColor Cyan
    Push-Location $PSScriptRoot
    try {
      $diagArgs = $pyBase + @("diagnose.py")
      & $pyExe @diagArgs
      $exitCode = $LASTEXITCODE
    } finally { Pop-Location }
    if (Test-StoreAlias $pyExe) {
      Write-Warning "当前使用的是应用商店占位版 Python：$pyExe"
    }
    Pause-IfNeeded
    exit $exitCode
  }

  $argsList = @()
  if ($Once) { $argsList += "--once" }
  $argsList += @("--interval", "$Interval")
  if ($Mock) { $argsList += "--mock" }
  if ($Preview) { $argsList += "--preview" }
  if ($Debug) { $argsList += "--debug" }
  $argsList += @("--transport", $Transport)
  $argsList += @("--token-file", $TokenFile)
  $argsList += @("--state-file", $StateFile)

  $allArgs = $pyBase + @("status_display.py") + $argsList
  Push-Location $PSScriptRoot
  try {
    & $pyExe @allArgs
    $exitCode = $LASTEXITCODE
  } finally {
    Pop-Location
  }
} catch {
  Write-Host ""
  Write-Host "出错了：$($_.Exception.Message)" -ForegroundColor Red
  $exitCode = 1
}

Pause-IfNeeded
exit $exitCode
