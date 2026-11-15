# 字段表 · C 模块（订单售后与通知 7 接口）

> 交付依据：task-05 实现代码（`backend/src/main/java/com/cinema/controller/audience` + `controller/admin` + `service/aftermarket` + `common/event/QueueCheckSubscriber`），与代码完全一致。
> 供 task-06/07 前端对接。**只新建本文件，不改任何既有文档。**
>
> 通用约定（与字段表-B模块.md 一致）：
> - 统一响应外壳：`{ "code": 0, "message": "成功", "data": ... }`；`code=0` 成功，非 0 见错误码表。
> - 鉴权：Session + Cookie（`Cookie: JSESSIONID=...`）。C 段 6 个观众侧接口仅 **AUDIENCE** 角色，检票核销仅 **CHECKER** 角色：未登录 `10001`（401），角色不符 `10002`（403）。
> - 枚举值一律传/回**大写下划线枚举名**（如 `ISSUED`、`REFUNDED`、`WAITING`、`MATCHED`）。
> - 时间格式统一 ISO-8601：`yyyy-MM-dd'T'HH:mm:ss`（含亚秒形如 `2026-11-16T03:40:00.123456`）。
> - 金额一律 2 位小数（HALF_UP）。
> - HTTP 状态映射：13012→400、12004/13004 系检索类→404（12004 为 404）、12005→403、13001~13011/13013~13014→409、10003→400、11005→404、11007→409。

## 错误码总表（C 模块段 13xxx + 复用段）

| code | 枚举名 | HTTP | 含义 |
|---|---|---|---|
| 13001 | ORDER_NOT_REFUNDABLE | 409 | 订单当前状态不可退票（UC302-4a：仅"已出票且未检票"可退；已检票终态/已退票/已取消/待支付均拒绝，message 注明具体原因） |
| 13002 | REFUND_DEADLINE_PASSED | 409 | 已过退票截止（UC302-4b：开映前 `cinema.booking.refund-deadline`（默认 2h）停止受理，message 含截止时刻） |
| 13003 | QUEUE_ALREADY_JOINED | 409 | 重复候补（UC304：同场次已有排队中记录） |
| 13004 | QUEUE_RECORD_INVALID | 409 | 候补记录不存在或状态不符（UC305 claim：未收到递补通知/已递补 MATCHED/已取消；检索不到同样本码） |
| 13005 | QUEUE_CLAIM_EXPIRED | 409 | 递补确认超时（UC305：响应时限内未确认，资格已顺延下一位） |
| 13006 | QUEUE_SEAT_TAKEN | 409 | 递补座位已被其他观众选购（claim 锁座并发失败；候补记录保持 WAITING 继续排队） |
| 13007 | CHECKIN_STATUS_MISMATCH | 409 | 订单当前状态不可核销（UC307-3a：仅已出票可核销） |
| 13008 | CHECKIN_ALREADY_CHECKED_IN | 409 | 订单已检票，重复核销幂等拒绝（UC307-5a：并发核销仅一次生效，A8） |
| 13009 | CHECKIN_MANUAL_REQUIRED | 409 | 开映已超过 30 分钟，转人工窗口处理（UC307-3b；宽限常量 30 分钟，PRD 固定值，未列为配置项） |
| 13010 | CHANGE_NOT_ALLOWED | 409 | 订单当前状态不可改签（UC303：仅已出票可改签） |
| 13011 | CHANGE_DEADLINE_PASSED | 409 | 已过改签截止（UC303：与退票同口径，开映前 2 小时） |
| 13012 | CHANGE_TARGET_INVALID | 400 | 改签目标场次无效（须为同影片其他已发布、未开映且余座充足的场次；余座不足同样本码） |
| 13013 | CHANGE_LIMIT_REACHED | 409 | 每单仅限改签一次（change_from_showing_id 已留痕） |
| 13014 | QUEUE_JOIN_REJECTED | 409 | 当前场次不满足候补条件（UC304：仅未开映的满座场次可加入候补） |
| 12004 | ORDER_NOT_FOUND（复用 B 段） | 404 | 订单不存在（退票/改签/核销凭据检索不到） |
| 12005 | ORDER_FORBIDDEN（复用 B 段） | 403 | 非本人资源（退票/改签/确认他人候补记录，message 注明"候补/订单"） |
| 11005 | SHOWING_NOT_FOUND（复用 A 段） | 404 | 场次不存在（候补/改签目标场次 id 无效） |
| 11007 | MOVIE_OFF_SHOW（复用 A 段） | 409 | （I13 场次创建守卫；C 段不直接抛出，列入便于联调对码） |
| 10001/10002/10003/10004/10005 | 公共段 | — | 未登录/无权限/参数非法/资源不存在/座位已被占用（claim 并发窗口下映射为 13006） |

