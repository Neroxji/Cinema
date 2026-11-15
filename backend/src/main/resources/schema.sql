-- =====================================================================
-- 电影票在线购票系统 · 建表脚本（task-01 公共底座，9 张表）
-- 语法基准：MySQL 8；同一份脚本兼容 H2 MODE=MySQL（演示档已在 H2 实测执行通过）。
--   - 演示档（H2）：application.yml 默认 spring.sql.init.mode=always 自动执行本脚本；
--   - 正式档（MySQL）：先建库再手工执行（mysql profile 已关闭自动执行）：
--       CREATE DATABASE cinema DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
-- 兼容性约定：
--   1. 不使用 ENGINE=/CHARSET= 表选项与列内联 COMMENT（H2 不识别），注释以 -- 行注释承载；
--   2. `user`/`rows`/`rank` 为 H2/MySQL 保留字，统一反引号转义（H2 MySQL 模式支持反引号）；
--   3. 索引/约束一律用具名 CONSTRAINT ... UNIQUE / FOREIGN KEY 语法，双方言均可解析；
--   4. MySQL 8 侧本机无库，未实测（如实标注）；MySQL 侧务必以 utf8mb4 建库。
-- =====================================================================

-- 1. 用户表：三类角色账户（观众/排片员/检票员），被 ticket_order / queue / notification 引用
CREATE TABLE IF NOT EXISTS `user` (
    id            BIGINT       NOT NULL AUTO_INCREMENT, -- 主键
    username      VARCHAR(50)  NOT NULL,                -- 登录名（唯一）
    password_hash VARCHAR(100) NOT NULL,                -- 密码哈希，格式 salt$sha256hex（81 字符）
    role          VARCHAR(20)  NOT NULL,                -- 角色：AUDIENCE 观众 / SCHEDULER 排片员 / CHECKER 检票员
    phone         VARCHAR(20)  NULL,                    -- 手机号
    CONSTRAINT pk_user PRIMARY KEY (id),
    CONSTRAINT uk_user_username UNIQUE (username)
);

-- 2. 影片表：影片基础信息，1:N showing
CREATE TABLE IF NOT EXISTS movie (
    id           BIGINT       NOT NULL AUTO_INCREMENT, -- 主键
    title        VARCHAR(100) NOT NULL,                -- 片名
    genre        VARCHAR(50)  NULL,                    -- 类型（科幻/动作/喜剧…）
    duration_min INT          NULL,                    -- 片长（分钟）
    poster_url   VARCHAR(255) NULL,                    -- 海报地址
    status       VARCHAR(20)  NOT NULL,                -- 状态：ON_SHOW 上映中 / OFF_SHOW 已下架（UC104）
    CONSTRAINT pk_movie PRIMARY KEY (id)
);

-- 3. 影厅表：影厅与座位模板，1:N showing；座位由工厂按模板生成（UC102/UC103）
CREATE TABLE IF NOT EXISTS cinema_hall (
    id        BIGINT       NOT NULL AUTO_INCREMENT, -- 主键
    name      VARCHAR(50)  NOT NULL,                -- 影厅名称
    hall_type VARCHAR(20)  NOT NULL,                -- 类型：IMAX / STANDARD
    `rows`    INT          NOT NULL,                -- 行数（rows 为 MySQL/H2 关键字，须转义）
    `cols`    INT          NOT NULL,                -- 列数（每排座位数）
    zone_rule VARCHAR(255) NULL,                    -- 分区规则 JSON（工厂写入，与 seat.zone 一致）
    CONSTRAINT pk_cinema_hall PRIMARY KEY (id)
);

-- 4. 场次表：场次排片，N:1 movie、cinema_hall；1:N seat、ticket_order、queue
CREATE TABLE IF NOT EXISTS showing (
    id           BIGINT       NOT NULL AUTO_INCREMENT, -- 主键
    movie_id     BIGINT       NOT NULL,                -- 放映影片
    hall_id      BIGINT       NOT NULL,                -- 放映影厅（座位模板来源）
    start_time   DATETIME     NOT NULL,                -- 开映时间（退票截止=开映前 2 小时）
    status       VARCHAR(20)  NOT NULL,                -- 状态：PUBLISHED 已发布 / CANCELED 已取消 / FINISHED 预留
    price_type   VARCHAR(20)  NOT NULL,                -- 票价策略：ORIGINAL / MEMBER_DISCOUNT / ZONE_PRICING
    price_params VARCHAR(255) NULL,                    -- 计价参数 JSON（见 PriceType 枚举注释）
    CONSTRAINT pk_showing PRIMARY KEY (id),
    CONSTRAINT fk_showing_movie FOREIGN KEY (movie_id) REFERENCES movie (id),
    CONSTRAINT fk_showing_hall  FOREIGN KEY (hall_id)  REFERENCES cinema_hall (id)
);

