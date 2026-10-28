#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DeepSeek 状态墨水屏（EPD_4in2 / 400×300）命令行控制端。

用法示例：
    ./refresh.sh                  # 每 60 秒刷新一次
    ./refresh.sh --once           # 只刷新一次
    ./refresh.sh --mock --preview --transport none   # 无网络/无硬件时预览
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time
from datetime import datetime, timedelta, timezone

try:
    import requests
except ImportError:
    raise SystemExit(
        "缺少 requests。请先安装依赖：\n"
        "  python -m pip install pillow requests bleak\n"
        "（如果 pip 提示找不到，检查是否用了 Windows 应用商店的 python3.exe 占位程序）"
    )

import renderer

HERE = os.path.dirname(os.path.abspath(__file__))
BEIJING = timezone(timedelta(hours=8))
API_URL = "https://api.deepseek.com/user/balance"

# 峰谷时段（北京时间）：00:30–08:30 为谷时，其余为峰时
OFFPEAK_START = (0, 30)
OFFPEAK_END = (8, 30)


def now_cn() -> datetime:
    return datetime.now(BEIJING)


def is_offpeak(dt: datetime) -> bool:
    m = dt.hour * 60 + dt.minute
    start = OFFPEAK_START[0] * 60 + OFFPEAK_START[1]
    end = OFFPEAK_END[0] * 60 + OFFPEAK_END[1]
    return start <= m < end


def _decode_text_file(data: bytes) -> str:
    """稳健解码文本：兼容 Windows PowerShell 默认的 UTF-16(BOM)、UTF-8(BOM)、GBK。"""
    if data.startswith(b"\xef\xbb\xbf"):
        return data.decode("utf-8-sig", errors="replace")
    if data.startswith(b"\xff\xfe") or data.startswith(b"\xfe\xff"):
        return data.decode("utf-16", errors="replace")
    for enc in ("utf-8", "gbk"):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            continue
    # 没有 BOM 但含大量 0x00 → 大概率是 UTF-16LE
    if data.count(b"\x00") > len(data) // 4:
        return data.decode("utf-16-le", errors="replace")
    return data.decode("utf-8", errors="replace")


def read_optional_token(path: str):
    if not os.path.isabs(path):
        path = os.path.join(HERE, path)
    if not os.path.exists(path):
        return None
    try:
        return read_token(path)
    except SystemExit:
        return None


def read_token(path: str) -> str:
    if not os.path.isabs(path):
        path = os.path.join(HERE, path)
    if not os.path.exists(path):
        raise SystemExit(f"未找到 {path}，请把 DeepSeek API Key 写入该文件（一行即可）")
    data = open(path, "rb").read()
    text = _decode_text_file(data).replace("\x00", "")
    token = text.strip().strip('"').strip("'").strip()
    # 只取第一行，去掉可能残留的提示文字
    token = token.splitlines()[0].strip() if token else ""
    if token.startswith("#") or token.startswith("//"):
        token = ""
    if not token:
        raise SystemExit(f"{path} 是空的（或只有注释行）")
    return token


def fetch_balance(token: str):
    """返回 (原始响应文本, 余额 float)。"""
    resp = requests.get(
        API_URL,
        headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
        timeout=20,
    )
    raw = resp.text
    resp.raise_for_status()
    data = resp.json()
    infos = data.get("balance_infos") or []
    if not infos:
        raise RuntimeError("余额接口未返回 balance_infos")
    cny = next((i for i in infos if i.get("currency") == "CNY"), infos[0])
    return raw, float(cny.get("total_balance") or 0)


def load_state(path: str) -> dict:
    if not os.path.isabs(path):
        path = os.path.join(HERE, path)
    try:
        return json.load(open(path, "r", encoding="utf-8"))
    except Exception:
        return {}


def save_state(path: str, state: dict):
    if not os.path.isabs(path):
        path = os.path.join(HERE, path)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=2)


def update_today_used(state: dict, balance: float, today: str) -> tuple[float, dict]:
    """累计「本机观测到的」当天消费：余额下降累加，余额上升视为充值（不动已用量）。

    注意：保留 state 里的其他字段（displayed_balance 等），否则「无变化跳过刷屏」会失效。
    """
    if state.get("date") != today:
        new_state = dict(state)
        new_state.update({"date": today, "today_used": 0.0, "last_balance": balance})
        return 0.0, new_state

    used = float(state.get("today_used", 0.0))
    last = state.get("last_balance")
    # 兼容旧版本 state（只有 start_balance）
    if last is None and "start_balance" in state:
        used = max(0.0, float(state["start_balance"]) - balance)
    elif last is not None:
        delta = float(last) - balance
        if delta > 0:            # 消费
            used += delta
        # delta <= 0：充值或未变，不加
    used = round(max(0.0, used), 6)
    new_state = dict(state)
    new_state.update({"date": today, "today_used": used, "last_balance": balance})
    return used, new_state


