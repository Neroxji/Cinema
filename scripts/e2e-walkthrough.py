# -*- coding: utf-8 -*-
"""task-09 交付终验 · 六场景 API 级走查（一次性验证工具，非产品代码）。

用法（容器全套 healthy 后，宿主机执行）：
    /d/tools/python/python.exe -I scripts/e2e-walkthrough.py

对 http://localhost:8080（compose 路线 A，mysql profile）执行六场景：
  S1 正常购票（注册/登录→影片→座位图→锁座(倒计时)→支付出票→我的订单）
  S2 双窗口抢同座（并发锁同一座位，恰 1 成功，另 1 得 10005）
  S3 锁座超时释放（DB 把 lock_expire_at 改到过去 → @Scheduled 1s 扫描 → 座位 FREE
      + 订单 CANCELED + SEAT_RELEASED 通知）
  S4 退票→递补（候补满座场 → 种子单退票 → 候补者收 QUEUE_MATCHED → claim → 支付）
  S5 检票核销（checker 凭取票码核销 → CHECKED_IN；重复核销 13008；核销后退票 13001）
  S6 策略切换（scheduler 切 ZONE_PRICING → 座位图计价参数变化 + 锁座价按新策略，
      验证后恢复原策略并清理测试锁座）

依赖：仅 Python 标准库；Docker CLI（DB 手段改 lock_expire_at 用 docker exec 进
cinema-mysql-1 容器，属演示夹具手段，不动产品代码与配置）。
"""
import json
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8080"
DB = ["docker", "exec", "cinema-mysql-1", "mysql", "-uroot", "-pcinema2026", "-N", "-e"]

RESULTS = []          # (scenario, step, ok, detail)
TOK = uuid.uuid4().hex[:6]   # 本次运行唯一后缀，保证重跑幂等（用户名全库唯一）


def check(scenario, step, ok, detail=""):
    RESULTS.append((scenario, step, bool(ok), str(detail)))
    tag = "PASS" if ok else "FAIL"
    line = "[%s] %-52s %s  %s" % (scenario, step, tag, detail)
    print(line, flush=True)
    return ok


def db(sql):
    """进 mysql 容器执行 SQL（演示夹具手段），返回 stdout 去掉密码告警行。"""
    out = subprocess.run(DB + [sql], capture_output=True, text=True, timeout=30)
    lines = [l for l in (out.stdout or "").splitlines() if "Using a password" not in l]
    return "\n".join(lines).strip()


class Client:
    """一个用户会话（独立 Cookie，承载 JSESSIONID）。"""

    def __init__(self):
        self.cookie = None

    def req(self, method, path, body=None):
        r = urllib.request.Request(BASE + path, method=method)
        if self.cookie:
            r.add_header("Cookie", self.cookie)
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            r.add_header("Content-Type", "application/json")
        try:
            resp = urllib.request.urlopen(r, data, timeout=20)
        except urllib.error.HTTPError as e:      # 4xx/5xx 也读 body（统一外壳）
            resp = e
        raw = resp.read().decode("utf-8", "replace")
        setc = resp.headers.get("Set-Cookie")
        if setc:
            self.cookie = setc.split(";")[0]
        try:
            return resp.status if hasattr(resp, "status") else resp.code, json.loads(raw)
        except ValueError:
            return resp.status if hasattr(resp, "status") else resp.code, {"_raw": raw}


def register(username):
    c = Client()
    st, j = c.req("POST", "/api/auth/register",
                  {"username": username, "password": "pass123456"})
    return c, st, j


def login(username, password):
    c = Client()
    st, j = c.req("POST", "/api/auth/login", {"username": username, "password": password})
    return c, st, j


def seat_map(c, sid):
    st, j = c.req("GET", "/api/showings/%d/seats" % sid)
    return j


def free_seats(c, sid, n=1):
    m = seat_map(c, sid)
    return m, [s for s in m["data"]["seats"] if s["status"] == "FREE"][:n]


def wait_until(fn, timeout=12, interval=0.5):
    """轮询直到 fn() 返回真值或超时，返回最后的 fn() 结果。"""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = fn()
        if last:
            return last
        time.sleep(interval)
    return last


