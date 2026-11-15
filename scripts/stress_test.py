#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P1 · N 并发抢同一座位压测（测试计划 §5 P1，NFR1 零超卖的直接证据；task-04 交付物）。

流程（任务卡口径）：
  1. scheduler 登录；
  2. （未指定 --showing-id 时）经管理端接口自动创建专用 影片/影厅/场次；
  3. 批量注册/登录 N 观众（默认 500，各持独立会话 Cookie）；
  4. asyncio 并发锁同一座位（POST /api/showings/{id}/seats/lock）；
  5. 统计 成功(0) / 占用失败(10005) / 其他；
  6. 唯一成功者支付出票（POST /api/orders/{id}/pay）；
  7. 复查终态：座位 SOLD、唯一取票码（有效票=1）、其余 N-1 人全被拒；
  8. 输出 PASS/FAIL 与耗时，退出码 0/1（供流水线判定）。

零超卖判定（API 层可得证据）：
  成功数 == 1 且支付返回 code=0（唯一 pickup_code 出票）且终态座位图该座位 == SOLD。
  （order_item 表不在观众端暴露，"有效票=1"由唯一成功者+唯一出票+座位 SOLD 等价证明。）

用法：
  python stress_test.py --base http://localhost:8080 --users 500
  python stress_test.py --users 200 --showing-id 7 --seat-id 301

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
AUDIENCE_PASS = "p1pass123456"


def now_ms() -> float:
    return time.perf_counter() * 1000.0


class ApiClient:
    """单用户会话（自管 JSESSIONID，独立 Cookie，互不串号）。"""

    def __init__(self, session: aiohttp.ClientSession, base: str):
        self.session = session
        self.base = base.rstrip("/")
        self.cookie: str | None = None

    async def request(self, method: str, path: str, body: dict | None = None) -> dict:
        headers = {"Content-Type": "application/json"}
        if self.cookie:
            # self.cookie 存储的是剥离前缀后的裸值，回传时须补 JSESSIONID= 前缀
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


async def ensure_showing(scheduler: ApiClient, args) -> tuple[int, int]:
    """scheduler 建专用场次（未指定 --showing-id 时自动创建影片/影厅/场次），返回 (showingId, seatId)。"""
    showing_id = args.showing_id
    if showing_id:
        seat_map = await scheduler.get(f"/api/showings/{showing_id}/seats")
        assert seat_map.get("code") == 0, f"读取座位图失败：{seat_map}"
        seats = seat_map["data"]["seats"]
        free = [s for s in seats if s["status"] == "FREE"]
        seat_id = args.seat_id or free[0]["seatId"]
        print(f"[fixture] 使用既有场次 showingId={showing_id}，目标座位 seatId={seat_id}")
        return showing_id, seat_id

    stamp = datetime.now().strftime("%H%M%S%f")[:10]
    resp = await scheduler.post("/api/admin/movies",
                                {"title": f"P1压测影片{stamp}", "durationMin": 90})
    assert resp.get("code") == 0, f"创建影片失败：{resp}"
    movie_id = resp["data"]["id"]
    resp = await scheduler.post("/api/admin/halls", {"name": f"P1压测厅{stamp}", "hallType": "STANDARD"})
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
    seats = seat_map["data"]["seats"]
    free = [s for s in seats if s["status"] == "FREE"]
    assert free, "专用场次无空闲座位"
    seat_id = args.seat_id or free[0]["seatId"]
    print(f"[fixture] 已创建专用场次 showingId={showing_id}（座位 {len(seats)} 个），"
          f"目标座位 seatId={seat_id}")
    return showing_id, seat_id


async def register_and_login(session: aiohttp.ClientSession, base: str, username: str,
                             reg_semaphore: asyncio.Semaphore,
                             login_semaphore: asyncio.Semaphore) -> ApiClient:
    client = ApiClient(session, base)
    # 注册串行（信号量=1）：H2 2.x IDENTITY 计数为事务快照语义，同表并发 INSERT 会撞主键
    # （压测夹具阶段串行即可；被测的并发锁座阶段不受影响——失败方回滚、仅胜者插单）
    async with reg_semaphore:
        reg = await client.post("/api/auth/register", {"username": username, "password": AUDIENCE_PASS})
        assert reg.get("code") == 0, f"注册失败 {username}：{reg}"
    async with login_semaphore:
        login = await client.post("/api/auth/login", {"username": username, "password": AUDIENCE_PASS})
        assert login.get("code") == 0, f"登录失败 {username}：{login}"
    return client


async def try_lock(client: ApiClient, showing_id: int, seat_id: int) -> dict:
    """并发锁同一座位：返回 {'user', 'resp'}。"""
    resp = await client.post(f"/api/showings/{showing_id}/seats/lock", {"seatIds": [seat_id]})
    return {"client": client, "resp": resp}


