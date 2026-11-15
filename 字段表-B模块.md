# 字段表 · B 模块（观众端购票 4 接口 + 公共段鉴权 2 接口）

> 交付依据：task-04 实现代码（`backend/src/main/java/com/cinema/controller/auth` + `controller/audience` + `service/auth` + `service/purchase`），与代码完全一致。
> 供 task-06/07 前端对接。**只新建本文件，不改任何既有文档。**
>
> 通用约定：
> - 统一响应外壳：`{ "code": 0, "message": "成功", "data": ... }`；`code=0` 成功，非 0 见错误码表。
> - 鉴权：Session + Cookie（登录接口 `Set-Cookie: JSESSIONID`，后续请求带 `Cookie: JSESSIONID=...`）。
>   除 `/api/auth/**` 外的所有 `/api/**` 接口都要求已登录：未登录 `10001`（HTTP 401），
>   角色不符 `10002`（HTTP 403）。B 段接口中锁座与支付仅观众（AUDIENCE）可调。
> - 枚举值一律传/回**大写下划线枚举名**（如 `ORIGINAL`、`FREE`、`PENDING_PAYMENT`）；非法枚举落到 `10003`。
> - 时间格式统一 ISO-8601：`yyyy-MM-dd'T'HH:mm:ss`（含亚秒时形如 `2026-11-20T18:30:00.123456`）。
> - 金额一律 2 位小数（HALF_UP）。
> - HTTP 状态映射：12001→402、12002/12003/12006→409、12004→404、12005→403、12007→401、10003→400、10004/11005→404、10005→409、10001→401、10002→403。

## 错误码总表（B 模块段 12xxx + 复用段）

| code | 枚举名 | HTTP | 含义 |
|---|---|---|---|
| 12001 | PAY_GATEWAY_FAILED | 402 | 模拟支付网关返回失败（UC205-3a）：订单保持待支付、座位维持锁定至倒计时结束，可重试 |
| 12002 | PAY_VOID_SEAT_RELEASED | 409 | 支付作废（UC205-4a）：支付时座位已被超时释放/订单已取消，模拟退款，**提示重新选座** |
| 12003 | ORDER_NOT_PAYABLE | 409 | 订单当前状态不可支付（已出票重复支付/已检票/已退票等，状态机拒绝） |
| 12004 | ORDER_NOT_FOUND | 404 | 订单不存在 |
| 12005 | ORDER_FORBIDDEN | 403 | 非本人订单（越权支付） |
| 12006 | USERNAME_TAKEN | 409 | 用户名已被注册（UC201） |
| 12007 | LOGIN_FAILED | 401 | 用户名或密码错误（不区分具体原因，防用户名枚举） |
| 10001 | NOT_LOGGED_IN | 401 | 未登录或会话已过期（拦截器） |
| 10002 | NO_PERMISSION | 403 | 无权限（角色不符，拦截器；锁座/支付仅 AUDIENCE） |
| 10003 | INVALID_PARAM | 400 | 参数非法（@Valid 校验失败/选座数越界/场次状态不可售等） |
| 10004 | NOT_FOUND | 404 | 资源不存在 |
| 10005 | SEAT_OCCUPIED | 409 | 座位已被占用（并发锁座失败统一提示，UC204-4a） |
| 11005 | SHOWING_NOT_FOUND | 404 | 场次不存在（复用 A 段码：座位图/锁座的场次 id 无效） |

> 公共段 10000 系统异常兜底不变；A 段 11xxx 见字段表-A模块.md。

---

## 1. 注册观众账号（UC201）—— `POST /api/auth/register`

仅创建**观众（AUDIENCE）**账号；注册成功**不建立会话**，需再调接口 2 登录。排片员/检票员账号由种子数据提供，不开放注册。

**请求体**（`application/json`）：

| 字段 | 类型 | 必填 | 说明 | 非法后果 |
|---|---|---|---|---|
| username | string | 是 | 用户名，2~50 字，全库唯一 | 10003；重名 12006 |
| password | string | 是 | 密码，6~32 字（服务端只存 SHA-256+盐哈希） | 10003 |
| phone | string | 否 | 手机号，≤20 字 | 10003 |

**响应 `data`**（AuthResponse）：

| 字段 | 类型 | 说明 |
|---|---|---|
| userId | long | 用户 id |
| username | string | 用户名 |
| role | string | 固定 `AUDIENCE` |

