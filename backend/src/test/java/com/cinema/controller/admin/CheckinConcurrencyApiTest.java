package com.cinema.controller.admin;

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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A8(C-4) · 重复核销并发 API 专项（测试计划 §4.2，幂等仅一次生效——task-04 挂账本卡落地）。
 *
 * <p>机制：TestRestTemplate + RANDOM_PORT 真 HTTP（非 MockMvc），与 A5~A7 同法；
 * N 个会话（同一检票员账号 checker 的 N 个独立 JSESSIONID 会话）对同一已出票订单
 * <b>并发</b>提交核销——核销迁移以条件更新原子化
 * {@code UPDATE ticket_order SET status='CHECKED_IN' WHERE id=? AND status='ISSUED'}：
 * 恰一次成功（code=0），其余按重读状态返回 13xxx（13008 已检票/13007 状态不符）拒绝，
 * 终态 CHECKED_IN、checkin_time 有且仅有一条记录（幂等）。
 *
 * <p>独立 profile api-test（独立 H2 内存库 cinema-api-test）：测试真实提交数据（注册观众/
 * 专用场次/已出票订单），与 I 系测试和种子断言隔离（机制照搬 A5~A7）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api-test")
@DisplayName("A8(C-4) · 重复核销并发（真 HTTP + 10 会话，幂等仅一次生效）")
class CheckinConcurrencyApiTest {

    /** 并发核销会话数。 */
    private static final int SESSIONS = 10;

    @LocalServerPort
    private int port;

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------------- HTTP 会话助手（照搬 AudienceBookingApiConcurrentTest） ----------------

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