---

## 1. 我的订单列表（UC301）—— `GET /api/orders`

仅观众角色；返回当前登录观众全部订单，按订单 id 降序（新单在前）。无请求参数。

**响应 `data`**（OrderSummaryResponse 数组）：

| 字段 | 类型 | 说明 |
|---|---|---|
| orderId | long | 订单 id（退票/改签/支付接口路径参数） |
| orderNo | string | 订单号 |
| status | string | `PENDING_PAYMENT` / `ISSUED` / `CHECKED_IN` / `REFUNDED` / `CANCELED` |
| totalAmount | number | 订单总额（有效明细快照合计；改签后为新场次明细合计） |
| pickupCode | string \| null | 取票码（8 位数字；待支付订单为 null） |
| payTime | string \| null | 支付（出票）时间 |
| checkinTime | string \| null | 检票核销时间 |
| changeFromShowingId | long \| null | 改签来源场次 id（仅改签单非空） |
| showing.showingId | long | 所属场次 id（改签后为新场次） |
| showing.movieTitle | string | 片名 |
| showing.hallName | string | 影厅名 |
| showing.startTime | string | 开映时间（ISO-8601） |
| seats | array | 座位明细：`{seatId, rowNum, colNum, zone, price, valid}`（price=快照价；valid=false 表示该明细已因退票/改签失效，历史仍可见） |

**错误码**：10001/10002 鉴权。

## 2. 退票（UC302）—— `POST /api/orders/{id}/refund`

仅观众且**本人订单**。请求体可为空 JSON `{}`（无业务字段）。

**路径参数**：`id`（long，订单 id）。

**响应 `data`**（RefundResponse）：

| 字段 | 类型 | 说明 |
|---|---|---|
| orderId | long | 订单 id |
| orderNo | string | 订单号 |
| status | string | 固定 `REFUNDED`（已退票，终态） |
| refundAmount | number | 模拟退款金额（=订单总额，模拟渠道到账） |
| refundedAt | string | 退票时间 |
| seatIds | long 数组 | 释放座位 id 列表（已回空闲、可重售） |

**实现口径**：
- 校验链（纯逻辑校验器 `RefundValidator`，U7 被测）：本人（12005）→ 状态仅 ISSUED（13001）→ 未过开映前 2 小时（13002）。
- 退票执行（命令模式，`RefundCommand` 接 `CommandHistory`）：订单 ISSUED→REFUNDED（状态机）→ `order_item.valid_flag` 置 NULL（防双卖唯一索引释放，座位可重售）→ 座位 SOLD→FREE（状态机 releaseByRefund + @Version）。
- 事件广播（同事务）：`SeatReleasedEvent(REFUND)` → **候补检查订阅者触发递补**（队列非空按 rank 通知第一位，UC302-6a；队列空无动作座位直接可售 6b）+ 通知订阅者；`RefundCompletedEvent` → 退款到账通知。
- undo：`RefundCommand.undo()` 补偿撤销（恢复 ISSUED/明细有效/座位已售），仅经 `CommandHistory.undoLast` 可达、不经 HTTP 暴露；座位已被重售时拒绝撤销。

