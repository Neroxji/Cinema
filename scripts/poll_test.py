#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P2 · N 并发轮询座位图压测（测试计划 §5 P2，NFR4：座位图轮询 3 秒/次、P95 ≤ 1s；task-08 交付物）。

流程（任务卡口径）：
  1. scheduler 登录；
  2. 经管理端接口自动创建专用 影片/影厅/场次（即被轮询的目标场次）；
  3. 批量注册/登录 N 观众（默认 500，各持独立会话 Cookie）；
  4. asyncio 并发轮询 GET /api/showings/{id}/seats 持续 ~30 秒（每人请求间 0 间隔，
     500 用户 × 3s/次 ≈ 全场每秒 ~500 请求的持续读压力）；
  5. 统计 P50 / P95 / P99 / 错误率（code!=0 或 HTTP 非 200 记为错误）；
  6. 输出 PASS/FAIL 与判定口径（P95 ≤ 1s = PASS），退出码 0/1（供流水线判定）。

用法：
  python poll_test.py --base http://localhost:8080 --users 500 --duration 30

依赖：Python 3.10+ 与 aiohttp（pip install aiohttp）。
"""

import argparse
import asyncio
import json
import sys
import time
from datetime import datetime, timedelta

try:
    import aiohttp
except ImportError:
    print("缺少依赖 aiohttp，请先执行：pip install aiohttp")
    sys.exit(2)

SCHEDULER_USER = "scheduler"
SCHEDULER_PASS = "scheduler123"
AUDIENCE_PASS = "p2pass123456"


def now_ms() -> float:
    return time.perf_counter() * 1000.0


def percentile(sorted_values: list[float], p: float) -> float:
    """最近邻秩百分位（值已升序，单位 ms）。"""
    if not sorted_values:
        return 0.0
    idx = min(len(sorted_values) - 1, max(0, round(p / 100.0 * len(sorted_values) - 1)))
    return sorted_values[idx]


class ApiClient:
    """单用户会话（自管 JSESSIONID，独立 Cookie，互不串号；与 stress_test.py 同法）。"""

    def __init__(self, session: aiohttp.ClientSession, base: str):
        self.session = session
        self.base = base.rstrip("/")
        self.cookie: str | None = None

    async def request(self, method: str, path: str, body: dict | None = None) -> dict:
        headers = {"Content-Type": "application/json"}
        if self.cookie:
            headers["Cookie"] = "JSESSIONID=" + self.cookie
        async with self.session.request(
                method, self.base + path, json=body, headers=headers,
                timeout=aiohttp.ClientTimeout(total=60)) as resp:
            set_cookie = resp.headers.get("Set-Cookie")
            if set_cookie and set_cookie.startswith("JSESSIONID="):
                value = set_cookie.split(";", 1)[0]
                self.cookie = value[len("JSESSIONID="):]
            raw = await resp.text()
        try:
            return json.loads(raw or "{}")
        except json.JSONDecodeError:
            return {"code": -1, "message": f"HTTP {resp.status} 非 JSON 响应", "raw": raw[:200]}

    async def get(self, path: str) -> dict:
        return await self.request("GET", path)

    async def post(self, path: str, body: dict | None = None) -> dict:
        return await self.request("POST", path, body or {})


async def ensure_showing(scheduler: ApiClient) -> int:
    """scheduler 建专用场次（影片/影厅/场次各一），返回 showingId。"""
    stamp = datetime.now().strftime("%H%M%S%f")[:10]
    resp = await scheduler.post("/api/admin/movies",
                                {"title": f"P2压测影片{stamp}", "durationMin": 90})
    assert resp.get("code") == 0, f"创建影片失败：{resp}"
    movie_id = resp["data"]["id"]
    resp = await scheduler.post("/api/admin/halls", {"name": f"P2压测厅{stamp}", "hallType": "STANDARD"})
    assert resp.get("code") == 0, f"创建影厅失败：{resp}"
    hall_id = resp["data"]["id"]
    start = (datetime.now() + timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%S")
    resp = await scheduler.post("/api/admin/showings", {
        "movieId": movie_id, "hallId": hall_id, "startTime": start,
        "priceType": "ORIGINAL", "priceParams": {"basePrice": 1.00}})
    assert resp.get("code") == 0, f"创建场次失败：{resp}"
    showing_id = resp["data"]["id"]
    seat_map = await scheduler.get(f"/api/showings/{showing_id}/seats")
    assert seat_map.get("code") == 0, f"读取座位图失败：{seat_map}"
    print(f"[fixture] 已创建专用场次 showingId={showing_id}"
          f"（座位 {len(seat_map['data']['seats'])} 个），为轮询目标")
    return showing_id


async def register_and_login(session: aiohttp.ClientSession, base: str, username: str,
                             reg_semaphore: asyncio.Semaphore,
                             login_semaphore: asyncio.Semaphore) -> ApiClient:
    client = ApiClient(session, base)
    # 注册串行（信号量=1）：H2 2.x IDENTITY 计数为事务快照语义，同表并发 INSERT 会撞主键
    # （压测夹具阶段串行即可；被测的并发轮询阶段不受影响）
    async with reg_semaphore:
        reg = await client.post("/api/auth/register", {"username": username, "password": AUDIENCE_PASS})
        assert reg.get("code") == 0, f"注册失败 {username}：{reg}"
    async with login_semaphore:
        login = await client.post("/api/auth/login", {"username": username, "password": AUDIENCE_PASS})
        assert login.get("code") == 0, f"登录失败 {username}：{login}"
    return client


async def poll_worker(client: ApiClient, showing_id: int, deadline: float,
                      latencies: list[float], errors: list[str]) -> None:
    """单用户轮询循环：3 秒/次（NFR4 口径），持续至 deadline；每次记录时延/错误。"""
    while True:
        if now_ms() >= deadline:
            break
        t0 = now_ms()
        resp = await client.get(f"/api/showings/{showing_id}/seats")
        elapsed = now_ms() - t0
        if resp.get("code") == 0:
            latencies.append(elapsed)
        else:
            errors.append(f"code={resp.get('code')} msg={str(resp.get('message'))[:60]}")
        # 3 秒/次轮询节奏（NFR4：座位图页 3 秒/次）；减去本次耗时对齐固定周期
        sleep_s = 3.0 - (now_ms() - t0) / 1000.0
        if sleep_s > 0:
            await asyncio.sleep(sleep_s)


async def main() -> int:
    parser = argparse.ArgumentParser(description="P2 · N 并发轮询座位图压测（NFR4 P95≤1s）")
    parser.add_argument("--base", default="http://localhost:8080", help="服务地址（默认 http://localhost:8080）")
    parser.add_argument("--users", type=int, default=500, help="并发轮询用户数（默认 500）")
    parser.add_argument("--duration", type=int, default=30, help="持续时长秒（默认 30）")
    parser.add_argument("--login-concurrency", type=int, default=25, help="登录并发上限（默认 25；注册串行）")
    args = parser.parse_args()

    print("=" * 72)
    print("P2 · N 并发轮询座位图压测（NFR4：P95 ≤ 1s）")
    print(f"  服务地址 : {args.base}")
    print(f"  并发用户 : {args.users}")
    print(f"  持续时长 : {args.duration}s（每用户 3 秒/次轮询，NFR4 口径）")
    print(f"  开始时间 : {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 72)

    connector = aiohttp.TCPConnector(limit=0)
    # 会话隔离关键：禁用 aiohttp 会话级自动 Cookie（DummyCookieJar）+ 各用户自管 JSESSIONID
    # （与 P1 同坑：共享 jar 会令 N 请求共用最后登录者会话）
    t_total0 = now_ms()
    async with aiohttp.ClientSession(connector=connector,
                                     cookie_jar=aiohttp.DummyCookieJar()) as session:
        # ---------- 1. scheduler 登录 + 专用场次 ----------
        scheduler = ApiClient(session, args.base)
        login = await scheduler.post("/api/auth/login",
                                     {"username": SCHEDULER_USER, "password": SCHEDULER_PASS})
        assert login.get("code") == 0, f"scheduler 登录失败：{login}"
        showing_id = await ensure_showing(scheduler)

        # ---------- 2. 批量注册/登录 N 观众（注册串行防 H2 IDENTITY 主键冲突；登录并发） ----------
        t_reg0 = now_ms()
        reg_semaphore = asyncio.Semaphore(1)
        login_semaphore = asyncio.Semaphore(args.login_concurrency)
        stamp = datetime.now().strftime("%H%M%S")
        tasks = [register_and_login(session, args.base, f"p2{stamp}u{i}", reg_semaphore, login_semaphore)
                 for i in range(args.users)]
        users = await asyncio.gather(*tasks)
        t_reg1 = now_ms()
        print(f"[step2] 批量注册+登录 {len(users)} 观众完成，耗时 {(t_reg1 - t_reg0) / 1000:.2f}s")

        # ---------- 3. 并发轮询（每用户 3 秒/次）持续 duration 秒 ----------
        print(f"[step3] {len(users)} 用户并发轮询 GET /api/showings/{showing_id}/seats "
              f"持续 {args.duration}s ……")
        latencies: list[float] = []
        errors: list[str] = []
        deadline = now_ms() + args.duration * 1000.0
        await asyncio.gather(*[poll_worker(u, showing_id, deadline, latencies, errors)
                               for u in users])

    # ---------- 4. 统计 ----------
    total_requests = len(latencies) + len(errors)
    error_rate = len(errors) / total_requests * 100.0 if total_requests else 0.0
    lat_sorted = sorted(latencies)
    p50 = percentile(lat_sorted, 50)
    p95 = percentile(lat_sorted, 95)
    p99 = percentile(lat_sorted, 99)
    avg = sum(latencies) / len(latencies) if latencies else 0.0
    t_total1 = now_ms()
    total_seconds = (t_total1 - t_total0) / 1000.0

    print("[step4] 轮询统计：")
    print(f"    总请求数          : {total_requests}（成功 {len(latencies)} / 失败 {len(errors)}）")
    print(f"    平均耗时          : {avg:.1f} ms")
    print(f"    P50               : {p50:.1f} ms")
    print(f"    P95               : {p95:.1f} ms")
    print(f"    P99               : {p99:.1f} ms")
    print(f"    错误率            : {error_rate:.2f}%")
    if errors:
        from collections import Counter
        print(f"    错误样例          : {Counter(errors).most_common(3)}")
    print(f"    实际吞吐          : ≈ {total_requests / max(args.duration, 1):.0f} 请求/秒")

    # ---------- 5. 结论 ----------
    fail_reasons = []
    if p95 > 1000.0:
        fail_reasons.append(f"P95={p95:.1f}ms > 1000ms（NFR4 阈值）")
    if error_rate > 1.0:
        fail_reasons.append(f"错误率 {error_rate:.2f}% > 1%（轮询应全程稳定可用）")
    verdict = "PASS" if not fail_reasons else "FAIL"
    print("=" * 72)
    print(f"P2 结果：{verdict}")
    if fail_reasons:
        for reason in fail_reasons:
            print(f"  失败原因：{reason}")
    print(f"  判定口径：{args.users} 并发用户 3 秒/次持续 {args.duration}s 轮询，"
          f"P95={p95:.1f}ms ≤ 1000ms 且错误率 {error_rate:.2f}% ≤ 1%")
    print(f"  总耗时：{total_seconds:.2f}s（含夹具注册/登录）")
    print(f"  结束时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 72)
    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
