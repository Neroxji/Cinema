# 电影票在线购票系统 · 系统设计文档（spec 初稿）

| 文档信息 | 内容 |
|---|---|
| 版本 / 状态 | v0.3 · 决策全部确认（待小组会背书后定稿） |
| 依据 | 《PRD-需求规格说明书-初稿.md》v0.1（**PRD 尚未定稿，定稿后需复核本 spec**） |
| 硬约束 | 单体 Spring Boot（controller/service/repository/model）；9 张表：开发期 H2 文件库（MySQL 兼容模式，零安装），交付期 docker-compose（应用 + MySQL 8）一键部署；模式 7 种=状态(×2)/策略/观察者/命令/工厂/单例/模板方法；并发=先查后锁→@Version→唯一索引(valid_flag)→升序加锁→释放前复查 |
| 用途 | ① 报告第四/五章底稿 ② AI 辅助编码上下文 |
| 引用约定 | 一律以 UC 编号引用 PRD，不重复 PRD 内容 |

---

## 1. 数据库设计（9 张表）

| 表名 | 用途 | 关键字段 | 与哪些表什么关系 |
|---|---|---|---|
| user | 三类角色账户（观众/排片员/检票员） | id, username, password_hash, role, phone | 被 ticket_order / queue 引用 |
| movie | 影片基础信息 | id, title, genre, duration_min, poster_url, status | 1:N showing |
| cinema_hall | 影厅与座位模板 | id, name, hall_type, rows, cols, zone_rule | 1:N showing；座位由工厂按模板生成（UC102/103） |
| showing | 场次排片 | id, movie_id, hall_id, start_time, status, price_type, price_params | N:1 movie、cinema_hall；1:N seat、ticket_order、queue |
| seat | 场次座位（并发核心表） | id, showing_id, row_num, col_num, zone, status, lock_expire_at, lock_user_id, version | N:1 showing；唯一索引(showing_id,row_num,col_num)；1:1 order_item |
| ticket_order | 购票订单 | id, order_no, pickup_code(取票码, 8位数字, 出票时生成, 全库唯一索引), user_id, showing_id, status, total_amount, pay_time, checkin_time, change_from_showing_id(改签来源,可NULL) | N:1 user、showing；1:N order_item |
| order_item | 订单明细（票；防双卖唯一索引载体） | id, order_id, seat_id, price_snapshot, valid_flag(退票置NULL) | N:1 ticket_order；N:1 seat；唯一索引(seat_id, valid_flag) |
| queue | 候补队列 | id, showing_id, user_id, rank, status(排队/已递补/过期/取消) | N:1 showing、user；座位释放时按 rank 递补（UC304/305） |
| notification | 站内通知 | id, user_id, type, title, content, read_flag, created_at | N:1 user；由观察者订阅者写入（UC306） |

> 已确认：通知独立成表，全库 9 张，符合"约 8 张"口径。
> 已确认：库表 snake_case、Java 驼峰，框架自动转换。

## 2. 接口清单（20 个）

**公共**
- POST /api/auth/register —— 注册观众账号（UC201）
- POST /api/auth/login —— 登录并建立会话（UC201）

**A 排片与票价（管理端）**
- POST /api/admin/movies —— 新增并上架影片（UC101）
- PUT /api/admin/movies/{id} —— 编辑影片信息或上下架（UC104）
- POST /api/admin/halls —— 创建影厅与座位模板（UC102）
- POST /api/admin/showings —— 创建场次并按模板生成座位（UC103）
- PUT /api/admin/showings/{id}/cancel —— 下架影片/取消场次（UC104）
- PUT /api/admin/showings/{id}/strategy —— 配置/切换票价策略（UC105）
- GET /api/admin/showings —— 查询排片列表（UC103/104）

**B 在线购票（观众端）**
- GET /api/movies —— 查询上映中影片列表（UC202）
- GET /api/showings/{id}/seats —— 查询场次详情与实时座位图（轮询）（UC202/UC203）
- POST /api/showings/{id}/seats/lock —— 选座锁座（并发核心接口，见第4节）（UC204）
- POST /api/orders/{id}/pay —— 模拟支付并出票（UC205）