**错误码**：12004 订单不存在；12005 非本人；13001/13002；10001/10002。

## 3. 改签（UC303）—— `POST /api/orders/{id}/change`

仅观众且**本人订单**。规则：限同影片其他场次、开映前 2 小时（与退票同口径）、**每单限 1 次**。

**路径参数**：`id`（long，订单 id）。

**请求体**：

| 字段 | 类型 | 必填 | 说明 | 非法后果 |
|---|---|---|---|---|
| targetShowingId | long | 是 | 目标场次 id（须同影片、其他场次、PUBLISHED、未开映） | 缺失 10003；无效/余座不足 13012 |

**响应 `data`**（ChangeShowingResponse）：

| 字段 | 类型 | 说明 |
|---|---|---|
| orderId | long | 订单 id |
| orderNo | string | 订单号（不变） |
| status | string | 保持 `ISSUED`（指向新场次） |
| originalShowingId | long | 原场次 id |
| newShowingId | long | 新场次 id |
| changeFromShowingId | long | 改签留痕（=newShowingId；每单限 1 次判定依据） |
| newSeatIds | long 数组 | 新场次座位 id 列表 |
| newTotalAmount | number | 改签后总额（按新场次票价策略重算） |

**实现口径（spec 沉默处最简实现，已决策记录）**：
- **差价不结算**：新票按新场次票价策略重新计价，total_amount 重置为新明细合计，不补款不退款。
- **选座策略**：优先目标场次"同排同座号"空闲座位，不足按 seat.id 升序取其余空闲座位补足；无足够余座拒绝（13012）。
- 执行（命令模式，`ChangeShowingCommand` 接 `CommandHistory`）：原座位明细失效 + 座位释放（releaseByChange）→ 新场次复用 SeatLockService 条件更新锁座 → 立即条件更新售出（改签单无二次支付环节）→ 重建有效明细（新策略快照价）→ 状态机 changeShowing 留痕 + showing 指向新场次 → 广播 `SeatReleasedEvent(CHANGE_SHOWING)`（原场次候补检查照常触发）。取票码沿用。
- 锁座并发失败（10005）→ 整体回滚，原座位随事务恢复不变。
- undo：`ChangeShowingCommand.undo()` 换回原场次（原座位被重售则拒绝），仅经 CommandHistory 可达。

**错误码**：12004/12005/13010/13011/13012/13013；11005 目标场次不存在；10005 锁座并发失败；10001/10002。

## 4. 加入候补（UC304）—— `POST /api/showings/{id}/queue`

仅观众。请求体可为空 JSON `{}`。

**路径参数**：`id`（long，场次 id）。

**响应 `data`**（QueueJoinResponse）：

| 字段 | 类型 | 说明 |
|---|---|---|
| queueId | long | 候补记录 id（**递补确认接口的路径参数，请前端保存**） |
| showingId | long | 场次 id |
| rank | int | 候补序号（越小越优先递补；按场次历史最大 rank+1 分配，过期占号不重排） |
| status | string | 固定 `WAITING`（排队中） |

**实现口径**：
- **候补准入**：仅"未开映的满座场次"（status=PUBLISHED、start_time>now、全部座位 SOLD）可候补，否则 13014。
- **重复候补拒绝**：同场次已有本人 WAITING 记录 → 13003（EXPIRED/MATCHED 后可重新候补）。
- 递补触发：该场次座位释放（退票/超时释放/主动放弃/改签）时，候补检查订阅者按 rank 取第一位发递补邀请（站内通知 type=`QUEUE_MATCHED`、title=候补递补通知、content 内嵌**递补凭据** `递补凭据Q{queueId}` 与确认截止时间）。

**错误码**：11005；13014/13003；10001/10002。

## 5. 候补确认递补并锁座（UC305）—— `POST /api/queue/{id}/claim`

仅观众且**本人候补记录**。请求体可为空 JSON `{}`。

