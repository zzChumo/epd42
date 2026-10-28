#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""BLE 连接诊断：检查 Python 依赖、扫描设备、列出服务/特征值、试发 INIT。"""
from __future__ import annotations

import asyncio
import sys


def check_deps():
    print("=== Python 环境 ===")
    print("executable:", sys.executable)
    print("version   :", sys.version.split()[0])
    for mod in ("requests", "PIL", "bleak"):
        try:
            m = __import__(mod)
            ver = getattr(m, "__version__", "?")
            print(f"{mod:8s}: OK  {ver}")
        except ImportError as e:
            print(f"{mod:8s}: 缺失（{e}）")
    print()


async def main_async(prefix: str):
    try:
        from bleak import BleakClient, BleakScanner
    except ImportError:
        print("缺少 bleak：请运行 python -m pip install bleak")
        return 1

    print(f"=== 扫描 BLE 设备（10 秒，关注名称前缀 {prefix}）===")
    devices = await BleakScanner.discover(timeout=10.0)
    target = None
    for d in devices:
        mark = ""
        if (d.name or "").startswith(prefix):
            target = d
            mark = "  <== 目标设备"
        print(f"  {d.address}  {d.name or '(无名)'}{mark}")
    if not devices:
        print("  （没有扫描到任何设备；请确认蓝牙已开启、设备已唤醒）")
    print()

    if target is None:
        print(f"未找到名称以 {prefix} 开头的设备。")
        print("建议：用 NFC / 无线充唤醒墨水屏后再试；或加 --device-address 指定 MAC。")
        return 2

    print(f"=== 连接 {target.name} ({target.address}) ===")
    client = BleakClient(target)
    await client.connect()
    print("已连接")
    try:
        from ds42_ble import CHAR_UUID, _find_char, _iter_services
        services = getattr(client, "services", None)
        for svc in _iter_services(services):
            print(f"  service {getattr(svc, 'uuid', '?')}")
            for c in getattr(svc, "characteristics", []) or []:
                print(f"    char {getattr(c, 'uuid', '?')} props={getattr(c, 'properties', '?')}")
        ch = _find_char(services, CHAR_UUID)
        print()
        print("特征值匹配:", "OK" if ch is not None else "失败（UUID 不匹配）")
        if ch is not None:
            print("=== 试发 INIT(driver=0x01) ===")
            await client.write_gatt_char(ch, bytes([0x01, 0x01]), response=True)
            print("INIT 已发送（屏幕无反应属正常，本诊断不发图像）")
    finally:
        await client.disconnect()
        print("已断开")
    return 0


def main():
    prefix = "NRF_EPD_"
    for i, a in enumerate(sys.argv[1:]):
        if a == "--prefix" and i + 1 < len(sys.argv[1:]):
            prefix = sys.argv[i + 2]
    check_deps()
    try:
        code = asyncio.run(main_async(prefix))
    except Exception as e:
        print(f"诊断失败：{type(e).__name__}: {e}")
        code = 3
    return code


if __name__ == "__main__":
    sys.exit(main())
