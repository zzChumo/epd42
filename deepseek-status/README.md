# DeepSeek 状态墨水屏（EPD_4in2 · 400×300）

把 DeepSeek 账户状态显示在 **4.2 寸黑白电子墨水屏**（微雪 EPD_4in2 / UC8176，
参考 [pengwon/epd42](https://github.com/pengwon/epd42) 的 nRF51 BLE 价签）上，
通过命令行定时刷新（默认 **每 60 秒**）。

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
status_display.py   CLI 主程序（默认 60s 循环；--once 只刷一次）
renderer.py         400×300 4 级灰阶渲染 + 双平面(0x10/0x13)编码 + 预览图
ds42_ble.py         BLE 传输（服务 62750001-… / 特征 62750002-…，EPD_4in2 driver=0x01）
refresh.sh          Linux Shell 控制端
refresh.ps1         PowerShell 控制端（脚本方式调用）
refresh.cmd         Windows 双击启动器（带 ExecutionPolicy Bypass，结束后暂停）
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
- Windows 上如果双击 `refresh.ps1` 一闪而过：Windows 默认禁止运行未签名脚本 + 出错时窗口立即关闭，
  请改用 **`refresh.cmd`**（或按上面命令手动执行 PowerShell）
- 无硬件/无网络时可 `--transport none --mock` 只生成预览图

## 用法

```bash
# Linux / macOS
echo 'sk-你的APIKey' > .dstoken
# Windows PowerShell（推荐，写成纯 ASCII 避免编码问题）
Set-Content -Path .dstoken -Value 'sk-你的APIKey' -Encoding ascii
# 注：Windows PowerShell 用 > 重定向会写成 UTF-16 文件，本工具也已兼容（会自动识别编码）

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

## 连接策略（默认一次性，不占用连接）

默认策略：**每轮「连上 → INIT → 推图 → 立刻断开」**，不在后台占着蓝牙连接。

- 好处：不长时间占用 BLE 连接（避免「占着茅坑不拉屎」），其他程序/手机也能连设备；
- 代价：设备断开后如果进入休眠，需要 NFC/无线充唤醒才能再连（参考固件的设计）；
- 想改成跨轮复用连接：加 `--keep-connected`。

推送失败会自动重连重试（默认 2 次，`--retries N` 可调）。

## 硬件说明

- 目标硬件：nRF51 BLE 墨水屏价签（固件来自 pengwon/epd42），黑白双色版本，驱动 `EPD_4in2`（UC8176）。
- BLE 服务 `62750001-d828-918d-fb46-b6c11c675aec`，特征值 `62750002-…`；包格式 `[cmd] + payload`（≤20B）。
- 4 级灰阶：发送 `0x10` / `0x13` 两个 15000B 平面，然后加载自定义 LUT（`0x20–0x25`）并 `DISPLAY`。
- 设备名以 `NRF_EPD_` 开头（可用 `--device-prefix` 修改）。

## 「今日使用」怎么才算得准

DeepSeek **API Key（sk-…）只能查余额，查不了用量**（余额接口 `/user/balance`，无用量字段）。
所以本工具提供两种方式：

1. **默认：本机观测累计** —— 每次刷新比较余额变化，下降就累加；**只能统计程序运行期间**的消费，
   首次运行之前当天已消费的部分统计不到（第一次运行会显示 0.00，之后才会涨）。
   - 想让当天数据完整：让它在 00:00 后就一直跑着（默认 60 秒刷新正合适）。
   - 想手动重新校准：`./refresh.sh --reset-today`（以当前余额为起点重新累计）。
2. **可选：Platform 会话 userToken（推荐，能拿到真实今日消费）** ——
   登录 `platform.deepseek.com` 后从浏览器 Local Storage 取 `userToken`，写进同目录 `.dptoken`，
   工具会调用平台用量接口 `usage/by_api_key/cost`（按北京时间当天）得到真实金额并显示在屏幕上。
   该接口是网页私有接口，可能变动；失败会自动回退到本机观测。

## 连不上墨水屏时：先跑诊断

```powershell
refresh.cmd -Diagnose          # Windows（等价于 diagnose.cmd 双击）
python diagnose.py             # Linux / macOS
```

诊断会输出：
1. Python 路径、版本，`requests` / `PIL` / `bleak` 是否就绪
2. 扫描到的 BLE 设备列表（目标设备名以 `NRF_EPD_` 开头会标记出来）
3. 连接后设备的所有 service / characteristic（用于确认 UUID 是否匹配）
4. 试发一次 INIT（不刷图），验证写入通道

把诊断输出贴出来，就能定位是「设备没醒 / 服务 UUID 不对 / 特征值写入失败 / 依赖缺失」哪一类问题。

## Windows 蓝牙不稳定怎么办

BLE 在 Windows 上偶发掉线很常见（尤其笔记本内置网卡 + 2.4GHz WiFi 干扰）。本工具已加：

- **持久连接**：每轮复用同一条连接，不重复扫描
- **写包间隔 2ms**：缓解控制器缓冲区拥塞
- **失败自动重试**：默认重连重试 2 次（`--retries N` 可调）

还可以这样改善：

1. 设备离电脑近一点，中间不要隔墙；
2. 路由器 2.4GHz 与蓝牙互相干扰，尽量用 5GHz WiFi；
3. 设备管理器 → 蓝牙适配器 → 电源管理 → **取消勾选「允许计算机关闭此设备以节省电源」**；
4. 外接一个 USB 蓝牙适配器（比内置网卡稳很多）；
5. 设备休眠后需要 **NFC / 无线充** 唤醒（这是参考固件的行为，不是 bug）。

## Windows 常见问题

**Q: 提示 `ModuleNotFoundError: No module named 'requests'` / 用的 Python 是商店占位版怎么办？**
Windows 自带的 `python3.exe`（位于 `...\Microsoft\WindowsApps\`）只是应用商店的占位程序，没有 pip、也没有我们需要的依赖。
处理方式（任选）：

1. 到 [python.org](https://www.python.org/downloads/) 安装正式版，**安装时勾选 Add python.exe to PATH**；
2. 然后用正式版装依赖（注意用到完整路径）：
   ```powershell
   "C:\Users\<你的用户名>\AppData\Local\Programs\Python\Python313\python.exe" -m pip install pillow requests bleak
   ```
3. 启动时指定解释器：`refresh.cmd -Python "C:\Users\...\Python313\python.exe"`（也可只给目录）；
4. 或者到 **设置 → 应用 → 高级应用设置 → 应用执行别名**，把 `python.exe` / `python3.exe` 的商店别名关掉。

启动器会自动跳过商店占位版、优先挑一个**已经装好依赖**的 Python（并打印实际使用的路径）。

## 备注

- `.dptoken` 是网页登录会话 token，不是 API Key，请勿泄露；`.gitignore` 已忽略它。
- 字体：Noto Sans CJK SC，SIL Open Font License 1.1。
