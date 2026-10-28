# DeepSeek余额及用量・峰谷状态显示（EPD_4in2）

将您的DeepSeek账户状态显示在该项目原仓库所支持的4.2寸墨水屏上，
通过命令行CLI定时刷新（默认60s）。

## 画面内容（4 行）

| 行 | 内容 | 说明 |
|---|---|---|
| 1 | **梁文峰 / 梁文谷** | 按当前北京时间判断：`00:30–08:30` 为谷时 → 梁文谷；其余峰时 → 梁文峰 |
| 2 | `余额 ￥0.00` | 来自 DeepSeek `/user/balance` 接口（取 CNY total_balance） |
| 3 | `今日使用 ￥0.00` | 两种来源：① 可选 Platform userToken → 平台真实用量；② 否则本机观测累计（余额下降累加，充值不误计） |
| 4 | `上次刷新 YYYY-MM-DD hh:mm:ss` | 小字，北京时间 |

排版：4 级灰阶（0/85/170/255），Noto Sans CJK SC，400×300 1:1 点阵。

## 目录

```
status_display.py   CLI 主程序（`-once`只刷一次）
renderer.py         400×300 4 级灰阶渲染 + 双平面(0x10/0x13)编码 + 预览图
ds42_ble.py         BLE 传输（服务 62750001-… / 特征 62750002-…，EPD_4in2 driver=0x01）
refresh.sh          Linux Shell 控制端
refresh.ps1         PowerShell 控制端（脚本方式调用）
refresh.cmd         Windows CMD控制端（带 ExecutionPolicy Bypass，结束后暂停）
fonts/              Noto Sans CJK SC Regular / Bold（SIL OFL）
.dstoken            DeepSeek API Key（一行；见 .dstoken.example，已被 .gitignore 忽略）
.dsstate.json       今日使用量的本地状态（自动生成）
preview/            --preview 生成的预览图
```

## 依赖

```bash
pip install pillow requests bleak
```

- `bleak`：BLE 通信（Linux 需要 BlueZ；Windows 10+ 原生支持）
- Windows 如双击 `refresh.ps1` 一闪而过：Windows 默认禁止运行未签名脚本 + 出错时窗口立即关闭，
  请改用 **`refresh.cmd`**（或按上面命令手动执行 PowerShell）
- 无硬件时可使用`--transport none --mock`生成预览图

## 用法

```bash
# Linux / macOS
echo 'sk-你的APIKey' > .dstoken
# Windows PowerShell（推荐，写成纯 ASCII 避免编码问题）
Set-Content -Path .dstoken -Value 'sk-你的APIKey' -Encoding ascii
./refresh.sh                          # 每 60 秒刷新（默认）
./refresh.sh --once                   # 只刷新一次
./refresh.sh --interval 120           # 改间隔
./refresh.sh --mock --preview --transport none   # 离线预览排版
.\refresh.ps1                         # Windows PowerShell（可加 -Once -Mock -Preview -Transport none）
```

每次刷新终端会打印：

```
[14:09:43 北京] ===== 第 1 次刷新 @ 2026-10-28 14:09:43 =====
[14:09:43 北京] 计费时段：梁文峰（峰时 08:30–00:30）
[14:09:43 北京] 余额API原文：{"is_available":true,"balance_infos":[...]}
[14:09:43 北京] 余额 ￥12.34 ｜ 今日使用 ￥0.00
[14:09:43 北京] 已推送到墨水屏
```

## 连接策略

默认在推送后立即断开蓝牙链接。

- 改为跨轮复用连接：`--keep-connected`。

推送失败会自动重连重试（默认 2 次，`--retries N` 可调）。

## 硬件说明

- 目标硬件：nRF51 BLE 墨水屏标签（固件来自 pengwon/epd42），黑白双色版本，驱动 `EPD_4in2`（UC8176）。
- BLE 服务 `62750001-d828-918d-fb46-b6c11c675aec`，特征值 `62750002-…`；包格式 `[cmd] + payload`（≤20B）。
- 4 级灰阶：发送 `0x10` / `0x13` 两个 15000B 平面，然后加载自定义 LUT（`0x20–0x25`）并 `DISPLAY`。
- 设备名以 `NRF_EPD_` 开头（可用 `--device-prefix` 修改）。

## 余额使用量说明

默认每次刷新比较余额变化，下降则自动累加；只统计程序运行期间消费。
   - 手动重新校准当前使用量：`./refresh.sh --reset-today`（以当前余额为起点重新累计）或修改.dsstate.json。

## 诊断

```powershell
refresh.cmd -Diagnose          # Windows
python diagnose.py             # Linux / macOS
```

## so fuck u, Microsoft

Windows 在相关更新后默认的 `python3.exe`（位于 `...\Microsoft\WindowsApps\`）为Microsoft Store版本，在不明真相的情况下莫名其妙地安装可能会导致原有的Python被替换。

处理方式：

启动时指定解释器`refresh.cmd -Python "C:\Users\...\Python313\python.exe"`或到 **设置 → 应用 → 高级应用设置 → 应用执行别名**关闭`python.exe` / `python3.exe` 的商店别名。

## 备注

- `.dptoken` 是网页登录会话 token，不是 API Key，请勿泄露。
- 字体使用Noto Sans CJK SC，SIL Open Font License 1.1。