def stamp() -> str:
    return now_cn().strftime("%Y-%m-%d %H:%M:%S")


def log(msg: str):
    print(f"[{now_cn().strftime('%H:%M:%S')} 北京] {msg}", flush=True)


def render_frame(balance: float, used: float, refreshed: str, peak: bool):
    return renderer.render(
        balance_text=f"￥{balance:.2f}",
        today_used_text=f"￥{used:.2f}",
        refreshed_text=f"上次刷新 {refreshed}",
        peak=peak,
    )


async def do_refresh(args, cycle: int, session=None):
    """刷新一次；返回 (是否成功, session)。"""
    t = now_cn()
    peak = not is_offpeak(t)
    name = "梁文峰" if peak else "梁文谷"
    window = "峰时 08:30–00:30" if peak else "谷时 00:30–08:30"

    log(f"===== 第 {cycle} 次刷新 @ {t.strftime('%Y-%m-%d %H:%M:%S')} =====")
    log(f"计费时段：{name}（{window}）")

    if args.mock:
        raw = '{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"12.34","granted_balance":"0.00","topped_up_balance":"12.34"}]}'
        balance = 12.34
        log(f"余额API原文（mock）：{raw}")
    else:
        token = read_token(args.token_file)
        try:
            raw, balance = fetch_balance(token)
        except Exception as e:
            log(f"余额API请求失败：{e}")
            return False, session
        log(f"余额API原文：{raw}")

    today = t.strftime("%Y-%m-%d")
    state = load_state(args.state_file)
    if args.reset_today:
        state = {"date": today, "today_used": 0.0, "last_balance": balance}
        log("已重置今日起点（--reset-today）")
    used_local, state = update_today_used(state, balance, today)

    # 可选：用 Platform 会话 token 查真实今日消费（API Key 无法查用量）
    used, used_source = used_local, "本机观测"
    platform_token = read_optional_token(args.platform_token_file)
    if platform_token:
        try:
            from deepseek_usage import fetch_today_cost
            raw_usage, cost = fetch_today_cost(platform_token)
            if raw_usage:
                log(f"用量API原文：{raw_usage}")
            if cost is not None:
                used, used_source = cost, "平台用量"
            else:
                log("平台用量接口未返回今日数据，回退本机观测值")
        except Exception as e:
            log(f"平台用量接口失败，回退本机观测值：{e}")
    else:
        log(f"（未提供 {args.platform_token_file}，今日使用为本机观测累计，可能不含首次运行前的消费）")

    state["last_refresh"] = t.strftime("%Y-%m-%d %H:%M:%S")
    save_state(args.state_file, state)

    log(f"余额 ￥{balance:.2f} ｜ 今日使用 ￥{used:.2f}（{used_source}）")

    # 余额与用量都没变化 → 不刷屏（墨水屏刷新慢且伤屏）
    prev_b = state.get("displayed_balance")
    prev_u = state.get("displayed_used")
    if (not args.force and prev_b is not None and prev_u is not None
            and abs(float(prev_b) - balance) < 1e-9
            and abs(float(prev_u) - used) < 1e-9):
        log("余额与用量均未变化，跳过本次刷屏（如需强制刷新加 --force）")
        state["last_attempt"] = t.strftime("%Y-%m-%d %H:%M:%S")
        save_state(args.state_file, state)
        return True, session

    img = render_frame(balance, used, t.strftime("%Y-%m-%d %H:%M:%S"), peak)
    plane10, plane13 = renderer.to_planes(img)

    if args.preview:
        out = os.path.join(HERE, "preview", f"frame_{t.strftime('%Y%m%d_%H%M%S')}.png")
        renderer.save_preview(img, out, scale=2)
        log(f"预览图已保存：{out}")

    if args.transport == "none":
        log("未推送硬件（--transport none）")
        state["displayed_balance"] = balance
        state["displayed_used"] = used
        save_state(args.state_file, state)
        return True, session

    try:
        from ds42_ble import Epd42Session
    except ImportError:
        log("未安装 bleak，无法使用蓝牙推送：pip install bleak（或加 --transport none 只渲染）")
        return False, session

    try:
        if args.keep_connected:
            # 长连接模式：连上后跨轮复用
            if session is None:
                session = Epd42Session(args.device_prefix, args.driver, args.device_address, args.retries)
            await session.push(plane10, plane13)
            log("已推送到墨水屏（长连接模式，下一轮复用）")
            state["displayed_balance"] = balance
            state["displayed_used"] = used
            save_state(args.state_file, state)
            return True, session
        else:
            # 一次性模式（默认）：连上 → 推图 → 断开，不占用蓝牙连接
            one = Epd42Session(args.device_prefix, args.driver, args.device_address, args.retries)
            await one.push_once(plane10, plane13)
            log("已推送到墨水屏（本轮连接已释放）")
            state["displayed_balance"] = balance
            state["displayed_used"] = used
            save_state(args.state_file, state)
            return True, None
    except Exception as e:
        log(f"蓝牙推送失败：{type(e).__name__}: {e}")
        if getattr(args, "debug", False):
            import traceback
            log(traceback.format_exc())
        log("提示：设备可能已休眠（需 NFC/无线充唤醒），或蓝牙不稳定；本轮结束后会自动重试下一轮。")
        try:
            if session is not None:
                await session.disconnect()
        except Exception:
            pass
        return False, None


