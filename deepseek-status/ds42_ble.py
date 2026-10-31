#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""EPD42（nRF51 墨水屏价签）BLE 传输层。

协议参考 pengwon/epd42 的 Web 上位机（html/js/main.js）：
  Service        62750001-d828-918d-fb46-b6c11c675aec
  Characteristic 62750002-d828-918d-fb46-b6c11c675aec
  packet = [cmd] + payload，单包 ≤ 20 字节
  SEND_CMD / SEND_DATA 用于向屏幕控制器写命令与数据（每块 ≤ 19 字节）
"""
from __future__ import annotations

import asyncio
import json
import os

SERVICE_UUID = "62750001-d828-918d-fb46-b6c11c675aec"
CHAR_UUID = "62750002-d828-918d-fb46-b6c11c675aec"

CMD_SET_PINS = 0x00
CMD_INIT = 0x01
CMD_CLEAR = 0x02
CMD_SEND_CMD = 0x03
CMD_SEND_DATA = 0x04
CMD_DISPLAY = 0x05
CMD_SLEEP = 0x06

DRIVER_EPD_4IN2 = 0x01       # 对应上位机下拉框里的 "01"

MAX_PACKET = 20
CHUNK = MAX_PACKET - 1       # 19

# 记录上次成功连接的设备 MAC：之后直接连，避免每分钟重复扫描
_CACHE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".dsble.json")

# 4 级灰阶 LUT（与上位机 send4GrayLut 一致）
LUT_4GRAY = [
    (0x20, "000A0000000160141400000100140000000100130A010001000000000000000000000000000000000000"),
    (0x21, "400A0000000190141400000110140A000001A01301000001000000000000000000000000000000000000"),
    (0x22, "400A0000000190141400000100140A000001990C01030401000000000000000000000000000000000000"),
    (0x23, "400A0000000190141400000100140A000001990B04040101000000000000000000000000000000000000"),
    (0x24, "800A0000000190141400000120140A000001501301000001000000000000000000000000000000000000"),
    (0x25, "400A00000001101414000001A01301000001000000000000000000000000000000000000000000000000"),
]


def _iter_services(services):
    """兼容 bleak 各版本：BleakGATTServiceCollection 在 1.x 里没有 __len__/迭代差异。"""
    if services is None:
        return []
    # 1) 直接可迭代
    try:
        return list(services)
    except TypeError:
        pass
    # 2) 内部有 .services 字典
    inner = getattr(services, "services", None)
    if isinstance(inner, dict):
        return list(inner.values())
    if inner is not None:
        try:
            return list(inner)
        except TypeError:
            pass
    # 3) 有 .uuids / get_service
    uuids = getattr(services, "uuids", None)
    if uuids is not None:
        out = []
        for u in uuids:
            try:
                out.append(services.get_service(u))
            except Exception:
                pass
        return out
    return []


def _find_char(services, char_uuid: str):
    """在任意 bleak 版本里找到目标 characteristic。"""
    if services is None:
        return None
    try:
        ch = services.get_characteristic(char_uuid)
        if ch is not None:
            return ch
    except Exception:
        pass
    for svc in _iter_services(services):
        for ch in getattr(svc, "characteristics", []) or []:
            if str(getattr(ch, "uuid", "")).lower() == char_uuid.lower():
                return ch
    return None


class Epd42Ble:
    def __init__(self, name_prefix: str = "NRF_EPD_", scan_timeout: float = 20.0, address: str | None = None,
                 packet_delay: float = 0.002):
        self.name_prefix = name_prefix
        self.scan_timeout = scan_timeout
        self.address = address
        self.packet_delay = packet_delay
        self.client = None
        self.char = None
        self._cached_address = self._load_cached_address()

    # ── 设备地址缓存（减少反复扫描，降低蓝牙协议栈压力）──
    def _load_cached_address(self):
        try:
            with open(_CACHE_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            return data.get("address")
        except Exception:
            return None

    def _save_cached_address(self, address):
        try:
            with open(_CACHE_FILE, "w", encoding="utf-8") as f:
                json.dump({"address": address}, f)
            self._cached_address = address
        except Exception:
            pass

    def _clear_cached_address(self):
        try:
            os.remove(_CACHE_FILE)
        except Exception:
            pass
        self._cached_address = None

    async def connect(self):
        from bleak import BleakClient, BleakScanner  # 延迟导入，便于无蓝牙环境使用 --transport none

        # 1) 先尝试直连：显式 --device-address 优先，其次上次成功连接的缓存地址（不扫描）
        direct = self.address or self._cached_address
        if direct:
            try:
                print(f"[BLE] 直连 MAC {direct}（跳过扫描）")
                self.client = BleakClient(direct)
                await self.client.connect()
                services = getattr(self.client, "services", None)
                self.char = _find_char(services, CHAR_UUID)
                if self.char is None and hasattr(self.client, "get_services"):
                    try:
                        services = await self.client.get_services()
                    except Exception:
                        services = getattr(self.client, "services", None)
                    self.char = _find_char(services, CHAR_UUID)
                if self.char is None:
                    raise RuntimeError("直连成功但未找到 EPD 特征值")
                await self.client.start_notify(self.char, lambda _c, data: None)
                self._save_cached_address(direct)
                print("[BLE] 已连接（直连）")
                return self
            except Exception as e:
                print(f"[BLE] 直连失败：{type(e).__name__}: {e}，改为扫描")
                try:
                    if self.client is not None:
                        await self.client.disconnect()
                except Exception:
                    pass
                self.client = None
                self.char = None
                if not self.address:
                    self._clear_cached_address()

        # 2) 没有可用地址时才扫描（首次运行/缓存失效）
        print(f"[BLE] 扫描设备（名称前缀 {self.name_prefix}）…")
        device = None
        # bleak 各版本扫描 API 有差异，统一用 discover() 再自己筛选
        try:
            found = await BleakScanner.discover(timeout=self.scan_timeout)
        except TypeError:
            found = await BleakScanner.discover(self.scan_timeout)
        for d in found or []:
            if (d.name or "").startswith(self.name_prefix):
                device = d
                break
        if device is None:
            raise RuntimeError(
                f"未找到名称以 {self.name_prefix} 开头的设备"
                "（设备可能已休眠，请用 NFC/无线充唤醒）"
            )
        print(f"[BLE] 连接 {device.name} ({device.address})")
        self.client = BleakClient(device)
        await self.client.connect()

        # bleak 不同版本获取 services 的方式不同，统一走兼容函数
        services = getattr(self.client, "services", None)
        self.char = _find_char(services, CHAR_UUID)
        if self.char is None and hasattr(self.client, "get_services"):
            try:
                services = await self.client.get_services()
            except Exception:
                services = getattr(self.client, "services", None)
            self.char = _find_char(services, CHAR_UUID)
        if self.char is None:
            print("[BLE] 未匹配到 EPD 特征值，设备上的服务如下：")
            for svc in _iter_services(services):
                print(f"  service {getattr(svc, 'uuid', '?')}")
                for ch in getattr(svc, "characteristics", []) or []:
                    print(f"    char {getattr(ch, 'uuid', '?')} props={getattr(ch, 'properties', '?')}")
            raise RuntimeError("未找到 EPD 特征值，请确认固件/服务 UUID")
        await self.client.start_notify(self.char, lambda _c, data: None)
        # 记录本次成功的 MAC，下次直接连、不再扫描
        try:
            self._save_cached_address(self.client.address)
        except Exception:
            pass
        print("[BLE] 已连接")
        return self

    async def write(self, cmd: int, payload: bytes | bytearray | None = None):
        buf = bytearray([cmd])
        if payload:
            buf.extend(payload)
        if len(buf) > MAX_PACKET:
            raise ValueError("BLE 单包超过 20 字节")
        await self.client.write_gatt_char(self.char, bytes(buf), response=True)
        if self.packet_delay:
            await asyncio.sleep(self.packet_delay)

    async def epd_write(self, address: int, data: bytes):
        """向墨水屏控制器写一条命令 + 数据（自动分包）"""
        await self.write(CMD_SEND_CMD, bytes([address]))
        for i in range(0, len(data), CHUNK):
            await self.write(CMD_SEND_DATA, data[i:i + CHUNK])

    async def init_display(self, driver: int = DRIVER_EPD_4IN2):
        await self.write(CMD_INIT, bytes([driver]))

    async def show_4gray(self, plane10: bytes, plane13: bytes):
        """发送双平面 4 级灰阶图像并刷新（耗时约 20~30 秒）"""
        assert len(plane10) == len(plane13) == 15000
        await self.epd_write(0x10, plane10)
        await self.epd_write(0x13, plane13)
        await self.epd_write(0x00, bytes([0x3F]))       # Load LUT from register
        for addr, hexdata in LUT_4GRAY:
            await self.epd_write(addr, bytes.fromhex(hexdata))
        await self.write(CMD_DISPLAY)
        await self.epd_write(0x00, bytes([0x1F]))       # Load LUT from OTP
        print("[BLE] 刷新指令已发送")

    async def clear(self):
        await self.write(CMD_CLEAR)

    async def sleep(self):
        await self.write(CMD_SLEEP)

    async def close(self):
        try:
            if self.client and self.client.is_connected:
                await self.client.disconnect()
        except Exception:
            pass


class Epd42Session:
    """持久 BLE 会话：连接后保持不断开，多轮刷新复用同一条连接。

    设备固件在「广播空闲（无人连接一段时间）」时会自动关机（NFC/无线充唤醒），
    所以每次刷新都断开重连会导致只有第一次能连上。
    """

    def __init__(self, name_prefix: str = "NRF_EPD_", driver: int = DRIVER_EPD_4IN2,
                 address: str | None = None, retries: int = 2):
        self.name_prefix = name_prefix
        self.driver = driver
        self.address = address
        self.retries = retries
        self.dev = Epd42Ble(name_prefix, address=address)
        self.connected = False

    async def ensure(self):
        """确保连接可用；第一次会扫描连接，之后复用。"""
        if self.connected and self.dev.client is not None and self.dev.client.is_connected:
            return self
        self.dev = Epd42Ble(self.name_prefix, address=self.address)
        try:
            await self.dev.connect()
        except Exception:
            self.connected = False
            raise
        try:
            await self.dev.init_display(self.driver)
        except Exception:
            self.connected = False
            raise
        self.connected = True
        return self

    async def push(self, plane10: bytes, plane13: bytes):
        """推送图像；失败自动重连重试（应对 Windows 蓝牙偶发掉线）。"""
        attempt = 0
        while True:
            attempt += 1
            try:
                await self.ensure()
                await self.dev.show_4gray(plane10, plane13)
                return self
            except Exception as e:
                self.connected = False
                try:
                    await self.dev.close()
                except Exception:
                    pass
                if attempt > self.retries:
                    raise
                print(f"[BLE] 推送失败（{type(e).__name__}: {e}），1 秒后重连重试 "
                      f"（{attempt}/{self.retries}）")
                await asyncio.sleep(1.0)
                self.dev = Epd42Ble(self.name_prefix, address=self.address)

    async def push_once(self, plane10: bytes, plane13: bytes):
        """一次性模式：连上 → 推图 → 立刻断开（默认策略，不长期占用连接）。"""
        try:
            await self.push(plane10, plane13)
        finally:
            await self.disconnect()
        return self

    async def disconnect(self):
        self.connected = False
        await self.dev.close()


async def push_image(plane10: bytes, plane13: bytes, name_prefix: str = "NRF_EPD_", driver: int = DRIVER_EPD_4IN2):
    dev = Epd42Ble(name_prefix)
    await dev.connect()
    try:
        await dev.init_display(driver)
        await dev.show_4gray(plane10, plane13)
    finally:
        await dev.close()
