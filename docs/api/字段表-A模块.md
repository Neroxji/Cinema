# 字段表 · A 模块（管理端 7 接口）

> 交付依据：task-02 实现代码（`backend/src/main/java/com/cinema/controller/admin` + `service/admin`），与代码完全一致。
> 供 task-06/07 前端对接。**只新建本文件，不改任何既有文档。**
>
> 通用约定：
> - 统一响应外壳：`{ "code": 0, "message": "成功", "data": ... }`；`code=0` 成功，非 0 见错误码表。
> - 鉴权：A 段全部接口要求已登录（Session + Cookie），且角色为**排片员（SCHEDULER）**；
>   未登录返回 `10001`（HTTP 401），角色不符返回 `10002`（HTTP 403）。
> - 枚举值（如 `ON_SHOW`、`IMAX`、`ORIGINAL`）一律传/回**大写下划线枚举名**；
>   非法枚举值会被 Jackson 拒绝并落到 `10003 参数非法：请求体缺失或格式错误`。
> - 时间格式统一 ISO-8601：`yyyy-MM-dd'T'HH:mm:ss`（如 `2026-11-20T18:30:00`）。
> - 业务校验失败（110xx）HTTP 状态：11001/11005/11008→404，11002/11004/11006/11007→409，11003→400；10003→400。

## A 段错误码总表（11xxx）

| code | 枚举名 | HTTP | 含义 |
|---|---|---|---|
| 11001 | MOVIE_NOT_FOUND | 404 | 影片不存在 |
| 11002 | HALL_TIME_CONFLICT | 409 | 该影厅该时段已有场次（UC103-2a） |
| 11003 | INVALID_STRATEGY_PARAM | 400 | 票价策略参数非法（UC105-3a / UC103-3） |
| 11004 | SHOWING_CANCEL_FORBIDDEN | 409 | 场次存在已售或锁定中的座位，禁止取消（I14） |
| 11005 | SHOWING_NOT_FOUND | 404 | 场次不存在 |
| 11006 | SHOWING_NOT_CANCELABLE | 409 | 场次当前状态不允许取消（非 PUBLISHED） |
| 11007 | MOVIE_OFF_SHOW | 409 | 影片已下架，无法创建场次（UC103-5a） |
| 11008 | HALL_NOT_FOUND | 404 | 影厅不存在 |

公共段（沿用 task-01）：10000 系统异常 / 10001 未登录 / 10002 无权限 / 10003 参数非法 / 10004 资源不存在 / 10005 座位已被占用。

## price_params 三档参数约定（UC105，沿用 PriceType 枚举注释）

| priceType | 参数 JSON | 约束 |
|---|---|---|
| ORIGINAL（全场原价） | `{"basePrice":38.00}` | basePrice 必填且 > 0 |
| MEMBER_DISCOUNT（会员日折扣） | `{"basePrice":40.00,"discount":0.8}` | basePrice > 0；discount ∈ (0,1) 开区间（0/1/越界均拒绝） |
| ZONE_PRICING（分区计价） | `{"goldenPrice":45.00,"normalPrice":35.00}` | 两档价均必填且 > 0 |

金额一律 2 位小数（HALF_UP）。策略即时生效；已出票订单金额为 `order_item.price_snapshot` 快照，切换策略不影响。

---

## 1. 新增并上架影片（UC101）—— `POST /api/admin/movies`

创建即上架（`status` 固定写为 `ON_SHOW`，请求不含状态字段）。

**请求体**（`application/json`）：

| 字段 | 类型 | 必填 | 说明 | 非法后果 |
|---|---|---|---|---|
| title | string | 是 | 片名，≤100 字 | 10003 |
| genre | string | 否 | 类型（科幻/动作/喜剧…），≤50 字 | 10003 |
| durationMin | int | 否 | 片长（分钟），> 0；创建场次时段冲突校验取该值 | 10003 |
| posterUrl | string | 否 | 海报地址，≤255 字 | 10003 |

**响应 `data`**（MovieResponse）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | long | 影片 id |
| title | string | 片名 |
| genre | string \| null | 类型 |
| durationMin | int \| null | 片长分钟 |
| posterUrl | string \| null | 海报地址 |
| status | string | 固定 `ON_SHOW` |

## 2. 编辑影片信息或上下架（UC104）—— `PUT /api/admin/movies/{id}`

