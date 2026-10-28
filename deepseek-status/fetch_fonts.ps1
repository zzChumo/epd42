# 下载 Noto Sans CJK SC 字体到 fonts/（字体较大，未直接放进仓库）
$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot
New-Item -ItemType Directory -Force -Path "fonts" | Out-Null
$base = "https://github.com/notofonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese"
foreach ($f in @("NotoSansCJKsc-Regular.otf", "NotoSansCJKsc-Bold.otf")) {
  $dst = Join-Path "fonts" $f
  if (Test-Path -LiteralPath $dst) { Write-Host "已存在：$dst"; continue }
  Write-Host "下载 $f …"
  Invoke-WebRequest -Uri "$base/$f" -OutFile $dst -UseBasicParsing
}
Write-Host "字体就绪"