# =====================================================================
# S1 正常购票
# =====================================================================
def s1():
    sc = "S1 正常购票"
    user = "s1_" + TOK
    c, st, j = register(user)
    if not check(sc, "注册观众 " + user, st == 200 and j.get("code") == 0,
                 "http=%s code=%s" % (st, j.get("code"))):
        return
    st, j = c.req("POST", "/api/auth/login", {"username": user, "password": "pass123456"})
    ok = j.get("code") == 0 and j["data"]["role"] == "AUDIENCE"
    if not check(sc, "登录（角色 AUDIENCE, JSESSIONID）", ok, "role=%s" % j.get("data", {}).get("role")):
        return
    st, j = c.req("GET", "/api/movies")
    ok = j.get("code") == 0 and len(j["data"]) == 3
    if not check(sc, "浏览上映影片（3 部种子）", ok,
                 "titles=%s" % ",".join(m["title"] for m in j.get("data", []))):
        return
    m = seat_map(c, 3)
    free0 = m["data"]["freeSeats"]
    target = next(s for s in m["data"]["seats"] if s["status"] == "FREE")
    check(sc, "座位图场次3（轮询口径）", m["code"] == 0,
          "hall=%s free=%d/%d target=(r%s,c%s,%s,id%s)"
          % (m["data"]["hallName"], free0, m["data"]["totalSeats"],
             target["rowNum"], target["colNum"], target["zone"], target["seatId"]))
    st, j = c.req("POST", "/api/showings/3/seats/lock", {"seatIds": [target["seatId"]]})
    d = j.get("data") or {}
    ok = (j.get("code") == 0 and d.get("status") == "PENDING_PAYMENT"
          and bool(d.get("lockExpireAt")) and d.get("lockSeconds") == 900)
    if not check(sc, "锁座→待支付+倒计时(15m=900s)", ok,
                 "orderId=%s lockSeconds=%s expireAt=%s est=%s"
                 % (d.get("orderId"), d.get("lockSeconds"), d.get("lockExpireAt"),
                    d.get("estimatedAmount"))):
        return
    oid = d["orderId"]
    m2 = seat_map(c, 3)
    locked = next(s for s in m2["data"]["seats"] if s["seatId"] == target["seatId"])
    check(sc, "座位图实时态 LOCKED", locked["status"] == "LOCKED",
          "seat=%s status=%s" % (target["seatId"], locked["status"]))
    st, j = c.req("POST", "/api/orders/%d/pay" % oid)
    d = j.get("data") or {}
    ok = (j.get("code") == 0 and d.get("status") == "ISSUED"
          and re.fullmatch(r"\d{8}", str(d.get("pickupCode") or "")))
    if not check(sc, "支付出票（取票码 8 位）", ok,
                 "orderNo=%s pickup=%s paid=%s" % (d.get("orderNo"), d.get("pickupCode"),
                                                   d.get("paidAmount"))):
        return
    st, j = c.req("GET", "/api/orders")
    found = next((o for o in j.get("data", []) if o["orderId"] == oid), None)
    ok = (found is not None and found["status"] == "ISSUED"
          and found["pickupCode"] == d["pickupCode"] and found["seats"][0]["valid"])
    check(sc, "我的订单可见（状态/取票码/明细）", ok,
          "orders=%d status=%s pickup=%s" % (len(j.get("data", [])),
                                             (found or {}).get("status"),
                                             (found or {}).get("pickupCode")))
    m3 = seat_map(c, 3)
    sold = next(s for s in m3["data"]["seats"] if s["seatId"] == target["seatId"])
    check(sc, "座位图实时态 SOLD", sold["status"] == "SOLD",
          "seat=%s status=%s" % (target["seatId"], sold["status"]))
    return {"user": user, "orderId": oid, "pickupCode": d["pickupCode"],
            "seatId": target["seatId"]}