async def run_loop(args):
    cycle = 0
    session = None
    while True:
        cycle += 1
        try:
            ok, session = await do_refresh(args, cycle, session)
        except Exception as e:
            log(f"本次刷新异常：{e}")
        if args.once:
            if session is not None:
                await session.disconnect()
            return
        log(f"下次刷新：{args.interval} 秒后（Ctrl+C 退出）")
        await asyncio.sleep(args.interval)


def main():
    ap = argparse.ArgumentParser(description="DeepSeek 状态墨水屏控制端（EPD_4in2, 400x300）")
    ap.add_argument("--once", action="store_true", help="只刷新一次（默认每 60 秒循环）")
    ap.add_argument("--interval", type=int, default=60, help="刷新间隔秒数（默认 60）")
    ap.add_argument("--token-file", default=".dstoken", help="API Key 文件（默认同目录 .dstoken）")
    ap.add_argument("--state-file", default=".dsstate.json", help="本地状态文件（默认同目录 .dsstate.json）")
    ap.add_argument("--platform-token-file", default=".dptoken", help="可选：Platform 会话 userToken 文件（可查真实今日消费）")
    ap.add_argument("--reset-today", action="store_true", help="把当天已用量清零，以当前余额为起点重新累计")
    ap.add_argument("--transport", choices=["ble", "none"], default="ble", help="输出方式：ble 蓝牙 / none 仅渲染")
    ap.add_argument("--device-prefix", default="NRF_EPD_", help="蓝牙设备名前缀")
    ap.add_argument("--device-address", default=None, help="可选：直接指定 BLE MAC 地址（跳过扫描）")
    ap.add_argument("--preview", action="store_true", help="同时保存 2 倍预览图到 preview/")
    ap.add_argument("--driver", type=int, default=1, choices=[1, 2, 3], help="1=EPD_4in2(默认) 2=EPD_4in2_V2 3=EPD_4in2b_V2")
    ap.add_argument("--mock", action="store_true", help="使用假数据（调试排版/预览用）")
    ap.add_argument("--debug", action="store_true", help="出错时打印完整 traceback")
    ap.add_argument("--force", action="store_true", help="即使余额/用量未变化也强制刷新屏幕")
    ap.add_argument("--retries", type=int, default=2, help="蓝牙推送失败后的重连重试次数（默认 2）")
    ap.add_argument("--keep-connected", action="store_true", help="保持蓝牙长连接跨轮复用（默认每轮推完即断开）")
    args = ap.parse_args()

    log("DeepSeek 状态墨水屏控制端启动")
    log(f"间隔 {args.interval}s ｜ 传输 {args.transport} ｜ token 文件 {args.token_file}")
    if args.transport == "ble":
        try:
            import bleak  # noqa: F401
            log(f"bleak 已就绪（{getattr(bleak, '__version__', '?')}）")
        except ImportError:
            log("错误：当前 Python 缺少 bleak，无法使用蓝牙推送。")
            log(f"请运行： {sys.executable} -m pip install pillow requests bleak")
            log("（先加 --transport none 可以只渲染预览图）")
            return

    try:
        asyncio.run(run_loop(args))
    except KeyboardInterrupt:
        log("已停止")


if __name__ == "__main__":
    main()