-- 5. 场次座位表（并发核心表）：N:1 showing；唯一索引(场次,排,座)防同位置重复建座
CREATE TABLE IF NOT EXISTS seat (
    id             BIGINT      NOT NULL AUTO_INCREMENT, -- 主键（多座位加锁按 id 升序逐行执行，防死锁）
    showing_id     BIGINT      NOT NULL,                -- 所属场次
    row_num        INT         NOT NULL,                -- 排号（1 起）
    col_num        INT         NOT NULL,                -- 座号（1 起）
    zone           VARCHAR(20) NOT NULL,                -- 分区：GOLDEN 黄金区 / NORMAL 普通区
    status         VARCHAR(20) NOT NULL,                -- 状态：FREE 空闲 / LOCKED 锁定 / SOLD 已售
    lock_expire_at DATETIME    NULL,                    -- 锁定截止时间（锁定态必填；超时由定时任务释放 UC206）
    lock_user_id   BIGINT      NULL,                    -- 锁定人 user.id（不设外键：临时态，保持并发路径简单）
    version        BIGINT      NOT NULL DEFAULT 0,      -- @Version 乐观锁（条件更新 WHERE status='空闲' AND version=?）
    CONSTRAINT pk_seat PRIMARY KEY (id),
    CONSTRAINT uk_seat_showing_pos UNIQUE (showing_id, row_num, col_num),
    CONSTRAINT fk_seat_showing FOREIGN KEY (showing_id) REFERENCES showing (id)
);

-- 6. 订单表：N:1 user、showing；1:N order_item；状态机 5 态见 spec §3.1
CREATE TABLE IF NOT EXISTS ticket_order (
    id                     BIGINT        NOT NULL AUTO_INCREMENT, -- 主键
    order_no               VARCHAR(32)   NOT NULL,                -- 订单号（唯一；检票可凭订单号 UC307）
    user_id                BIGINT        NOT NULL,                -- 下单用户
    showing_id             BIGINT        NOT NULL,                -- 所属场次（改签后指向新场次）
    status                 VARCHAR(20)   NOT NULL,                -- PENDING_PAYMENT/ISSUED/CHECKED_IN/REFUNDED/CANCELED
    total_amount           DECIMAL(10,2) NOT NULL,                -- 订单总额=有效明细快照价之和
    pay_time               DATETIME      NULL,                    -- 支付时间（出票时间）
    checkin_time           DATETIME      NULL,                    -- 检票核销时间（UC307）
    change_from_showing_id BIGINT        NULL,                    -- 改签来源场次（可空；不设外键保留历史可读性）
    CONSTRAINT pk_ticket_order PRIMARY KEY (id),
    CONSTRAINT uk_ticket_order_no UNIQUE (order_no),
    CONSTRAINT fk_order_user    FOREIGN KEY (user_id)    REFERENCES `user` (id),
    CONSTRAINT fk_order_showing FOREIGN KEY (showing_id) REFERENCES showing (id)
);

-- 7. 订单明细表（票；防双卖唯一索引载体）：N:1 ticket_order、seat
--    唯一索引 (seat_id, valid_flag)：同一座位至多一条有效明细；退票置 NULL（NULL 不参与唯一比对），
--    座位可重售——"防双卖"与"退后重售"并存（spec §4 兼容方案 A）
CREATE TABLE IF NOT EXISTS order_item (
    id             BIGINT        NOT NULL AUTO_INCREMENT, -- 主键
    order_id       BIGINT        NOT NULL,                -- 所属订单
    seat_id        BIGINT        NOT NULL,                -- 票对应座位
    price_snapshot DECIMAL(10,2) NOT NULL,                -- 出票时快照价（策略切换不影响已出票订单 UC105）
    valid_flag     BIGINT        NULL,                    -- 1=有效；退票/失效置 NULL
    CONSTRAINT pk_order_item PRIMARY KEY (id),
    CONSTRAINT uk_order_item_seat_valid UNIQUE (seat_id, valid_flag),
    CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES ticket_order (id),
    CONSTRAINT fk_item_seat  FOREIGN KEY (seat_id)  REFERENCES seat (id)
);

-- 8. 候补队列表：N:1 showing、user；座位释放时按 rank 升序递补（UC304/305）
CREATE TABLE IF NOT EXISTS queue (
    id         BIGINT      NOT NULL AUTO_INCREMENT, -- 主键
    showing_id BIGINT      NOT NULL,                -- 候补的场次
    user_id    BIGINT      NOT NULL,                -- 候补观众
    `rank`     INT         NOT NULL,                -- 候补序号（rank 为 MySQL 8/H2 关键字，须转义）
    status     VARCHAR(20) NOT NULL,                -- WAITING 排队 / MATCHED 已递补 / EXPIRED 已过期 / CANCELED 已取消
    CONSTRAINT pk_queue PRIMARY KEY (id),
    CONSTRAINT fk_queue_showing FOREIGN KEY (showing_id) REFERENCES showing (id),
    CONSTRAINT fk_queue_user    FOREIGN KEY (user_id)    REFERENCES `user` (id)
);

-- 9. 站内通知表：N:1 user；由观察者订阅者写入（UC306）
CREATE TABLE IF NOT EXISTS notification (
    id         BIGINT        NOT NULL AUTO_INCREMENT, -- 主键
    user_id    BIGINT        NOT NULL,                -- 接收用户
    type       VARCHAR(20)   NOT NULL,                -- TICKET_ISSUED/SEAT_RELEASED/QUEUE_MATCHED/REFUND_COMPLETED
    title      VARCHAR(100)  NOT NULL,                -- 通知标题
    content    VARCHAR(1000) NULL,                    -- 通知内容（双方言兼容起见未用 TEXT）
    read_flag  BOOLEAN       NOT NULL DEFAULT FALSE,  -- 已读标志（MySQL 下即 TINYINT(1)）
    created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 创建时间
    CONSTRAINT pk_notification PRIMARY KEY (id),
    CONSTRAINT fk_notice_user FOREIGN KEY (user_id) REFERENCES `user` (id)
);