**路径参数**：`id`（long，必填，影片 id）。

**请求体**（部分更新：**仅非 null 字段被更新**，null/缺省字段保持原值）：

| 字段 | 类型 | 必填 | 说明 | 非法后果 |
|---|---|---|---|---|
| title | string | 否 | 片名，≤100 字 | 10003 |
| genre | string | 否 | 类型，≤50 字 | 10003 |
| durationMin | int | 否 | 片长（分钟），> 0 | 10003 |
| posterUrl | string | 否 | 海报地址，≤255 字 | 10003 |
| status | string | 否 | `ON_SHOW` 上架 / `OFF_SHOW` 下架 | 10003（非法枚举） |

**响应 `data`**：同接口 1 的 MovieResponse（更新后的完整影片）。

> 注：下架（OFF_SHOW）**不级联取消**既有场次；观众端仅可见 ON_SHOW 影片，且再次为该影片创建场次会被 11007 拦截（UC103-5a）。

**错误码**：11001 影片不存在；10003 参数非法。

## 3. 创建影厅与座位模板（UC102）—— `POST /api/admin/halls`

行列数/分区规则**不在请求中传入**：座位模板由工厂按 `hallType` 固定提供
（IMAX：5 排×10 列=50 座，第 2~4 排黄金区；普通厅：6 排×8 列=48 座，不分区）。
创建影厅只落模板（cinema_hall 行），**不预生成座位**——座位按场次在接口 4（UC103-4）生成。

**请求体**：

| 字段 | 类型 | 必填 | 说明 | 非法后果 |
|---|---|---|---|---|
| name | string | 是 | 影厅名称，≤50 字 | 10003 |
| hallType | string | 是 | `IMAX` / `STANDARD`（决定座位模板工厂） | 10003（非法枚举） |

**响应 `data`**（HallResponse）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | long | 影厅 id |
| name | string | 影厅名称 |
| hallType | string | `IMAX` / `STANDARD` |
| rows | int | 行数（排数，工厂模板：IMAX=5，普通=6） |
| cols | int | 列数（每排座位数：IMAX=10，普通=8） |
| zoneRule | string | 分区规则 JSON（如 `{"type":"GOLDEN_ROWS","goldenRows":[2,3,4],"desc":"..."}` / `{"type":"NONE",...}`） |
| seatCount | int | 座位总数 = rows × cols（50/48） |

**错误码**：10003 参数非法。

## 4. 创建场次并按模板生成座位（UC103）—— `POST /api/admin/showings`

校验顺序：影片存在（11001）→ 影片上架（11007）→ 影厅存在（11008）→ 时段冲突（11002）→ 策略参数（11003）。
全部通过后：落 showing（PUBLISHED）并按影厅模板生成全部"空闲"座位。

- 时段冲突规则：同影厅 **PUBLISHED** 场次时间区间重叠即拒绝（区间 = 开映时间起，加影片 `durationMin`，缺省按 0 分钟；**缓冲 0 分钟**，紧接排片允许）；CANCELED 场次不占用时段。
- UC103-3a：`priceType` 缺省（未配置策略）→ 默认套用 `ORIGINAL` 并在 `message` 中提示"未配置票价策略，已默认套用全场原价"；此时 `priceParams` 仍必填（含 basePrice）——**无价场次不可售**。

**请求体**：

| 字段 | 类型 | 必填 | 说明 | 非法后果 |
|---|---|---|---|---|
| movieId | long | 是 | 影片 id | 10003（缺失）；11001（不存在）；11007（已下架） |
| hallId | long | 是 | 影厅 id | 10003（缺失）；11008（不存在） |
| startTime | string | 是 | 开映时间，ISO-8601 `yyyy-MM-dd'T'HH:mm:ss` | 10003 |
| priceType | string | 否 | `ORIGINAL` / `MEMBER_DISCOUNT` / `ZONE_PRICING`；缺省默认 ORIGINAL（3a） | 10003（非法枚举） |
| priceParams | object | 是 | 三档参数对象（见约定表） | 11003（缺失/折扣系数∉(0,1)/分区价≤0） |