# =====================================================================
# S2 双窗口抢同座
# =====================================================================
def s2():
    sc = "S2 双窗口抢同座"
    ua, sta, ja = register("s2a_" + TOK)
    ub, stb, jb = register("s2b_" + TOK)
    for c, st, j, name in ((ua, sta, ja, "A"), (ub, stb, jb, "B")):
        c.req("POST", "/api/auth/login", {"username": "s2%s_%s" % (name, TOK),
                                          "password": "pass123456"})
    m, frees = free_seats(ua, 4, 1)
    if not check(sc, "准备：场次4取空闲座", m.get("code") == 0 and frees,
                 "seatId=%s" % (frees[0]["seatId"] if frees else None)):
        return
    seat = frees[0]["seatId"]
    barrier = threading.Barrier(2)
    out = {}

    def fire(name, cli):
        barrier.wait()                      # 双窗口同时点击
        out[name] = cli.req("POST", "/api/showings/4/seats/lock", {"seatIds": [seat]})

    ta = threading.Thread(target=fire, args=("A", ua))
    tb = threading.Thread(target=fire, args=("B", ub))
    ta.start(); tb.start(); ta.join(); tb.join()
    codes = {k: v[1].get("code") for k, v in out.items()}
    winners = [k for k, v in out.items() if v[1].get("code") == 0]
    losers = [k for k, v in out.items() if v[1].get("code") == 10005]
    ok = len(winners) == 1 and len(losers) == 1
    if not check(sc, "并发锁同座：恰 1 成功 1 拒绝(10005)", ok,
                 "codes=%s winner=%s loserHttp=%s"
                 % (codes, winners, [out[k][0] for k in out if out[k][1].get("code") != 0])):
        return
    w = winners[0]
    wc = {"A": ua, "B": ub}[w]                 # 胜者客户端（out 里存的是 (status,json) 元组）
    d = out[w][1]["data"]
    print("      抢座胜者 %s：orderId=%s orderNo=%s" % (w, d["orderId"], d["orderNo"]))
    # 胜者直接支付后立即退票，把座位还原为 FREE（订单落 REFUNDED 留痕，不占座）
    wc.req("POST", "/api/orders/%d/pay" % d["orderId"])
    st, j = wc.req("POST", "/api/orders/%d/refund" % d["orderId"])
    check(sc, "胜者支付后退票还原座位", j.get("code") == 0
          and j.get("data", {}).get("status") == "REFUNDED",
          "refundSeatIds=%s" % j.get("data", {}).get("seatIds"))
    m2 = seat_map(ua, 4)
    back = next(s for s in m2["data"]["seats"] if s["seatId"] == seat)
    check(sc, "被抢座位回到 FREE", back["status"] == "FREE",
          "seat=%s status=%s" % (seat, back["status"]))


# =====================================================================
# S3 锁座超时释放（DB 夹具 + @Scheduled 1s 扫描）
# =====================================================================
def s3():
    sc = "S3 超时释放"
    user = "s3_" + TOK
    c, st, j = register(user)
    c.req("POST", "/api/auth/login", {"username": user, "password": "pass123456"})
    m, frees = free_seats(c, 2, 1)
    seat = frees[0]["seatId"]
    st, j = c.req("POST", "/api/showings/2/seats/lock", {"seatIds": [seat]})
    d = j.get("data") or {}
    if not check(sc, "锁座不支付（PENDING_PAYMENT）", j.get("code") == 0,
                 "orderId=%s seat=%s expire=%s" % (d.get("orderId"), seat, d.get("lockExpireAt"))):
        return
    oid = d["orderId"]
    n0 = len(c.req("GET", "/api/notifications")[1].get("data") or [])
    # DB 夹具：把锁定期限改到过去（演示手段，容器内 mysql 执行）。
    # 释放任务扫描的是 seat.lock_expire_at（LockExpireReleaseTask.scanExpired），
    # 故座位表与订单表一并改到过去，模拟"到期未支付"。
    db("UPDATE cinema.seat SET lock_expire_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE) "
       "WHERE id=%d; UPDATE cinema.ticket_order SET lock_expire_at = "
       "DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id=%d;" % (seat, oid))
    check(sc, "DB 夹具：seat/order 的 lock_expire_at 改到过去", True, "order=%d seat=%d" % (oid, seat))

    def released():
        mm = seat_map(c, 2)
        s = next(x for x in mm["data"]["seats"] if x["seatId"] == seat)
        _, oj = c.req("GET", "/api/orders")
        o = next((x for x in oj["data"] if x["orderId"] == oid), None)
        return s["status"] == "FREE" and o and o["status"] == "CANCELED", s["status"], \
            (o or {}).get("status")

    got = wait_until(lambda: released()[0], timeout=15)
    _, seat_state, order_state = released()
    check(sc, "@Scheduled(1s) 扫描后座位回 FREE", got and seat_state == "FREE",
          "seat=%s -> %s" % (seat, seat_state))
    check(sc, "订单转 CANCELED", got and order_state == "CANCELED",
          "order=%d -> %s" % (oid, order_state))
    noti = c.req("GET", "/api/notifications")[1].get("data") or []
    rel = [x for x in noti if x["type"] == "SEAT_RELEASED"]
    check(sc, "收到释放通知 SEAT_RELEASED", len(noti) > n0 and len(rel) >= 1,
          "noti=%d(+%d) rel=%d title=%s" % (len(noti), len(noti) - n0, len(rel),
                                            rel[0]["title"] if rel else "-"))