**错误码**：12006 重名；10003 参数非法。

## 2. 登录并建立会话（UC201）—— `POST /api/auth/login`

三类角色（AUDIENCE/SCHEDULER/CHECKER）均可登录；成功后服务端写 Session 并返回 `Set-Cookie: JSESSIONID=...`。

**请求体**：

| 字段 | 类型 | 必填 | 说明 | 非法后果 |
|---|---|---|---|---|
| username | string | 是 | 用户名 | 10003 |
| password | string | 是 | 密码（明文仅限本机演示环境） | 10003；校验失败 12007 |

**响应 `data`**：同接口 1 的 AuthResponse（`role` 为实际角色：`AUDIENCE` / `SCHEDULER` / `CHECKER`）。

**错误码**：12007 用户名或密码错误；10003 参数非法。

## 3. 上映中影片列表（UC202）—— `GET /api/movies`

无请求参数；返回全部 `ON_SHOW` 影片，按影片 id 升序。

**响应 `data`**（MovieBriefResponse 数组）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | long | 影片 id |
| title | string | 片名 |
| genre | string \| null | 类型 |
| durationMin | int \| null | 片长（分钟） |
| posterUrl | string \| null | 海报地址 |

**错误码**：10001 未登录。

## 4. 场次详情与实时座位图（UC202/UC203）—— `GET /api/showings/{id}/seats`

轮询接口（前端建议 3 秒/次，spec §7.3）；服务端两查询实现（场次+座位），保持轮询轻量（NFR4）。

**路径参数**：`id`（long，必填，场次 id）。

**响应 `data`**（ShowingSeatMapResponse）：

| 字段 | 类型 | 说明 |
|---|---|---|
| showingId | long | 场次 id |
| movieId | long | 影片 id |
| movieTitle | string | 片名 |
| hallName | string | 影厅名称 |
| startTime | string | 开映时间（ISO-8601） |
| status | string | 场次状态：`PUBLISHED` / `CANCELED` / `FINISHED` |
| priceType | string | 票价策略：`ORIGINAL` / `MEMBER_DISCOUNT` / `ZONE_PRICING` |
| priceParams | object | 计价参数（对象形式，键约定见字段表-A模块.md） |
| lockSeconds | long | 服务端锁定时长秒数（演示档 30 / 正式档 900；前端倒计时**仅展示用**） |
| totalSeats | int | 座位总数 |
| freeSeats | int | 空闲座位数 |
| lockedSeats | int | 锁定中座位数 |
| soldSeats | int | 已售座位数 |
| seats | array | 座位列表（排号升序、座号升序），元素见下 |

**seats 元素**（SeatView）：

| 字段 | 类型 | 说明 |
|---|---|---|
| seatId | long | 座位 id（锁座请求 `seatIds` 取这里） |
| rowNum | int | 排号（1 起） |
| colNum | int | 座号（1 起） |
| zone | string | 分区：`GOLDEN` 黄金区 / `NORMAL` 普通区 |
| status | string | 实时状态：`FREE` 空闲 / `LOCKED` 锁定 / `SOLD` 已售 |

**错误码**：11005 场次不存在；10001 未登录。

## 5. 选座锁座（UC204，并发核心）—— `POST /api/showings/{id}/seats/lock`

仅观众角色（其他角色 `10002`）。并发语义（UC204-4a）：同一场次同一座位并发锁座**有且仅有一个成功**，其余返回 `10005 座位已被占用，请刷新座位图后重试`。

**路径参数**：`id`（long，必填，场次 id）。

**请求体**：

| 字段 | 类型 | 必填 | 说明 | 非法后果 |
|---|---|---|---|---|
| seatIds | long 数组 | 是 | 所选座位 id 列表（取自接口 4；服务端去重升序处理） | 10003（缺失/空数组/含 null/座位不属于该场次）；超过 4 座 10003（单笔上限 `cinema.booking.max-seats-per-order`，默认 4） |

**响应 `data`**（LockOrderResponse）：

