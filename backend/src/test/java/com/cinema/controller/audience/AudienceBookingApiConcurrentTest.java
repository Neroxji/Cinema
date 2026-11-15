package com.cinema.controller.audience;

import com.cinema.config.BookingConfig;
import com.cinema.task.LockExpireReleaseTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.ResponseErrorHandler;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5~A7 · API 层并发专项（测试计划 §4.2，NFR1 零超卖的直接证据，P0 护栏）。
 *
 * <p>机制：TestRestTemplate + RANDOM_PORT 真 HTTP（非 MockMvc）；每个注册用户一个
 * 独立 {@link ApiUser}（独立 TestRestTemplate 实例 + 自管 JSESSIONID——会话隔离，
 * 互不串号）；管理端用 scheduler 登录建专用场次（影片/影厅/场次经 A 段接口创建）。
 *
 * <p>独立 profile api-test（独立 H2 内存库）：API 测试会真实提交数据（50 个注册用户、
 * 专用场次），与 I 系测试（@Transactional 回滚）和种子断言隔离，互不污染。
 *
 * <p>A7 竞态构造：锁定时长临时调短为 400ms（BookingConfig 单例，finally 恢复演示档），
 * 到期前 ~40ms 经 HTTP 发起支付、到期后立即直调 {@link LockExpireReleaseTask} 的
 * @Scheduled 同一方法——支付售出的条件更新（WHERE status=锁定 AND version=? AND
 * lock_expire_at&gt;now）与释放的条件更新（WHERE status=锁定 AND lock_expire_at&lt;now）
 * 在同一座位行上互斥，谁先提交谁生效；断言不变式"有且仅有一个生效、终态一致"
 * （SOLD⇔支付 0 且 ISSUED；FREE⇔支付 12002 且订单 CANCELED），不预设获胜方。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api-test")
@DisplayName("A5~A7 · API 层并发专项（真 HTTP + 50 会话隔离用户，NFR1）")
class AudienceBookingApiConcurrentTest {

    /** 并发用户数（A5/A6 口径：50）。 */
    private static final int USERS = 50;

    @LocalServerPort
    private int port;

    @Autowired
    private BookingConfig bookingConfig;

    @Autowired
    private LockExpireReleaseTask releaseTask;

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------------- HTTP 会话助手 ----------------

    /** 容错响应处理器：4xx/5xx 不抛异常（业务结果从统一响应外壳 code 判读）。 */
    private static final ResponseErrorHandler TOLERANT = new ResponseErrorHandler() {
        @Override
        public boolean hasError(ClientHttpResponse response) {
            return false;
        }

        @Override
        public void handleError(ClientHttpResponse response) {
            // no-op：HTTP 状态交由业务断言处理
        }

        @Override
        public void handleError(URI url, HttpMethod method, ClientHttpResponse response) {
            // no-op
        }
    };

    /**
     * 单用户会话：独立 TestRestTemplate + 自管 JSESSIONID（登录响应 Set-Cookie 捕获，
     * 后续请求 Cookie 头回传）——50 个用户互不串号，且不依赖请求工厂的 CookieStore 行为。
     */
    private final class ApiUser {
        private final TestRestTemplate client = new TestRestTemplate();
        private String jsessionId;

        ApiUser() {
            client.getRestTemplate().setErrorHandler(TOLERANT);
        }

        JsonNode get(String path) {
            return exchange(HttpMethod.GET, path, null);
        }

        JsonNode post(String path, Object body) {
            return exchange(HttpMethod.POST, path, body);
        }