**响应 `data`**（ShowingResponse；接口 5/7 同构）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | long | 场次 id |
| movieId | long | 影片 id |
| movieTitle | string | 片名（冗余展示字段） |
| hallId | long | 影厅 id |
| hallName | string | 影厅名称（冗余展示字段） |
| startTime | string | 开映时间（ISO-8601） |
| status | string | `PUBLISHED` / `CANCELED` / `FINISHED`（预留） |
| priceType | string | 生效的票价策略类型 |
| priceParams | object \| null | 生效的计价参数（对象形式） |
| totalSeats | int | 座位总数（按影厅模板生成） |
| freeSeats | int | 空闲座位数 |
| lockedSeats | int | 锁定中座位数 |
| soldSeats | int | 已售座位数 |

**响应 `message`**：默认"排片成功"；命中 3a 时为"排片成功；未配置票价策略，已默认套用全场原价"。

**错误码**：11001 / 11007 / 11008 / 11002 / 11003 / 10003。

## 5. 取消场次（UC104 / I14）—— `PUT /api/admin/showings/{id}/cancel`

无请求体。规则：仅 `PUBLISHED` 场次可取消（重复取消/已结束 → 11006）；存在**已售或锁定中**座位一律拒绝（11004，锁定中取消会产生悬挂锁定，保守处理）；取消仅置 `CANCELED`，不级联改动座位/订单/候补。

**路径参数**：`id`（long，必填，场次 id）。

**响应 `data`**：ShowingResponse（`status` 已变为 `CANCELED`，其余座位统计不变）。

**错误码**：11005 场次不存在；11006 状态不允许取消；11004 存在已售/锁定座位。

## 6. 配置/切换票价策略（UC105）—— `PUT /api/admin/showings/{id}/strategy`

策略保存后**即时生效**；已出票订单金额快照不受影响。响应携带示例价格预览（UC105-4）。

**路径参数**：`id`（long，必填，场次 id）。

**请求体**：

| 字段 | 类型 | 必填 | 说明 | 非法后果 |
|---|---|---|---|---|
| priceType | string | 是 | `ORIGINAL` / `MEMBER_DISCOUNT` / `ZONE_PRICING` | 10003（缺失/非法枚举） |
| priceParams | object | 是 | 三档参数对象（见约定表） | 11003（缺失/折扣系数∉(0,1)/分区价≤0） |

**响应 `data`**（StrategyResponse）：

| 字段 | 类型 | 说明 |
|---|---|---|
| showingId | long | 场次 id |
| priceType | string | 已生效的票价策略类型 |
| priceParams | object | 已生效的计价参数 |
| examplePrices | object | 示例价格预览：键为分区名。`ZONE_PRICING` 返回 `{"NORMAL":35.00,"GOLDEN":45.00}`；全场类策略仅 `{"NORMAL":...}`（金额 2 位小数） |

**错误码**：11005 场次不存在；11003 策略参数非法；10003 参数非法。

## 7. 排片列表查询（UC103/104）—— `GET /api/admin/showings`

**Query 参数**（全部可选，组合过滤）：

| 参数 | 类型 | 必填 | 说明 | 非法后果 |
|---|---|---|---|---|
| movieId | long | 否 | 按影片过滤 | — |
| hallId | long | 否 | 按影厅过滤 | — |
| status | string | 否 | `PUBLISHED` / `CANCELED` / `FINISHED`（大小写不敏感） | 10003（取值无效） |

**响应 `data`**：`ShowingResponse` 数组，按 `startTime` 升序；元素结构与接口 4 完全一致（含座位统计）。

**错误码**：10003（status 非法）；10001/10002（鉴权）。

---

## 附：与 spec 的解读差异（实现口径，前端对接请注意）

1. **UC103-3a**："未配置票价策略"指未传 `priceType`；默认套用全场原价，但 `priceParams`（含 basePrice）始终必填——无价场次不可售；命中默认时以 `message` 提示。
2. **时段冲突**：同影厅 PUBLISHED 场次区间重叠（时长取影片 `durationMin`，缺省按 0 分钟；缓冲 0 分钟，紧接排片允许）。
3. **取消场次**：存在已售**或锁定中**座位均拒绝（spec 仅写已售；锁定中取消会产生悬挂锁定，保守处理）。
4. **下架影片**：不级联取消既有场次（UC103-5a 已在下一次创建场次时拦截）。
5. **取消无开映时间限制**：spec 未约束"已开映不可取消"，当前仅校验 I14 与状态位。
6. **创建影厅**：行列/分区由工厂固定模板决定（IMAX 5×10 / 普通 6×8），不接受自定义行列。