| 字段 | 类型 | 说明 |
|---|---|---|
| orderId | long | 订单 id（支付接口路径参数） |
| orderNo | string | 订单号，`B`+17 位时间戳+4 位随机（22 字符，全库唯一） |
| showingId | long | 场次 id |
| movieTitle | string | 片名（展示） |
| status | string | 固定 `PENDING_PAYMENT`（待支付） |
| lockExpireAt | string | 锁定截止时间（ISO-8601；倒计时归零未支付由服务端自动释放） |
| lockSeconds | long | 服务端锁定时长秒数 |
| estimatedAmount | number | 待支付预估金额 = 按策略计价的座位单价合计 |
| seats | array | 座位明细：`{seatId, rowNum, colNum, zone, price}`（price=该座预估单价） |

**业务规则（实现口径）**：
- 场次须 `PUBLISHED` 且未开映，否则 `10003`（场次可售校验在锁座服务）。
- **同用户在同一场次重复锁座 = 改选**：上一笔待支付订单自动取消（状态机 待支付→已取消）、其仍持有的锁定座位立即释放（广播 GIVE_UP 释放事件并通知），随后按本次选择重新锁定——保证"一用户一场次至多一笔待支付订单"。
- 目标座位若"锁定已超时但尚未被定时任务清理"（UC204-4b），先条件更新释放再按空闲锁定。
- 锁座成功不扣款不产生票据明细；票据在支付出票时生成。

**错误码**：10005 座位已被占用（并发失败）；10003 参数/状态非法；11005 场次不存在；10001/10002 鉴权。

## 6. 模拟支付并出票（UC205）—— `POST /api/orders/{id}/pay`

仅观众角色且**本人订单**（他人订单 `12005`）。请求体可为空 JSON `{}`（无业务字段）。

**路径参数**：`id`（long，必填，订单 id = 接口 5 返回的 `orderId`）。

**响应 `data`**（PayResponse，`code=0` 时）：

| 字段 | 类型 | 说明 |
|---|---|---|
| orderId | long | 订单 id |
| orderNo | string | 订单号 |
| status | string | 固定 `ISSUED`（已出票） |
| pickupCode | string | **取票码**：8 位数字（`^\d{8}$`，允许前导 0），出票时生成、全库唯一（检票核销凭据 UC307） |
| paidAmount | number | 实付金额 = 明细快照价合计 |
| payTime | string | 支付时间（ISO-8601） |
| seatIds | long 数组 | 出票座位 id 列表 |

**失败语义（响应 code 非 0，data 为 null）**：

| code | 场景 | 订单/座位终态 |
|---|---|---|
| 12001（402） | 模拟网关返回失败（失败率配置 `cinema.booking.pay-fail-rate`：0=必成/1=必败/(0,1)=随机，UC205-3a） | 订单保持 `PENDING_PAYMENT`；座位维持锁定至倒计时结束；可重试支付 |
| 12002（409） | 支付时座位已被超时释放/订单已取消（UC205-4a） | 订单维持 `CANCELED`；残留锁定释放回 FREE；网关若已扣款则模拟退款并广播退款通知；**提示重新选座** |
| 12003（409） | 订单已出票（重复支付）/已检票/已退票 | 状态机拒绝，一切不变（防双付） |
| 12004（404） | 订单 id 不存在 | 不变 |
| 12005（403） | 非本人订单 | 不变 |

**成功语义（实现口径）**：
- 支付在事务内逐座条件更新售出（复查"本人锁定且未超时"，乐观锁 version+1）——与超时释放任务在数据库行级互斥，**支付 vs 超时释放竞态有且仅有一个生效**（A7/P1 证据）。
- `order_item` 每座一条有效明细（valid_flag=1），`price_snapshot` 按出票时刻的策略与座位分区计价；`total_amount` = 明细合计（锁座时的预估金额仅为展示）。
- 出票成功广播出票事件 → 站内通知落库（观察者，UC306）。

**错误码**：12001/12002/12003/12004/12005；10001/10002 鉴权。

---

## 附：B 模块配置项（NFR2，修改配置不需改代码）

| 配置键 | 默认 | 说明 |
|---|---|---|
| `cinema.booking.lock-duration` | 演示档 30s / 正式档 15m | 锁定时长（接口 4/5 的 lockSeconds 与 lockExpireAt 依据） |
| `cinema.booking.release-scan-interval-millis` | 1000（=1s，演示档实时性优先） | 锁定超时释放 @Scheduled 扫描间隔（task-04 新增） |
| `cinema.booking.pay-fail-rate` | 0.0 | 模拟支付失败率（0=必成 / 1=必败 / (0,1)=随机） |
| `cinema.booking.max-seats-per-order` | 4 | 单笔锁座上限（UC204-3） |