# =====================================================================
# S4 退票→递补
# =====================================================================
def s4():
    sc = "S4 退票→递补"
    user = "s4_" + TOK
    c4, st, j = register(user)
    c4.req("POST", "/api/auth/login", {"username": user, "password": "pass123456"})
    m = seat_map(c4, 1)
    check(sc, "满座场确认（种子场次1）", m["code"] == 0 and m["data"]["freeSeats"] == 0,
          "free=%d sold=%d" % (m["data"]["freeSeats"], m["data"]["soldSeats"]))
    st, j = c4.req("POST", "/api/showings/1/queue")
    d = j.get("data") or {}
    if not check(sc, "加入候补（WAITING, rank）", j.get("code") == 0
                 and d.get("status") == "WAITING",
                 "queueId=%s rank=%s" % (d.get("queueId"), d.get("rank"))):
        return
    qid = d["queueId"]
    ca, st, j = login("audience", "audience123")
    if not check(sc, "种子观众 audience 登录", j.get("code") == 0, "-"):
        return
    st, j = ca.req("GET", "/api/orders")
    issued = [o for o in j["data"] if o["status"] == "ISSUED"]
    seed = next((o for o in issued if o["pickupCode"] == "86420195"), None)
    if not check(sc, "种子已出票单在场（86420195）", seed is not None,
                 "orderId=%s seats=%d" % ((seed or {}).get("orderId"),
                                          len((seed or {}).get("seats", [])))):
        return
    st, j = ca.req("POST", "/api/orders/%d/refund" % seed["orderId"])
    d = j.get("data") or {}
    if not check(sc, "已出票观众退票（REFUNDED）", j.get("code") == 0
                 and d.get("status") == "REFUNDED",
                 "orderId=%s released=%d 座" % (seed["orderId"], len(d.get("seatIds") or []))):
        return

    def invited():
        noti = c4.req("GET", "/api/notifications")[1].get("data") or []
        hit = [x for x in noti if x["type"] == "QUEUE_MATCHED"
               and ("递补凭据Q%d" % qid) in x["content"]]
        return hit

    hit = wait_until(lambda: invited(), timeout=15)
    if not check(sc, "候补者收递补通知（QUEUE_MATCHED）", bool(hit),
                 "title=%s" % (hit[0]["title"] if hit else "-")):
        return
    st, j = c4.req("POST", "/api/queue/%d/claim" % qid)
    d = j.get("data") or {}
    if not check(sc, "claim 递补锁座（PENDING_PAYMENT）", j.get("code") == 0,
                 "orderId=%s orderNo=%s seat=%s" % (d.get("orderId"), d.get("orderNo"),
                                                    d.get("seatId"))):
        return
    st, j = c4.req("POST", "/api/orders/%d/pay" % d["orderId"])
    d2 = j.get("data") or {}
    ok = (j.get("code") == 0 and d2.get("status") == "ISSUED"
          and re.fullmatch(r"\d{8}", str(d2.get("pickupCode") or "")))
    check(sc, "递补者支付出票（8 位取票码）", ok,
          "pickup=%s paid=%s" % (d2.get("pickupCode"), d2.get("paidAmount")))


# =====================================================================
# S5 检票核销
# =====================================================================
def s5(s1_ctx):
    sc = "S5 检票核销"
    if not s1_ctx:
        print("[%s] SKIP（S1 未产出订单）" % sc)
        RESULTS.append((sc, "skip", False, "S1 未产出订单"))
        return
    cc, st, j = login("checker", "checker123")
    if not check(sc, "checker 登录", j.get("code") == 0
                 and j.get("data", {}).get("role") == "CHECKER", "-"):
        return
    pickup = s1_ctx["pickupCode"]
    st, j = cc.req("POST", "/api/admin/checkin", {"pickupCode": pickup})
    d = j.get("data") or {}
    ok = j.get("code") == 0 and d.get("status") == "CHECKED_IN" and bool(d.get("checkinTime"))
    if not check(sc, "凭取票码核销→CHECKED_IN", ok,
                 "orderNo=%s operator=%s time=%s"
                 % (d.get("orderNo"), d.get("operator"), d.get("checkinTime"))):
        return
    st, j = cc.req("POST", "/api/admin/checkin", {"pickupCode": pickup})
    check(sc, "重复核销拒绝（13008）", j.get("code") == 13008,
          "code=%s msg=%s" % (j.get("code"), j.get("message")))
    c1, st, j = login(s1_ctx["user"], "pass123456")   # S1 单属该观众，本人会话退票
    st, j = c1.req("POST", "/api/orders/%d/refund" % s1_ctx["orderId"])
    check(sc, "已检票订单退票拒绝（13001）", j.get("code") == 13001,
          "code=%s msg=%s" % (j.get("code"), j.get("message")))