async def main() -> int:
    parser = argparse.ArgumentParser(description="P1 · N 并发抢同一座位压测（NFR1 零超卖）")
    parser.add_argument("--base", default="http://localhost:8080", help="服务地址（默认 http://localhost:8080）")
    parser.add_argument("--users", type=int, default=500, help="并发观众数（默认 500）")
    parser.add_argument("--showing-id", type=int, default=None, help="既有场次 id（缺省自动创建专用场次）")
    parser.add_argument("--seat-id", type=int, default=None, help="目标座位 id（缺省取该场次第一个空闲座位）")
    parser.add_argument("--login-concurrency", type=int, default=25, help="登录并发上限（默认 25；注册串行）")
    args = parser.parse_args()

    print("=" * 72)
    print("P1 · N 并发抢同一座位压测（NFR1 零超卖证据链）")
    print(f"  服务地址 : {args.base}")
    print(f"  并发观众 : {args.users}")
    print(f"  场次来源 : {'指定 --showing-id ' + str(args.showing_id) if args.showing_id else '自动创建专用场次'}")
    print(f"  开始时间 : {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 72)

    connector = aiohttp.TCPConnector(limit=0)
    # 会话隔离关键：禁用 aiohttp 会话级自动 Cookie——否则共享 ClientSession 的 jar
    # 会把"最后一个登录用户"的 JSESSIONID 注入所有请求，覆盖各用户显式 Cookie 头，
    # 500 个请求实际全部落在同一会话（本次联调实测踩坑）。各用户 Cookie 由 ApiClient 自管。
    t_total0 = now_ms()
    async with aiohttp.ClientSession(connector=connector,
                                     cookie_jar=aiohttp.DummyCookieJar()) as session:
        # ---------- 1. scheduler 登录 + 专用场次 ----------
        scheduler = ApiClient(session, args.base)
        login = await scheduler.post("/api/auth/login",
                                     {"username": SCHEDULER_USER, "password": SCHEDULER_PASS})
        assert login.get("code") == 0, f"scheduler 登录失败：{login}"
        showing_id, seat_id = await ensure_showing(scheduler, args)

        # ---------- 2. 批量注册/登录 N 观众（注册串行防 H2 IDENTITY 主键冲突；登录并发） ----------
        t_reg0 = now_ms()
        reg_semaphore = asyncio.Semaphore(1)
        login_semaphore = asyncio.Semaphore(args.login_concurrency)
        stamp = datetime.now().strftime("%H%M%S")
        tasks = [register_and_login(session, args.base, f"p1{stamp}u{i}", reg_semaphore, login_semaphore)
                 for i in range(args.users)]
        users = await asyncio.gather(*tasks)
        t_reg1 = now_ms()
        print(f"[step2] 批量注册+登录 {len(users)} 观众完成，耗时 {(t_reg1 - t_reg0) / 1000:.2f}s")

        # ---------- 3. asyncio 并发锁同一座位 ----------
        print(f"[step3] {len(users)} 并发抢座位 seatId={seat_id}（showingId={showing_id}）……")
        t_lock0 = now_ms()
        results = await asyncio.gather(*[try_lock(u, showing_id, seat_id) for u in users])
        t_lock1 = now_ms()
        lock_seconds = (t_lock1 - t_lock0) / 1000.0

        success = [r for r in results if r["resp"].get("code") == 0]
        occupied = [r for r in results if r["resp"].get("code") == 10005]
        others = [r for r in results
                  if r["resp"].get("code") not in (0, 10005)]

        print("[step4] 锁座统计：")
        print(f"    成功(code=0)      : {len(success)}")
        print(f"    占用失败(code=10005): {len(occupied)}")
        print(f"    其他              : {len(others)}")
        if others:
            from collections import Counter
            codes = Counter(r["resp"].get("code") for r in others)
            print(f"    其他明细          : {dict(codes)}")
            for sample in others[:3]:
                print(f"      样例: {sample['resp']}")
        print(f"    锁座阶段耗时      : {lock_seconds:.2f}s，"
              f"吞吐 ≈ {len(results) / max(lock_seconds, 0.001):.0f} 请求/秒")

        fail_reasons = []
        if len(success) != 1:
            fail_reasons.append(f"成功数应为 1，实际 {len(success)}（NFR1 有且仅有一个成功）")
        if others:
            fail_reasons.append(f"存在 {len(others)} 个非预期响应（既非成功也非 10005 占用失败）")

        winner = success[0] if success else None
        pickup_code = None
        if winner:
            # ---------- 5. 唯一成功者支付出票 ----------
            order_id = winner["resp"]["data"]["orderId"]
            pay = await winner["client"].post(f"/api/orders/{order_id}/pay")
            if pay.get("code") != 0:
                fail_reasons.append(f"胜者支付失败：{pay}")
            else:
                pickup_code = pay["data"]["pickupCode"]
                print(f"[step5] 胜者支付出票成功：orderId={order_id}，"
                      f"取票码={pickup_code}，金额 ¥{pay['data']['paidAmount']}")

            # ---------- 6. 复查终态：座位 SOLD、零超卖 ----------
            seat_map = await scheduler.get(f"/api/showings/{showing_id}/seats")
            data = seat_map.get("data") or {}
            target = next((s for s in data.get("seats", []) if s["seatId"] == seat_id), None)
            status = target["status"] if target else "MISSING"
            print(f"[step6] 终态复查：座位 {seat_id} status={status}，"
                  f"全场 sold={data.get('soldSeats')} locked={data.get('lockedSeats')} "
                  f"free={data.get('freeSeats')}")
            if status != "SOLD":
                fail_reasons.append(f"目标座位终态应为 SOLD，实际 {status}")
            if data.get("soldSeats") != 1:
                fail_reasons.append(f"全场已售应为 1（零超卖），实际 {data.get('soldSeats')}")
        else:
            print("[step5] 无成功者，跳过支付与终态复查")

    # ---------- 7. 结论 ----------
    t_total1 = now_ms()
    total_seconds = (t_total1 - t_total0) / 1000.0
    verdict = "PASS" if not fail_reasons else "FAIL"
    print("=" * 72)
    print(f"P1 结果：{verdict}")
    if fail_reasons:
        for reason in fail_reasons:
            print(f"  失败原因：{reason}")
    print(f"  判定口径：成功数=1（{len(success)}）× 支付出票=1（取票码 {pickup_code or '-'}）"
          f"× 终态座位 SOLD × 其余 {len(users) - len(success) - len(others)} 人全部 10005 被拒 ⇒ 零超卖")
    print(f"  总耗时：{total_seconds:.2f}s（其中并发锁座 {lock_seconds:.2f}s）")
    print(f"  结束时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 72)
    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