**路径参数**：`id`（long，**候补记录 queueId**，来自接口 4 响应或递补通知凭据）。

**响应 `data`**（QueueClaimResponse）：

| 字段 | 类型 | 说明 |
|---|---|---|
| queueId | long | 候补记录 id |
| status | string | 固定 `MATCHED`（已递补） |
| orderId | long | 递补锁座后创建的待支付订单 id（**接 B 段支付接口 `POST /api/orders/{id}/pay`**） |
| orderNo | string | 待支付订单号 |
| showingId | long | 场次 id |
| seatId | long | 递补锁定的座位 id |
| lockExpireAt | string | 锁定截止时间（限期内未支付由超时释放任务取消 UC206） |
| estimatedAmount | number | 待支付预估金额（按场次策略计价） |

**实现口径（含编排者契约决策 3）**：
- **递补响应时限不加列**：确认截止 = 递补邀请通知的 `created_at` + `cinema.booking.queue-response-limit`（默认 3 分钟，NFR2 配置项）。邀请通知 content 内嵌递补凭据 `递补凭据Q{queueId}，`（尾缀"，"防 queueId 数字前缀歧义），后端按该令牌检索最新邀请推算截止。
- **超时顺延**：定时扫描（@Scheduled，与锁定超时释放同频，复用既有配置键 `release-scan-interval-millis`）+ claim 懒惰检查双路径驱动，语义一致幂等可重入；超时候补置 `EXPIRED` 并在仍有空闲座位时邀请下一位。
- **claim 流转**：确认前先做本场次懒惰顺延（超时者过期）→ 校验 WAITING + 有未过期邀请 → 取场次当前第一个空闲座位（seat.id 升序）复用 B 模块锁座下单编排（SeatLockService 条件更新 + 创建 PENDING_PAYMENT 订单）→ queue→MATCHED → 广播 `QueueMatchedEvent`（递补成功通知）。
- **座位不锚定邀请时刻的具体座位**：邀请的座位若被普通购票抢走而场次尚有其他余座，claim 取当前余座仍成功；全场无余座 → 13006，候补记录保持 WAITING 继续排队。
- 递补订单支付走 B 段 `POST /api/orders/{id}/pay`（复用，不在 C 段重复提供）。

**错误码**：13004（未收到递补/状态不符/记录不存在）/13005（超时）/13006（座位被抢）/13003；12005 非本人候补；11005；10001/10002。

## 6. 通知中心列表（UC306）—— `GET /api/notifications`

仅观众。无请求参数；返回本人全部站内通知，按 id 降序（新通知在前）。

**响应 `data`**（NotificationItemResponse 数组）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | long | 通知 id |
| type | string | `TICKET_ISSUED` 出票 / `SEAT_RELEASED` 座位释放 / `QUEUE_MATCHED` 递补（含邀请与递补成功两种） / `REFUND_COMPLETED` 退款到账 |
| title | string | 通知标题（递补邀请 title=候补递补通知） |
| content | string | 通知内容（递补邀请内嵌 `递补凭据Q{queueId}` 与截止时间） |
| readFlag | boolean | 已读标志，恒为 false（见下方语义） |
| createdAt | string | 创建时间（递补邀请的确认截止 = created_at + queue-response-limit） |

**read_flag 语义（编排者契约决策 4）**：本接口**仅列表、只读**，不翻转 read_flag；20 接口清单之外不加"标记已读"端点，故当前无任何置读入口，全部通知保持未读（false）返回。语义已如实记录，后续如需已读能力需另立接口。

**错误码**：10001/10002。

## 7. 检票核销（UC307，幂等）—— `POST /api/admin/checkin`

仅检票员（CHECKER）角色。请求体 JSON。

**请求体**：

| 字段 | 类型 | 必填 | 说明 | 非法后果 |
|---|---|---|---|---|
| orderNo | string | 二选一 | 订单号（与 pickupCode 均传时**订单号优先**） | 均缺 10003；检索不到 12004 |
| pickupCode | string | 二选一 | 取票码（8 位数字） | 同上 |