**C 订单售后与通知**
- GET /api/orders —— 查询我的订单列表（UC301）
- POST /api/orders/{id}/refund —— 退票并触发候补递补检查（UC302）
- POST /api/orders/{id}/change —— 改签至同影片其他场次（UC303）
- POST /api/showings/{id}/queue —— 加入候补队列（UC304）
- POST /api/queue/{id}/claim —— 候补观众确认递补并锁座（UC305）
- GET /api/notifications —— 通知中心列表（UC306）
- POST /api/admin/checkin —— 检票核销（幂等）（UC307）

## 3. 状态转移表

### 3.1 订单（TicketOrder）：待支付 / 已出票 / 已检票 / 已退票 / 已取消

| 当前状态 | 事件 | 次状态 | 动作 |
|---|---|---|---|
| 待支付 | 支付成功（UC205） | 已出票 | 座位→已售；终止锁定倒计时；广播出票事件 |
| 待支付 | 支付失败（UC205 扩展3a） | 待支付 | 座位维持锁定至倒计时结束 |
| 待支付 | 锁定超时（UC206） | 已取消 | 座位→空闲；广播释放事件，触发候补检查 |
| 待支付 | 观众主动放弃 | 已取消 | 同上 |
| 已出票 | 检票核销（UC307） | 已检票 | 记录核销时间/操作员；**终态，拒绝一切变更** |
| 已出票 | 退票（UC302） | 已退票 | 座位→空闲；模拟退款；广播释放事件→候补递补 |
| 已出票 | 改签（UC303） | 已出票（新场次） | 原座位→空闲；新场次重新锁座出票；留存改签记录 |
| 已检票 / 已退票 / 已取消 | 任意事件 | 不变 | 状态机拒绝迁移 |

### 3.2 座位（Seat，场次内生命周期）：空闲 / 锁定 / 已售

| 当前状态 | 事件 | 次状态 | 动作 |
|---|---|---|---|
| 空闲 | 观众选座（UC204） | 锁定 | 条件更新+乐观锁；写 lock_expire_at / lock_user_id，version+1 |
| 空闲 | 候补递补成功（UC305） | 锁定 | 为候补观众直接锁定 |
| 锁定 | 支付出票（UC205） | 已售 | 生成订单明细（快照 price_snapshot） |
| 锁定 | 锁定超时（UC206） | 空闲 | **释放前复查** status=锁定 且已过期；条件更新释放；清空 lock_user_id |
| 锁定 | 观众主动放弃（UC204） | 空闲 | 同上 |
| 已售 | 退票（UC302） | 空闲 | 广播释放事件；触发候补检查（UC305） |
| 已售 | 改签（UC303） | 空闲 | 原场次座位释放，新场次重新走锁座流程 |

## 4. 锁座并发方案（UC204 步骤4，NFR1）

1. **事务外预检**：读座位图快照，目标座位非"空闲"直接返回失败（快速失败，减少数据库压力）。
2. **开启事务**：事务边界 = 从对 seat 的条件更新，到锁定记录提交为止，全程一个事务。
3. **条件更新（乐观锁）**：多座位时按 seat.id **升序逐行**执行 `UPDATE seat SET status='锁定', lock_expire_at=?, lock_user_id=?, version=version+1 WHERE id=? AND status='空闲' AND version=?`；影响行数=0 即并发失败，整体回滚并返回"座位已被占用"（保证 NFR1 有且仅有一个成功）。
4. **唯一索引兜底**：order_item 以 (seat_id, valid_flag) 唯一索引保证同一座位至多一条有效明细；退票时 valid_flag 置 NULL（不参与唯一性比对），座位可重售。即使乐观锁被绕过，重复占用在约束处报错回滚。
5. **提交**：事务提交后锁定原子生效，返回锁定截止时间；前端倒计时仅为展示，不承担释放职责。
6. **超时释放（UC206）**：@Scheduled 短事务扫描 lock_expire_at < now 且 status='锁定'，**释放前复查**后条件更新释放，成功后广播释放事件→候补递补检查（UC305）。

> 已确认：兼容方案 A——order_item 增加 valid_flag 列，退票置 NULL 绕开唯一索引，"防双卖"与"退后重售"并存。
> 已确认：多座位按 seat.id 升序逐行加锁，全系统统一顺序防死锁。
> 已确认：锁定截止时间字段名采用 lock_expire_at。

## 5. 模式落点清单

