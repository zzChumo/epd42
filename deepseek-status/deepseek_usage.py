#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DeepSeek 用量查询（可选）。

注意：**API Key（sk-…）无法访问用量接口**，余额接口只有 /user/balance。
真实「今日消费」需要 DeepSeek Platform 的浏览器会话 userToken（可选功能）：
  GET https://platform.deepseek.com/api/v0/usage/by_api_key/cost
      ?start=<unix>&end=<unix>&tz=<offset 秒>
  Header: Authorization: Bearer <userToken>, x-client-platform: web

本模块只做「尽力解析」：在返回 JSON 里找带 cost 的日期桶求和，不依赖固定 schema。
"""
from __future__ import annotations

import time
from datetime import datetime, timedelta, timezone

import requests
PLATFORM_BASE = "https://platform.deepseek.com/api/v0/usage"


def _sum_costs(obj, today: str, found: list[bool]) -> float:
    """递归求当天 cost 之和；found 用于区分「0 元」与「没找到字段」。"""
    total = 0.0
    if isinstance(obj, dict):
        date = str(obj.get("date") or obj.get("day") or obj.get("date_str") or "")
        cost = None
        for key in ("cost", "total_cost", "cost_cny", "total_cost_cny", "amount_cost"):
            if key in obj and isinstance(obj[key], (int, float, str)):
                try:
                    cost = float(obj[key])
                except (ValueError, TypeError):
                    cost = None
                if cost is not None:
                    found[0] = True
                    break
        if cost is not None and (not date or today in date):
            total += cost
        for v in obj.values():
            total += _sum_costs(v, today, found)
    elif isinstance(obj, list):
        for v in obj:
            total += _sum_costs(v, today, found)
    return total


def fetch_today_cost(user_token: str, timeout: int = 20):
    """返回 (原始响应文本 or None, 今日消费金额 or None)。"""
    now = datetime.now().astimezone()
    start = datetime(now.year, now.month, now.day, tzinfo=now.tzinfo)
    params = {
        "start": int(start.timestamp()),
        "end": int(now.timestamp()),
        "tz": int((now.utcoffset() or timedelta(0)).total_seconds()),
    }
    headers = {
        "Authorization": f"Bearer {user_token}",
        "Accept": "application/json",
        "x-client-platform": "web",
        "User-Agent": "Mozilla/5.0",
    }
    r = requests.get(f"{PLATFORM_BASE}/by_api_key/cost", params=params, headers=headers, timeout=timeout)
    raw = r.text
    r.raise_for_status()
    data = r.json()
    found = [False]
    cost = _sum_costs(data, now.strftime("%Y-%m-%d"), found)
    return raw, (round(cost, 6) if found[0] else None)


if __name__ == "__main__":
    # 自检：用假 JSON 验证解析逻辑
    sample = {
        "code": 0,
        "data": {"biz_data": [
            {"api_key_id": 1, "date": "2026-10-28", "cost": 0.12},
            {"api_key_id": 2, "date": "2026-10-28", "cost": 0.34},
            {"api_key_id": 1, "date": "2026-10-27", "cost": 9.99},
        ]},
    }
    found = [False]
    print("解析自检:", _sum_costs(sample, "2026-10-28", found), "found=", found[0])