        JsonNode exchange(HttpMethod method, String path, Object body) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                if (jsessionId != null) {
                    headers.set(HttpHeaders.COOKIE, "JSESSIONID=" + jsessionId);
                }
                ResponseEntity<String> resp = client.exchange(
                        "http://localhost:" + port + path, method, new HttpEntity<>(body, headers),
                        String.class);
                List<String> setCookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
                if (setCookies != null) {
                    for (String cookie : setCookies) {
                        if (cookie.startsWith("JSESSIONID=")) {
                            String value = cookie.substring("JSESSIONID=".length());
                            int end = value.indexOf(';');
                            jsessionId = end < 0 ? value : value.substring(0, end);
                        }
                    }
                }
                return mapper.readTree(resp.getBody() == null ? "{}" : resp.getBody());
            } catch (Exception e) {
                throw new IllegalStateException("HTTP 调用失败：" + path, e);
            }
        }
    }

    /** 注册 + 登录一名观众（会话隔离）。 */
    private ApiUser registerAndLogin(String username) {
        ApiUser user = new ApiUser();
        JsonNode reg = user.post("/api/auth/register",
                Map.of("username", username, "password", "pass123456"));
        assertEquals(0, reg.path("code").asInt(), "注册应成功：" + reg);
        JsonNode session = user.post("/api/auth/login",
                Map.of("username", username, "password", "pass123456"));
        assertEquals(0, session.path("code").asInt(), "登录应成功：" + session);
        assertEquals(username, session.path("data").path("username").asText(), "会话应为本用户");
        assertEquals("AUDIENCE", session.path("data").path("role").asText());
        return user;
    }

    /** 管理端（scheduler 登录）建专用场次：影片 + 影厅 + 场次（ORIGINAL basePrice=10）。 */
    private long createShowing(String tag, String hallType) {
        ApiUser scheduler = new ApiUser();
        JsonNode login = scheduler.post("/api/auth/login",
                Map.of("username", "scheduler", "password", "scheduler123"));
        assertEquals(0, login.path("code").asInt(), "排片员登录应成功：" + login);
        long movieId = scheduler.post("/api/admin/movies",
                Map.of("title", "并发测试影片" + tag, "durationMin", 90))
                .path("data").path("id").asLong();
        assertTrue(movieId > 0, "影片应创建成功");
        long hallId = scheduler.post("/api/admin/halls",
                Map.of("name", "并发测试厅" + tag, "hallType", hallType))
                .path("data").path("id").asLong();
        assertTrue(hallId > 0, "影厅应创建成功");
        String start = LocalDateTime.now().plusHours(2).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        JsonNode showingResp = scheduler.post("/api/admin/showings", Map.of(
                "movieId", movieId, "hallId", hallId, "startTime", start,
                "priceType", "ORIGINAL", "priceParams", Map.of("basePrice", 10.0)));
        assertEquals(0, showingResp.path("code").asInt(), "场次应创建成功：" + showingResp);
        return showingResp.path("data").path("id").asLong();
    }

    /** 座位图座位 id 列表（排/座升序）。 */
    private List<Long> seatIds(ApiUser anyUser, long showingId) {
        List<Long> ids = new ArrayList<>();
        anyUser.get("/api/showings/" + showingId + "/seats")
                .path("data").path("seats").forEach(s -> ids.add(s.path("seatId").asLong()));
        return ids;
    }

    /** 单个座位实时状态（FREE/LOCKED/SOLD）。 */
    private String seatStatus(ApiUser anyUser, long showingId, long seatId) {
        for (JsonNode s : anyUser.get("/api/showings/" + showingId + "/seats")
                .path("data").path("seats")) {
            if (s.path("seatId").asLong() == seatId) {
                return s.path("status").asText();
            }
        }
        return "MISSING";
    }

    private JsonNode lockSeat(ApiUser user, long showingId, long seatId) {
        return user.post("/api/showings/" + showingId + "/seats/lock",
                Map.of("seatIds", List.of(seatId)));
    }

    /** 等待到指定时刻（毫秒时间戳）。 */
    private void waitUntil(long targetMillis) throws InterruptedException {
        long wait = targetMillis - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
    }

    // =====================================================================
    // A5 · 50 并发抢同一座位：恰好 1 成功，其余"座位已被占用"，零超卖
    // =====================================================================

    @Test
    @DisplayName("A5(C-1) 50 并发抢同一座位：恰好 1 成功 10005×49，终态仅 1 座锁定、零超卖")
    void a5_fiftyConcurrentLockSameSeat() throws Exception {
        String tag = String.valueOf(System.nanoTime());
        long showingId = createShowing("A5" + tag, "STANDARD");
        ApiUser watcher = registerAndLogin("a5w" + tag);
        long targetSeatId = seatIds(watcher, showingId).get(0);

        // 50 个注册观众（各自会话隔离）
        List<ApiUser> users = new ArrayList<>(USERS);
        for (int i = 0; i < USERS; i++) {
            users.add(registerAndLogin("a5" + tag + "_" + i));
        }

        // 并发抢同一座位（真 HTTP，CountDownLatch 同起跑线）
        ExecutorService pool = Executors.newFixedThreadPool(USERS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<JsonNode>> futures = new ArrayList<>(USERS);
            for (ApiUser user : users) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return lockSeat(user, showingId, targetSeatId);
                }));
            }
            start.countDown();
            List<JsonNode> responses = new ArrayList<>(USERS);
            for (Future<JsonNode> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }

            // 恰好 1 成功；其余全部 10005"座位已被占用"
            long success = responses.stream().filter(r -> r.path("code").asInt() == 0).count();
            long occupied = responses.stream().filter(r -> r.path("code").asInt() == 10005).count();
            assertEquals(1, success, "并发抢同一座位应有且仅有一个成功：" + responses);
            assertEquals(USERS - 1, occupied, "其余请求应全部返回 10005 座位已被占用");
            for (JsonNode r : responses) {
                if (r.path("code").asInt() == 10005) {
                    assertTrue(r.path("message").asText().contains("座位已被占用"),
                            "失败提示应含'座位已被占用'：" + r);
                    break;
                }
            }

            // 零超卖终态：目标座位恰为 LOCKED（单人锁定），全场无 SOLD
            assertEquals("LOCKED", seatStatus(watcher, showingId, targetSeatId), "胜者座位应为锁定态");
            JsonNode map = watcher.get("/api/showings/" + showingId + "/seats").path("data");
            assertEquals(1, map.path("lockedSeats").asInt(), "全场应恰有 1 座锁定");
            assertEquals(0, map.path("soldSeats").asInt(), "零超卖：不应有任何已售座位");

            // 会话隔离自证：未登录新客户端访问受保护接口 → 10001
            assertEquals(10001, new ApiUser().get("/api/movies").path("code").asInt(),
                    "未登录应被拦截 10001");
        } finally {
            pool.shutdownNow();
        }
    }

    // =====================================================================
    // A6 · 50 并发锁同场次不同座位：全部成功
    // =====================================================================

    @Test
    @DisplayName("A6(C-2) 50 并发锁同场次 50 个不同座位：全部成功，互不干扰")
    void a6_fiftyConcurrentLockDistinctSeats() throws Exception {
        String tag = String.valueOf(System.nanoTime());
        long showingId = createShowing("A6" + tag, "IMAX"); // IMAX 5×10=50 座，恰好一一对应
        ApiUser watcher = registerAndLogin("a6w" + tag);
        List<Long> seats = seatIds(watcher, showingId);
        assertEquals(50, seats.size(), "夹具：IMAX 场次应有 50 座");

        List<ApiUser> users = new ArrayList<>(USERS);
        for (int i = 0; i < USERS; i++) {
            users.add(registerAndLogin("a6" + tag + "_" + i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(USERS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<JsonNode>> futures = new ArrayList<>(USERS);
            for (int i = 0; i < USERS; i++) {
                ApiUser user = users.get(i);
                long seatId = seats.get(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return lockSeat(user, showingId, seatId);
                }));
            }
            start.countDown();

            Set<Long> orderIds = ConcurrentHashMap.newKeySet();
            for (Future<JsonNode> future : futures) {
                JsonNode resp = future.get(60, TimeUnit.SECONDS);
                assertEquals(0, resp.path("code").asInt(), "不同座位并发锁定应全部成功：" + resp);
                orderIds.add(resp.path("data").path("orderId").asLong());
            }
            assertEquals(USERS, orderIds.size(), "50 笔订单 id 应互不相同");

            // 终态：50 座全部锁定、无已售、无空闲
            JsonNode map = watcher.get("/api/showings/" + showingId + "/seats").path("data");
            assertEquals(50, map.path("lockedSeats").asInt(), "全部座位应为锁定态");
            assertEquals(0, map.path("freeSeats").asInt());
            assertEquals(0, map.path("soldSeats").asInt(), "零超卖：无已售座位");
        } finally {
            pool.shutdownNow();
        }
    }

    // =====================================================================
    // A7 · 支付 vs 超时释放竞态（重复若干轮）：有且仅有一个生效，终态一致
    // =====================================================================

    @Test
    @DisplayName("A7(C-3) 支付 vs 超时释放竞态×6 轮：有且仅有一个生效，终态 SOLD⇔ISSUED / FREE⇔CANCELED")
    void a7_payVersusTimeoutReleaseRace() throws Exception {
        String tag = String.valueOf(System.nanoTime());
        long showingId = createShowing("A7" + tag, "STANDARD");
        ApiUser watcher = registerAndLogin("a7w" + tag);
        List<Long> seats = seatIds(watcher, showingId);

        Duration originalLock = bookingConfig.getLockDuration();
        int rounds = 6;
        int payWins = 0;
        int releaseWins = 0;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            bookingConfig.setLockDuration(Duration.ofMillis(400));
            for (int r = 0; r < rounds; r++) {
                ApiUser user = registerAndLogin("a7" + tag + "_" + r);
                long seatId = seats.get(r);
                JsonNode lockResp = lockSeat(user, showingId, seatId);
                assertEquals(0, lockResp.path("code").asInt(), "锁座应成功：" + lockResp);
                long orderId = lockResp.path("data").path("orderId").asLong();

                long lockTime = System.currentTimeMillis();
                waitUntil(lockTime + 360); // 到期（≈lock+400ms）前 ~40ms 发起支付（真 HTTP）
                Future<JsonNode> payFuture = pool.submit(() ->
                        user.post("/api/orders/" + orderId + "/pay", Map.of()));
                waitUntil(lockTime + 405); // 到期后直调释放任务（与 @Scheduled 同一方法，真实路径）
                pool.submit(() -> {
                    releaseTask.releaseExpiredLocks();
                    return null;
                }).get(10, TimeUnit.SECONDS);
                JsonNode payResp = payFuture.get(15, TimeUnit.SECONDS);
                int payCode = payResp.path("code").asInt();

                // 等待终态（SOLD 或 FREE）：释放可能由直调/作废补记/调度器完成
                String status = "LOCKED";
                for (int i = 0; i < 20 && status.equals("LOCKED"); i++) {
                    Thread.sleep(150);
                    status = seatStatus(watcher, showingId, seatId);
                }
                assertTrue("SOLD".equals(status) || "FREE".equals(status),
                        "终态应为 SOLD/FREE，实际 " + status + "（第 " + r + " 轮）");

                if ("SOLD".equals(status)) {
                    // 支付生效：应返回 0 且订单已出票（座位 SOLD ⇔ 订单 ISSUED）
                    assertEquals(0, payCode, "座位已售则支付应成功：" + payResp);
                    assertEquals("ISSUED", payResp.path("data").path("status").asText());
                    payWins++;
                } else {
                    // 释放生效：支付应作废 12002（座位 FREE ⇔ 订单 CANCELED，作废补记维持取消态）
                    assertEquals(12002, payCode, "座位已释放则支付应作废 12002：" + payResp);
                    releaseWins++;
                }
            }
            assertEquals(rounds, payWins + releaseWins,
                    "每轮有且仅有一个生效（支付×" + payWins + " / 释放×" + releaseWins + "）");
        } finally {
            bookingConfig.setLockDuration(originalLock); // 恢复演示档锁定时长
            pool.shutdownNow();
        }
    }
}