| 模式 | 具体类名 | 所在模块 | 关联 UC |
|---|---|---|---|
| 状态（座位） | 接口 SeatStatus + 实现 SeatFree / SeatLocked / SeatSold（Context=Seat） | B | UC204 / 205 / 206 |
| 状态（订单） | 接口 OrderStatus + 实现 Pending / Issued / CheckedIn / Refunded / Canceled（Context=TicketOrder） | C | UC205 / 302 / 307 |
| 策略（票价） | 接口 PriceStrategy + 实现 OriginalPrice / MemberDiscount / ZonePricing | A | UC105 / 204 / 205 |
| 观察者（事件广播） | DomainEventPublisher + 出票/释放事件；订阅者 NoticeSubscriber、QueueCheckSubscriber | C | UC205 / 302 / 305 / 306 |
| 命令（退改签） | RefundCommand / ChangeShowingCommand（均实现 undo，接 CommandHistory） | C | UC302 / 303 |
| 工厂方法（座位模板） | SeatMatrixFactory + ImaxSeatFactory / StandardSeatFactory | A | UC102 / 103 |
| 单例（配置中心） | BookingConfig（锁定时长/候补时限/退票时限，@ConfigurationProperties 单例） | 公共 | UC204 / 206 / 302 |
| 模板方法（支付流程） | AbstractPayFlow：参数校验→支付网关→状态迁移→事件广播，骨架固定 | B | UC205 |

> 已确认：砍掉备忘录——改签历史由 ticket_order.change_from_showing_id 字段 + 通知记录承载，不包装成模式。模式共 7 种。

## 6. 4+1 视图

- **逻辑视图**：按模块 A/B/C 划分三个顶层包（排片管理 / 在线购票 / 订单售后），核心领域对象为 Showing、Seat、TicketOrder、PriceStrategy、QueueRecord；Seat 与 TicketOrder 经 order_item 关联，价格与状态行为以策略/状态接口多态实现。
- **开发视图**：Maven 单工程，分层 controller / service / repository / model，模块以包边界隔离并各自命名前缀；PlantUML 管理设计图源文件，Git 主干+特性分支协作。
- **进程视图**：单体单进程（Spring Boot 内嵌 Tomcat），HTTP 请求与 @Scheduled 定时任务同进程，无消息中间件。
- **物理视图**：演示口径单机单节点——浏览器 → Spring Boot(8080) → MySQL(3306)；备用 H2 文件库内嵌免安装。
- **场景视图**：核心场景为 UC204→UC205（锁座支付）、UC302→UC305（退票递补）、UC307（检票核销）、UC105（策略热切换）；顺序图按此 4 条主线绘制，满足"合计≥3 张"要求。

## 7. 开发补充约定（AI 辅助开发时随 spec 一并下发）

1. **鉴权**：Session + Cookie（演示最简），管理端接口按 role 校验（排片员/检票员），不做 JWT。
2. **统一响应**：所有接口返回 `{code, message, data}` 外壳，业务错误码按模块自定义。
3. **轮询**：座位图页 3 秒/次（对齐 NFR4）；订单页不轮询，操作后主动刷新。
4. **字段表先行**：AI 生成接口代码前，先输出请求/响应字段表供人工确认，确认后前端才对接（防与原型字段名脱节）。
5. **公共部分先行**：实体、建表 SQL、统一配置、事件定义先一次性生成并全组共用，之后 A/B/C 三模块才允许分头开发。
6. **种子数据**：3 部影片、3 个影厅（1 个 IMAX 10×5 分区 + 2 个普通 8×6）、6 个场次（含 1 个满座场用于演示候补）、观众/排片员/检票员演示账号各 1；由 DataSeeder 启动时写入。
7. **事件清单（观察者落点，模块间唯一联动方式）**：
   - 出票成功（UC205）→ 通知订阅者写站内通知
   - 座位释放（退票 UC302 / 超时 UC206 / 主动放弃）→ 候补检查订阅者触发递补（UC305）+ 通知订阅者
   - 递补成功（UC305）→ 通知订阅者
   - 退款到账（UC302）→ 通知订阅者

---

## 附：PRD 全文

【待 PRD 定稿后粘贴于此；当前 v0.2 见 `docs/PRD-需求规格说明书-初稿.md`】