def j_cookie_login(user):
    c = Client()
    c.req("POST", "/api/auth/login", {"username": user, "password": "pass123456"})
    return c, 200, {"code": 0}


# =====================================================================
# S6 策略切换
# =====================================================================
def s6():
    sc = "S6 策略切换"
    cs, st, j = login("scheduler", "scheduler123")
    if not check(sc, "scheduler 登录", j.get("code") == 0
                 and j.get("data", {}).get("role") == "SCHEDULER", "-"):
        return
    ca, _, _ = j_cookie_login("s1_" + TOK)
    before = seat_map(ca, 3)
    old = (before["data"]["priceType"], before["data"]["priceParams"])
    st, j = cs.req("PUT", "/api/admin/showings/3/strategy",
                   {"priceType": "ZONE_PRICING",
                    "priceParams": {"goldenPrice": 66.00, "normalPrice": 44.00}})
    if not check(sc, "scheduler 切场次3→ZONE_PRICING(66/44)", j.get("code") == 0, "-"):
        return
    after = seat_map(ca, 3)
    d = after["data"]
    ok = d["priceType"] == "ZONE_PRICING" and d["priceParams"]["goldenPrice"] == 66 \
        and d["priceParams"]["normalPrice"] == 44
    if not check(sc, "观众端座位图计价参数即时生效", ok,
                 "before=%s after=%s" % (old, (d["priceType"], d["priceParams"]))):
        return
    _, frees = free_seats(ca, 3, 1)
    seat = frees[0]["seatId"]
    st, j = ca.req("POST", "/api/showings/3/seats/lock", {"seatIds": [seat]})
    d2 = j.get("data") or {}
    ok = j.get("code") == 0 and d2["seats"][0]["price"] == 44.00
    check(sc, "新策略锁座价=normalPrice 44.00", ok,
          "seat=%s zone=%s price=%s orderId=%s"
          % (seat, d2["seats"][0]["zone"], d2["seats"][0]["price"], d2.get("orderId")))
    # 清理测试锁座：DB 夹具把 seat/order 到期 → 释放任务回收座位（与 S3 同法）
    if d2.get("orderId"):
        db("UPDATE cinema.seat SET lock_expire_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE) "
           "WHERE id=%d; UPDATE cinema.ticket_order SET lock_expire_at = "
           "DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id=%d;" % (seat, d2["orderId"]))
        got = wait_until(lambda: next(
            (x for x in seat_map(ca, 3)["data"]["seats"] if x["seatId"] == seat),
            {}).get("status") == "FREE", timeout=15)
        check(sc, "清理：测试锁座已释放回 FREE", bool(got), "seat=%s" % seat)
    st, j = cs.req("PUT", "/api/admin/showings/3/strategy",
                   {"priceType": "ORIGINAL", "priceParams": {"basePrice": 38.00}})
    back = seat_map(ca, 3)["data"]
    check(sc, "恢复场次3原策略 ORIGINAL 38.00", j.get("code") == 0
          and back["priceType"] == "ORIGINAL",
          "after=%s %s" % (back["priceType"], back["priceParams"]))


def main():
    print("=" * 100)
    print("task-09 六场景走查  base=%s  run-token=%s  %s"
          % (BASE, TOK, time.strftime("%Y-%m-%d %H:%M:%S")))
    print("=" * 100)
    s1_ctx = s1()
    s2()
    s3()
    s4()
    s5(s1_ctx)
    s6()
    fails = [r for r in RESULTS if not r[2]]
    print("=" * 100)
    for sc in sorted({r[0] for r in RESULTS}):
        rows = [r for r in RESULTS if r[0] == sc]
        verdict = "PASS" if all(r[2] for r in rows) else "FAIL"
        print("  %-22s %s  (%d steps)" % (sc, verdict, len(rows)))
    print("=" * 100)
    print("TOTAL %d steps, %d FAIL" % (len(RESULTS), len(fails)))
    with open(__file__.replace("e2e-walkthrough.py", "e2e-walkthrough-result.json"),
              "w", encoding="utf-8") as f:
        json.dump({"token": TOK, "results": RESULTS,
                   "fail": [r for r in RESULTS if not r[2]]}, f, ensure_ascii=False, indent=1)
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