    /** 单用户会话：独立 TestRestTemplate + 自管 JSESSIONID。 */
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
        assertEquals(0, user.post("/api/auth/register",
                Map.of("username", username, "password", "pass123456")).path("code").asInt());
        assertEquals(0, user.post("/api/auth/login",
                Map.of("username", username, "password", "pass123456")).path("code").asInt());
        return user;
    }

    /** 检票员会话（种子账号 checker，N 个独立 JSESSIONID）。 */
    private ApiUser checkerSession() {
        ApiUser checker = new ApiUser();
        assertEquals(0, checker.post("/api/auth/login",
                Map.of("username", "checker", "password", "checker123")).path("code").asInt());
        return checker;
    }

    /**
     * API 层夹具：排片员建专用场次（ORIGINAL basePrice=10，开映 +2h）→ 观众锁座 1 座 →
     * 支付出票。返回 Map：orderId / orderNo / pickupCode / showingId / audience。
     */
    private Map<String, Object> createIssuedOrder(String tag) {
        ApiUser scheduler = new ApiUser();
        assertEquals(0, scheduler.post("/api/auth/login",
                Map.of("username", "scheduler", "password", "scheduler123")).path("code").asInt());
        long movieId = scheduler.post("/api/admin/movies",
                Map.of("title", "核销并发影片" + tag, "durationMin", 90)).path("data").path("id").asLong();
        long hallId = scheduler.post("/api/admin/halls",
                Map.of("name", "核销并发厅" + tag, "hallType", "STANDARD")).path("data").path("id").asLong();
        String start = LocalDateTime.now().plusHours(2).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        JsonNode showingResp = scheduler.post("/api/admin/showings", Map.of(
                "movieId", movieId, "hallId", hallId, "startTime", start,
                "priceType", "ORIGINAL", "priceParams", Map.of("basePrice", 10.0)));
        assertEquals(0, showingResp.path("code").asInt(), "场次应创建成功：" + showingResp);
        long showingId = showingResp.path("data").path("id").asLong();

        ApiUser audience = registerAndLogin("a8u" + tag);
        JsonNode seats = audience.get("/api/showings/" + showingId + "/seats").path("data").path("seats");
        long seatId = seats.get(0).path("seatId").asLong();
        JsonNode lockResp = audience.post("/api/showings/" + showingId + "/seats/lock",
                Map.of("seatIds", List.of(seatId)));
        assertEquals(0, lockResp.path("code").asInt(), "锁座应成功：" + lockResp);
        long orderId = lockResp.path("data").path("orderId").asLong();
        JsonNode payResp = audience.post("/api/orders/" + orderId + "/pay", Map.of());
        assertEquals(0, payResp.path("code").asInt(), "支付应成功：" + payResp);
        assertEquals("ISSUED", payResp.path("data").path("status").asText());
        String pickupCode = payResp.path("data").path("pickupCode").asText();
        assertEquals(8, pickupCode.length(), "取票码应为 8 位");

        return Map.of("orderId", orderId, "orderNo", payResp.path("data").path("orderNo").asText(),
                "pickupCode", pickupCode, "showingId", showingId, "audience", audience);
    }

    // =====================================================================
    // A8 · 10 会话并发核销同一订单：恰一次成功，其余 13xxx 拒绝，幂等
    // =====================================================================

    @Test
    @DisplayName("A8(C-4) 10 会话并发核销：恰 1 成功，其余 13xxx，终态 CHECKED_IN 单条核销时间")
    void a8_concurrentCheckinExactlyOnce() throws Exception {
        String tag = String.valueOf(System.nanoTime());
        Map<String, Object> fixture = createIssuedOrder(tag);
        long orderId = (long) fixture.get("orderId");
        String orderNo = (String) fixture.get("orderNo");
        String pickupCode = (String) fixture.get("pickupCode");
        ApiUser audience = (ApiUser) fixture.get("audience");

        // N 个检票员会话（同一 checker 账号，独立 JSESSIONID）并发核销同一订单（凭订单号）
        List<ApiUser> checkers = new ArrayList<>(SESSIONS);
        for (int i = 0; i < SESSIONS; i++) {
            checkers.add(checkerSession());
        }
        ExecutorService pool = Executors.newFixedThreadPool(SESSIONS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<JsonNode>> futures = new ArrayList<>(SESSIONS);
            for (ApiUser checker : checkers) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return checker.post("/api/admin/checkin", Map.of("orderNo", orderNo));
                }));
            }
            start.countDown();

            List<JsonNode> responses = new ArrayList<>(SESSIONS);
            for (Future<JsonNode> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }

            // 恰一次成功；其余全部 13xxx 拒绝（13008 已检票 / 13007 状态不符——按并发时序自然分布）
            long success = responses.stream().filter(r -> r.path("code").asInt() == 0).count();
            long rejected13xxx = responses.stream()
                    .filter(r -> r.path("code").asInt() == 13007 || r.path("code").asInt() == 13008)
                    .count();
            assertEquals(1, success, "并发核销应有且仅有一次生效：" + responses);
            assertEquals(SESSIONS - 1, rejected13xxx,
                    "其余请求应全部以 13xxx 幂等拒绝：" + responses);
            JsonNode winner = responses.stream()
                    .filter(r -> r.path("code").asInt() == 0).findFirst().orElseThrow();
            assertEquals("CHECKED_IN", winner.path("data").path("status").asText());
            assertEquals("checker", winner.path("data").path("operator").asText(), "操作员应在响应返回");
            assertNotNull(winner.path("data").path("checkinTime").asText(), "核销时间应记录");

            // 终态幂等自证：观众订单列表中该订单为 CHECKED_IN（HTTP 只读核验）
            JsonNode myOrders = audience.get("/api/orders");
            assertEquals(0, myOrders.path("code").asInt(), "订单列表应可查");
            JsonNode target = null;
            for (JsonNode o : myOrders.path("data")) {
                if (o.path("orderId").asLong() == orderId) {
                    target = o;
                    break;
                }
            }
            assertNotNull(target, "订单列表应包含被核销订单");
            assertEquals("CHECKED_IN", target.path("status").asText(), "终态应为已检票");
            assertNotNull(target.path("checkinTime").asText(), "checkin_time 应落库");

            // 幂等收尾：并发结束后顺序补刀核销 → 仍 13008（仅一次生效不因时序漂移）
            assertEquals(13008, checkerSession().post("/api/admin/checkin", Map.of("orderNo", orderNo))
                    .path("code").asInt(), "事后重复核销应幂等拒绝 13008");
            assertEquals(13008, checkerSession().post("/api/admin/checkin", Map.of("pickupCode", pickupCode))
                    .path("code").asInt(), "凭取票码重复核销同样 13008");
        } finally {
            pool.shutdownNow();
        }
    }

    // =====================================================================
    // A8 补充 · 取票码检索路径 + 状态不符拒绝（顺序路径，单会话）
    // =====================================================================

    @Test
    @DisplayName("A8 补充：凭取票码核销成功；待支付订单核销 13007；角色不符 10002")
    void a8b_pickupCodePathAndStatusGuards() {
        String tag = String.valueOf(System.nanoTime());
        Map<String, Object> fixture = createIssuedOrder(tag);
        String pickupCode = (String) fixture.get("pickupCode");
        long orderId = (long) fixture.get("orderId");
        ApiUser audience = (ApiUser) fixture.get("audience");

        // 凭取票码核销成功（UC307 凭据二选一）
        ApiUser checker = checkerSession();
        JsonNode ok = checker.post("/api/admin/checkin", Map.of("pickupCode", pickupCode));
        assertEquals(0, ok.path("code").asInt(), "凭取票码核销应成功：" + ok);
        assertEquals("CHECKED_IN", ok.path("data").path("status").asText());

        // 状态不符（待支付订单）核销 → 13007：另造一笔仅锁座未支付的订单
        String tag2 = String.valueOf(System.nanoTime());
        ApiUser scheduler = new ApiUser();
        assertEquals(0, scheduler.post("/api/auth/login",
                Map.of("username", "scheduler", "password", "scheduler123")).path("code").asInt());
        long movieId = scheduler.post("/api/admin/movies",
                Map.of("title", "核销状态影片" + tag2, "durationMin", 90)).path("data").path("id").asLong();
        long hallId = scheduler.post("/api/admin/halls",
                Map.of("name", "核销状态厅" + tag2, "hallType", "STANDARD")).path("data").path("id").asLong();
        String start = LocalDateTime.now().plusHours(3).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        long showingId = scheduler.post("/api/admin/showings", Map.of(
                "movieId", movieId, "hallId", hallId, "startTime", start,
                "priceType", "ORIGINAL", "priceParams", Map.of("basePrice", 10.0)))
                .path("data").path("id").asLong();
        ApiUser other = registerAndLogin("a8b" + tag2);
        long seatId = other.get("/api/showings/" + showingId + "/seats")
                .path("data").path("seats").get(0).path("seatId").asLong();
        JsonNode lockResp = other.post("/api/showings/" + showingId + "/seats/lock",
                Map.of("seatIds", List.of(seatId)));
        long pendingOrderId = lockResp.path("data").path("orderId").asLong();
        assertEquals(13007, checker.post("/api/admin/checkin",
                        Map.of("orderNo", lockResp.path("data").path("orderNo").asText()))
                .path("code").asInt(), "待支付订单核销应拒绝 13007");

        // 角色不符：观众调用管理端核销 → 10002
        assertEquals(10002, audience.post("/api/admin/checkin",
                        Map.of("orderNo", "whatever")).path("code").asInt(),
                "观众调用核销应被角色拦截 10002");

        // 检索不到 → 12004
        assertEquals(12004, checker.post("/api/admin/checkin",
                        Map.of("pickupCode", "00000000")).path("code").asInt());

        // 凭据全缺 → 10003
        assertEquals(10003, checker.post("/api/admin/checkin", Map.of()).path("code").asInt());

        // 订单列表可见已检票终态
        JsonNode myOrders = audience.get("/api/orders");
        for (JsonNode o : myOrders.path("data")) {
            if (o.path("orderId").asLong() == orderId) {
                assertEquals("CHECKED_IN", o.path("status").asText());
                assertEquals(pickupCode, o.path("pickupCode").asText());
            }
        }
    }
}