**响应 `data`**（CheckinResponse）：

| 字段 | 类型 | 说明 |
|---|---|---|
| orderId | long | 订单 id |
| orderNo | string | 订单号 |
| pickupCode | string | 取票码 |
| status | string | 固定 `CHECKED_IN`（已检票，终态） |
| checkinTime | string | 核销时间（落库 ticket_order.checkin_time） |
| operator | string | **核销操作员用户名**（编排者契约决策 2：ticket_order 无 operator 列且不扩表，操作员写入应用日志并在此返回；"核销记录含操作员"的 spec §1 缺口待用户定夺是否补列） |
| showingId | long | 场次 id |
| movieTitle | string | 片名 |
| hallName | string | 影厅名 |
| startTime | string | 开映时间 |
| seats | array | 票券座位明细：`{seatId, rowNum, colNum, zone, price}` |

**实现口径（含 A8 并发幂等）**：
- 仅 `ISSUED` 可核销：已检票重复核销 → **13008 幂等拒绝**（UC307-5a"仅一次生效"）；待支付/已退票/已取消 → 13007。
- **并发幂等（A8，task-04 挂账本卡落地）**：核销迁移以条件更新原子化 `UPDATE ticket_order SET status='CHECKED_IN', checkin_time=? WHERE id=? AND status='ISSUED'`——并发 N 会话核销恰一次生效，其余按重读状态返回 13008/13007（ticket_order 无 @Version 列，实体脏检查无并发守卫能力，故走条件更新；迁移语义与状态机 `Issued.checkIn` 逐字等价）。
- UC307-3b：开映超过 30 分钟（常量宽限）→ 13009 转人工；CHECKED_IN 为终态，核销后退票/改签一律 13001/13010 拒绝。
- CHECKED_IN 终态后不可再退改（spec §3.1 行 8）。

**错误码**：10003/12004/13007/13008/13009；10001/10002。

---

## 附：C 模块相关配置项（NFR2，修改配置不需改代码）

| 配置键 | 默认 | 说明 |
|---|---|---|
| `cinema.booking.refund-deadline` | 2h | 退票/改签截止（开映前多少时长停止受理，UC302-4b/UC303） |
| `cinema.booking.queue-response-limit` | 3m | 递补响应时限（邀请 created_at + 本值 = 确认截止，UC305） |
| `cinema.booking.release-scan-interval-millis` | 1000 | 递补超时顺延扫描间隔（复用 UC206 既有键，不加新配置） |
| `cinema.booking.lock-duration` | 演示 30s / 正式 15m | 递补确认后的锁定时长（复用 UC204 既有键） |

## 附：C 模块新增类清单（模式落点对照 spec §5）

| 类 | 模式角色 | UC |
|---|---|---|
| `service/aftermarket/command/AftermarketCommand` | Command（接口，均实现 undo） | UC302/303 |
| `service/aftermarket/command/RefundCommand` / `ChangeShowingCommand` | ConcreteCommand | UC302/303 |
| `service/aftermarket/RefundService` / `ChangeShowingService` | Receiver | UC302/303 |
| `service/aftermarket/CommandHistory` | 命令历史（进程内单例栈，undo 不经 HTTP） | UC302/303 |
| `common/event/QueueCheckSubscriber` | Observer（Observer 角色·候补检查订阅者，观察者第三件） | UC305 |
| `service/aftermarket/RefundValidator` / `ChangeValidator` | 纯逻辑校验器（U7 被测，无 DB 依赖） | UC302/303 |
| `service/aftermarket/QueueService` | 候补编排（join/claim/邀请/顺延/定时扫描） | UC304/305 |
| `service/aftermarket/CheckinService` | 核销编排（条件更新原子迁移，A8 载体） | UC307 |
| `service/aftermarket/OrderQueryService` / `NotificationQueryService` | 查询服务 | UC301/306 |
