package com.cinema.controller;

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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.ResponseErrorHandler;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A1~A4 · API 正反例护栏套件（测试计划 §4.1，task-08 P2 清账交付）。
 *
 * <p>真 HTTP（TestRestTemplate + RANDOM_PORT，与 A5~A8 同法），四类断言口径：
 * <ol>
 *   <li><b>A1 未登录</b>：/api/** 除 /api/auth/** 一律经 AuthInterceptor 登录校验 →
 *       HTTP 401 + code 10001（NOT_LOGGED_IN）；</li>
 *   <li><b>A2 角色不符</b>：观众调管理端 7 接口 / scheduler 调核销 / checker 调排片 →
 *       HTTP 403 + code 10002（NO_PERMISSION），拦截器在 handler 前拒绝、无数据落库；</li>
 *   <li><b>A3 参数非法</b>：选座超 maxSeatsPerOrder(4) / 非法场次与订单 id / 非法策略参数 /
 *       空体缺字段 → HTTP 400 或 404 + 对应业务码（10003/10004/11003/11005/12004）；</li>
 *   <li><b>A4 状态不符写操作</b>：待支付直接核销 13007、待支付退票/改签 13001/13010、
 *       已取消订单支付按 UC205-4a 作废 12002、已出票/已检票重复支付 12003、已检票退票 13001
 *       ——业务码拒绝且<b>复查订单状态前后一致</b>（状态机守卫不产生迁移）。</li>
 * </ol>
 *
 * <p>错误码口径见 {@code common/ErrorCode.java} 与《字段表-A/B/C模块.md》错误码总表：
 * 每个业务码自带 HTTP 状态映射（10001→401 / 10002→403 / 10003→400 / 10004·11005·12004→404 /
 * 其余 4xx 冲突类→409），断言同时校验 HTTP 状态码与业务 code。
 *
 * <p>独立 profile api-test（独立 H2 内存库 cinema-api-test，DataSeeder 播种），与 I 系
 * 测试和种子断言隔离（机制照搬 A5~A8）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api-test")
@DisplayName("A1~A4 · API 正反例护栏（未登录/角色/参数/状态四类，真 HTTP）")
class ApiGuardrailsTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper mapper;

    // ---------------- HTTP 会话助手（照搬 A5~A8 风格） ----------------

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
            // A1 用例专打 401 场景。Spring Framework 6.1 的 SimpleClientHttpRequestFactory
            // 对任何带输出（POST/PUT）的请求一律走 JDK HttpURLConnection 流式发送；
            // 而 JDK HttpURLConnection 在"流式模式下收到 401"时无条件抛
            // HttpRetryException（认证重试机制，见 JDK 源码 getInputStream0）。
            // 故改用 JdkClientHttpRequestFactory（java.net.http.HttpClient 实现，
            // 无该遗留重试行为），保证 401 响应体可正常读出断言。
            client.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        }

        JsonNode get(String path) {
            return exchange(HttpMethod.GET, path, null);
        }

        JsonNode post(String path, Object body) {
            return exchange(HttpMethod.POST, path, body);
        }

        JsonNode put(String path, Object body) {
            return exchange(HttpMethod.PUT, path, body);
        }

        /** 携带期望 HTTP 状态的调用（A1~A4 需同时断言 HTTP 码与业务 code）。 */
        ResponseEntity<String> exchangeRaw(HttpMethod method, String path, Object body) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (jsessionId != null) {
                headers.set(HttpHeaders.COOKIE, "JSESSIONID=" + jsessionId);
            }
            return client.exchange("http://localhost:" + port + path, method,
                    new HttpEntity<>(body, headers), String.class);
        }

        private JsonNode exchange(HttpMethod method, String path, Object body) {
            try {
                ResponseEntity<String> resp = exchangeRaw(method, path, body);
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

    /** 断言：HTTP 状态码 + 统一外壳业务 code 双口径（A1~A4 统一判据）。 */
    private void assertHttpAndCode(ResponseEntity<String> resp, int expectedHttpStatus,
                                   int expectedCode, String label) throws Exception {
        assertEquals(expectedHttpStatus, resp.getStatusCode().value(),
                label + " HTTP 状态不符：" + resp.getBody());
        JsonNode body = mapper.readTree(resp.getBody() == null ? "{}" : resp.getBody());
        assertEquals(expectedCode, body.path("code").asInt(),
                label + " 业务码不符：" + resp.getBody());
    }

    /** 注册 + 登录一名观众（会话隔离，用户名带 nanoTime 防重名）。 */
    private ApiUser registerAndLogin(String tag) {
        ApiUser user = new ApiUser();
        String username = "a1234" + tag;
        assertEquals(0, user.post("/api/auth/register",
                Map.of("username", username, "password", "pass123456")).path("code").asInt());
        assertEquals(0, user.post("/api/auth/login",
                Map.of("username", username, "password", "pass123456")).path("code").asInt());
        return user;
    }

    private ApiUser scheduler() {
        ApiUser scheduler = new ApiUser();
        assertEquals(0, scheduler.post("/api/auth/login",
                Map.of("username", "scheduler", "password", "scheduler123")).path("code").asInt());
        return scheduler;
    }

    private ApiUser checker() {
        ApiUser checker = new ApiUser();
        assertEquals(0, checker.post("/api/auth/login",
                Map.of("username", "checker", "password", "checker123")).path("code").asInt());
        return checker;
    }

    /** 排片员建专用场次（ORIGINAL basePrice=10，开映 +hoursAhead 小时），返回 showingId。 */
    private long createShowing(ApiUser schedulerUser, String tag, long hoursAhead) {
        long movieId = schedulerUser.post("/api/admin/movies",
                Map.of("title", "护栏影片" + tag, "durationMin", 90))
                .path("data").path("id").asLong();
        long hallId = schedulerUser.post("/api/admin/halls",
                Map.of("name", "护栏厅" + tag, "hallType", "STANDARD"))
                .path("data").path("id").asLong();
        String start = LocalDateTime.now().plusHours(hoursAhead).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        JsonNode resp = schedulerUser.post("/api/admin/showings", Map.of(
                "movieId", movieId, "hallId", hallId, "startTime", start,
                "priceType", "ORIGINAL", "priceParams", Map.of("basePrice", 10.0)));
        assertEquals(0, resp.path("code").asInt(), "场次应创建成功：" + resp);
        return resp.path("data").path("id").asLong();
    }

    /** 观众锁单场第一个座位，返回 orderId（PENDING_PAYMENT）。 */
    private long lockFirstSeat(ApiUser audience, long showingId) {
        JsonNode seats = audience.get("/api/showings/" + showingId + "/seats")
                .path("data").path("seats");
        assertTrue(seats.isArray() && seats.size() > 0, "场次应有座位");
        JsonNode lockResp = audience.post("/api/showings/" + showingId + "/seats/lock",
                Map.of("seatIds", List.of(seats.get(0).path("seatId").asLong())));
        assertEquals(0, lockResp.path("code").asInt(), "锁座应成功：" + lockResp);
        return lockResp.path("data").path("orderId").asLong();
    }

    /** 支付出票（断言 ISSUED），返回订单号。 */
    private String payUntilIssued(ApiUser audience, long orderId) {
        JsonNode payResp = audience.post("/api/orders/" + orderId + "/pay", Map.of());
        assertEquals(0, payResp.path("code").asInt(), "支付应成功：" + payResp);
        assertEquals("ISSUED", payResp.path("data").path("status").asText());
        return payResp.path("data").path("orderNo").asText();
    }

    /** 检票核销（checker 凭订单号），断言成功。 */
    private void checkinByOrderNo(String orderNo) {
        JsonNode resp = checker().post("/api/admin/checkin", Map.of("orderNo", orderNo));
        assertEquals(0, resp.path("code").asInt(), "核销应成功：" + resp);
    }

    /** 复查订单当前状态（GET /api/orders 按 orderId 查找——A4"状态不变"口径）。 */
    private String orderStatusOf(ApiUser audience, long orderId) {
        JsonNode orders = audience.get("/api/orders").path("data");
        assertTrue(orders.isArray(), "订单列表应可查");
        for (JsonNode order : orders) {
            if (order.path("orderId").asLong() == orderId) {
                return order.path("status").asText();
            }
        }
        return fail("订单列表应包含订单 " + orderId + "，实际未找到：" + orders);
    }

    // =====================================================================
    // A1 · 未登录访问受保护接口 → HTTP 401 + 10001
    // =====================================================================

    @Test
    @DisplayName("A1 未登录访问 lock/pay/orders/refund/change/queue/claim/notifications/admin 系 → 401+10001")
    void a1_anonymousGets401WithCode10001OnEveryProtectedEndpoint() throws Exception {
        // 无会话的裸客户端（不登录、无 Cookie）
        ApiUser anonymous = new ApiUser();

        // 受保护接口全量抽查：观众端写/查 + 公共轮询 + 管理端 8 接口（AuthInterceptor 拦 /api/** 除 /api/auth/**）
        record Probe(HttpMethod method, String path, Object body, String label) {
        }
        List<Probe> probes = List.of(
                new Probe(HttpMethod.POST, "/api/showings/1/seats/lock",
                        Map.of("seatIds", List.of(1)), "锁座"),
                new Probe(HttpMethod.POST, "/api/orders/1/pay", Map.of(), "支付"),
                new Probe(HttpMethod.GET, "/api/orders", null, "我的订单"),
                new Probe(HttpMethod.POST, "/api/orders/1/refund", Map.of(), "退票"),
                new Probe(HttpMethod.POST, "/api/orders/1/change",
                        Map.of("targetShowingId", 1), "改签"),
                new Probe(HttpMethod.POST, "/api/showings/1/queue", Map.of(), "候补加入"),
                new Probe(HttpMethod.POST, "/api/queue/1/claim", Map.of(), "候补确认"),
                new Probe(HttpMethod.GET, "/api/notifications", null, "通知中心"),
                new Probe(HttpMethod.GET, "/api/movies", null, "影片列表"),
                new Probe(HttpMethod.GET, "/api/showings/1/seats", null, "座位图轮询"),
                new Probe(HttpMethod.POST, "/api/admin/movies",
                        Map.of("title", "未登录影片", "durationMin", 90), "管理端-新增影片"),
                new Probe(HttpMethod.PUT, "/api/admin/movies/999999",
                        Map.of("title", "未登录改片"), "管理端-编辑影片"),
                new Probe(HttpMethod.POST, "/api/admin/halls",
                        Map.of("name", "未登录厅", "hallType", "STANDARD"), "管理端-创建影厅"),
                new Probe(HttpMethod.POST, "/api/admin/showings",
                        Map.of("movieId", 1, "hallId", 1, "startTime",
                                "2026-12-01T10:00:00"), "管理端-创建场次"),
                new Probe(HttpMethod.PUT, "/api/admin/showings/999999/cancel", Map.of(), "管理端-取消场次"),
                new Probe(HttpMethod.PUT, "/api/admin/showings/999999/strategy",
                        Map.of("priceType", "ORIGINAL", "priceParams",
                                Map.of("basePrice", 10.0)), "管理端-配置策略"),
                new Probe(HttpMethod.GET, "/api/admin/showings", null, "管理端-排片列表"),
                new Probe(HttpMethod.POST, "/api/admin/checkin",
                        Map.of("orderNo", "B00000000000000000"), "管理端-检票核销"));

        for (Probe probe : probes) {
            ResponseEntity<String> resp = anonymous.exchangeRaw(
                    probe.method(), probe.path(), probe.body());
            assertHttpAndCode(resp, 401, 10001, "A1 未登录·" + probe.label());
        }

        // 对照样例：同一接口登录后可过鉴权（证明 401 来自登录校验而非接口本身不可用）
        ApiUser audience = registerAndLogin("a1ok");
        assertEquals(0, audience.get("/api/movies").path("code").asInt(), "登录后 /api/movies 应可访问");
        assertEquals(0, audience.get("/api/orders").path("code").asInt(), "登录后 /api/orders 应可访问");
    }

    // =====================================================================
    // A2 · 角色不符 → HTTP 403 + 10002（拦截器 handler 前拒绝，无数据落库）
    // =====================================================================

    @Test
    @DisplayName("A2 观众调用管理端 7 接口全部 403+10002，且影片/排片数据无增量")
    void a2_audienceBlockedFromAllAdminEndpoints() throws Exception {
        ApiUser audience = registerAndLogin("a2aud");
        ApiUser schedulerUser = scheduler();

        int moviesBefore = audience.get("/api/movies").path("data").size();
        int showingsBefore = schedulerUser.get("/api/admin/showings").path("data").size();

        // 管理端 7 接口逐一（类级 @RequireRole(SCHEDULER)）
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.POST, "/api/admin/movies",
                Map.of("title", "越权影片", "durationMin", 90)), 403, 10002, "A2 观众·新增影片");
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.PUT, "/api/admin/movies/999999",
                Map.of("title", "越权改片")), 403, 10002, "A2 观众·编辑影片");
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.POST, "/api/admin/halls",
                Map.of("name", "越权厅", "hallType", "STANDARD")), 403, 10002, "A2 观众·创建影厅");
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.POST, "/api/admin/showings",
                Map.of("movieId", 1, "hallId", 1, "startTime", "2026-12-01T10:00:00")),
                403, 10002, "A2 观众·创建场次");
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.PUT,
                "/api/admin/showings/999999/cancel", Map.of()), 403, 10002, "A2 观众·取消场次");
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.PUT,
                "/api/admin/showings/999999/strategy",
                Map.of("priceType", "ORIGINAL", "priceParams", Map.of("basePrice", 10.0))),
                403, 10002, "A2 观众·配置策略");
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.GET, "/api/admin/showings", null),
                403, 10002, "A2 观众·排片列表");

        // 无数据落库：影片数/排片数不变（拦截器在 handler 之前拒绝，@Valid 校验亦未触达）
        assertEquals(moviesBefore, audience.get("/api/movies").path("data").size(),
                "越权新增影片不得落库");
        assertEquals(showingsBefore, schedulerUser.get("/api/admin/showings").path("data").size(),
                "越权创建场次不得落库");
    }

    @Test
    @DisplayName("A2 scheduler 调核销 403、checker 调排片 403、scheduler 调观众端订单 403")
    void a2_crossRoleBlocksBetweenSchedulerCheckerAndAudience() throws Exception {
        ApiUser schedulerUser = scheduler();
        ApiUser checkerUser = checker();

        // scheduler（SCHEDULER）调核销（要求 CHECKER）→ 403+10002
        assertHttpAndCode(schedulerUser.exchangeRaw(HttpMethod.POST, "/api/admin/checkin",
                Map.of("orderNo", "B00000000000000000")), 403, 10002, "A2 scheduler·核销");

        // checker（CHECKER）调排片系（要求 SCHEDULER）→ 403+10002
        assertHttpAndCode(checkerUser.exchangeRaw(HttpMethod.GET, "/api/admin/showings", null),
                403, 10002, "A2 checker·排片列表");
        assertHttpAndCode(checkerUser.exchangeRaw(HttpMethod.POST, "/api/admin/showings",
                Map.of("movieId", 1, "hallId", 1, "startTime", "2026-12-01T10:00:00")),
                403, 10002, "A2 checker·创建场次");
        assertHttpAndCode(checkerUser.exchangeRaw(HttpMethod.POST, "/api/admin/movies",
                Map.of("title", "越权影片x", "durationMin", 90)), 403, 10002, "A2 checker·新增影片");

        // scheduler 调观众端角色限定接口（@RequireRole(AUDIENCE)）→ 403+10002
        assertHttpAndCode(schedulerUser.exchangeRaw(HttpMethod.GET, "/api/orders", null),
                403, 10002, "A2 scheduler·我的订单");
    }

    // =====================================================================
    // A3 · 参数非法 → 400/404 + 对应业务码
    // =====================================================================

    @Test
    @DisplayName("A3 选座超过单笔上限 4 座 → 400+10003（UC204-3 / maxSeatsPerOrder）")
    void a3_lockMoreThanMaxSeatsPerOrderRejected() throws Exception {
        ApiUser schedulerUser = scheduler();
        String tag = String.valueOf(System.nanoTime());
        long showingId = createShowing(schedulerUser, tag, 2);

        ApiUser audience = registerAndLogin("a3max");
        JsonNode seats = audience.get("/api/showings/" + showingId + "/seats")
                .path("data").path("seats");
        assertTrue(seats.size() >= 5, "普通厅 48 座应够取 5 座");

        List<Long> fiveSeats = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            fiveSeats.add(seats.get(i).path("seatId").asLong());
        }
        ResponseEntity<String> resp = audience.exchangeRaw(HttpMethod.POST,
                "/api/showings/" + showingId + "/seats/lock", Map.of("seatIds", fiveSeats));
        assertHttpAndCode(resp, 400, 10003, "A3 选座 5 座超限");
        assertTrue(resp.getBody().contains("单笔最多选 4 座"), "应提示上限：" + resp.getBody());

        // 状态不变证据：上限拒绝后座位图无锁定增量（未误锁任何座位）
        JsonNode seatMap = audience.get("/api/showings/" + showingId + "/seats").path("data");
        assertEquals(0, seatMap.path("lockedSeats").asInt(), "超限锁座不得锁定任何座位");
    }

    @Test
    @DisplayName("A3 非法场次/订单 id → 404+11005（座位图）/ 404+10004（锁座）/ 404+12004（支付/退票）")
    void a3_illegalShowingAndOrderIdsRejected() throws Exception {
        ApiUser audience = registerAndLogin("a3ids");

        // 座位图：场次不存在 → 11005（A 模块段）
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.GET, "/api/showings/999999/seats", null),
                404, 11005, "A3 座位图·非法场次 id");

        // 锁座：场次不存在 → 10004（B 模块段，SeatLockService 口径）
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.POST, "/api/showings/999999/seats/lock",
                        Map.of("seatIds", List.of(1))), 404, 10004, "A3 锁座·非法场次 id");

        // 支付/退票：订单不存在 → 12004
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.POST, "/api/orders/999999/pay", Map.of()),
                404, 12004, "A3 支付·非法订单 id");
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.POST, "/api/orders/999999/refund", Map.of()),
                404, 12004, "A3 退票·非法订单 id");
    }

    @Test
    @DisplayName("A3 非法策略参数（折扣∉(0,1)/分区价≤0/basePrice 缺失或≤0）→ 400+11003")
    void a3_invalidStrategyParamsRejected11003() throws Exception {
        ApiUser schedulerUser = scheduler();
        String tag = String.valueOf(System.nanoTime());
        long showingId = createShowing(schedulerUser, tag, 3);

        // 会员折扣：discount=1.5 ∉ (0,1) → 11003
        assertHttpAndCode(schedulerUser.exchangeRaw(HttpMethod.PUT,
                "/api/admin/showings/" + showingId + "/strategy",
                Map.of("priceType", "MEMBER_DISCOUNT", "priceParams", Map.of("discount", 1.5))),
                400, 11003, "A3 会员折扣 1.5");
        // 会员折扣：discount=0 → 11003
        assertHttpAndCode(schedulerUser.exchangeRaw(HttpMethod.PUT,
                "/api/admin/showings/" + showingId + "/strategy",
                Map.of("priceType", "MEMBER_DISCOUNT", "priceParams", Map.of("discount", 0))),
                400, 11003, "A3 会员折扣 0");
        // 分区计价：normalPrice=-1 ≤ 0 → 11003
        assertHttpAndCode(schedulerUser.exchangeRaw(HttpMethod.PUT,
                "/api/admin/showings/" + showingId + "/strategy",
                Map.of("priceType", "ZONE_PRICING",
                        "priceParams", Map.of("goldenPrice", 20.0, "normalPrice", -1))),
                400, 11003, "A3 分区价负数");
        // 原价：basePrice 缺失 → 11003
        assertHttpAndCode(schedulerUser.exchangeRaw(HttpMethod.PUT,
                "/api/admin/showings/" + showingId + "/strategy",
                Map.of("priceType", "ORIGINAL", "priceParams", Map.of())),
                400, 11003, "A3 basePrice 缺失");
        // 原价：basePrice=0 → 11003（无价场次不可售）
        assertHttpAndCode(schedulerUser.exchangeRaw(HttpMethod.PUT,
                "/api/admin/showings/" + showingId + "/strategy",
                Map.of("priceType", "ORIGINAL", "priceParams", Map.of("basePrice", 0))),
                400, 11003, "A3 basePrice=0");

        // 创建场次携带非法策略参数同样拒绝（UC103-3 参数前置校验）
        ResponseEntity<String> createResp = schedulerUser.exchangeRaw(HttpMethod.POST,
                "/api/admin/showings", Map.of(
                        "movieId", schedulerUser.post("/api/admin/movies",
                                Map.of("title", "策略参数影片" + tag, "durationMin", 90))
                                .path("data").path("id").asLong(),
                        "hallId", schedulerUser.post("/api/admin/halls",
                                Map.of("name", "策略参数厅" + tag, "hallType", "STANDARD"))
                                .path("data").path("id").asLong(),
                        "startTime", LocalDateTime.now().plusHours(3)
                                .truncatedTo(ChronoUnit.SECONDS)
                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        "priceType", "MEMBER_DISCOUNT", "priceParams", Map.of("discount", 2.5)));
        assertHttpAndCode(createResp, 400, 11003, "A3 创建场次·非法策略参数");
    }

    @Test
    @DisplayName("A3 空体/缺字段（锁座空 seatIds、影片缺片名、场次缺开映时间）→ 400+10003")
    void a3_missingOrEmptyBodyRejected10003() throws Exception {
        ApiUser schedulerUser = scheduler();
        String tag = String.valueOf(System.nanoTime());
        long showingId = createShowing(schedulerUser, tag, 2);
        ApiUser audience = registerAndLogin("a3empty");

        // 锁座：空对象（@NotEmpty seatIds → BindException → INVALID_PARAM 10003）
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.POST,
                "/api/showings/" + showingId + "/seats/lock", Map.of()), 400, 10003, "A3 锁座空体");
        // 锁座：seatIds 含 null 元素（Arrays.asList 允许 null，Jackson 绑定后校验拒绝）
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.POST,
                "/api/showings/" + showingId + "/seats/lock",
                Map.of("seatIds", Arrays.asList((Long) null))),
                400, 10003, "A3 锁座 seatIds null 元素");
        // 锁座：空数组
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.POST,
                "/api/showings/" + showingId + "/seats/lock", Map.of("seatIds", List.of())),
                400, 10003, "A3 锁座空数组");

        // 新增影片：缺片名（@NotBlank title）
        assertHttpAndCode(schedulerUser.exchangeRaw(HttpMethod.POST, "/api/admin/movies", Map.of()),
                400, 10003, "A3 影片缺片名");
        // 创建场次：缺开映时间（@NotNull startTime）
        assertHttpAndCode(schedulerUser.exchangeRaw(HttpMethod.POST, "/api/admin/showings",
                Map.of("movieId", 1, "hallId", 1)), 400, 10003, "A3 场次缺开映时间");
        // 创建影厅：缺影厅类型（@NotNull hallType）
        assertHttpAndCode(schedulerUser.exchangeRaw(HttpMethod.POST, "/api/admin/halls",
                Map.of("name", "缺类型厅")), 400, 10003, "A3 影厅缺类型");
        // 策略配置：缺 priceType（@NotNull）
        assertHttpAndCode(schedulerUser.exchangeRaw(HttpMethod.PUT,
                "/api/admin/showings/" + showingId + "/strategy",
                Map.of("priceParams", Map.of("basePrice", 10.0))), 400, 10003, "A3 策略缺 priceType");
    }

    // =====================================================================
    // A4 · 状态不符写操作 → 业务码拒绝 + 订单状态前后一致
    // =====================================================================

    @Test
    @DisplayName("A4 待支付订单直接核销 → 409+13007，订单保持 PENDING_PAYMENT")
    void a4_checkinPendingOrderRejected13007AndStateUnchanged() throws Exception {
        ApiUser schedulerUser = scheduler();
        String tag = String.valueOf(System.nanoTime());
        long showingId = createShowing(schedulerUser, tag, 2);

        ApiUser audience = registerAndLogin("a4chk");
        long orderId = lockFirstSeat(audience, showingId);
        assertEquals("PENDING_PAYMENT", orderStatusOf(audience, orderId), "前置：待支付");

        String foundOrderNo = null;
        JsonNode orderList = audience.get("/api/orders").path("data");
        for (JsonNode order : orderList) {
            if (order.path("orderId").asLong() == orderId) {
                foundOrderNo = order.path("orderNo").asText();
            }
        }
        assertNotNull(foundOrderNo, "订单号应在列表返回");

        // 待支付直接核销 → 13007
        assertHttpAndCode(checker().exchangeRaw(HttpMethod.POST, "/api/admin/checkin",
                Map.of("orderNo", foundOrderNo)), 409, 13007, "A4 待支付核销");

        // 状态不变
        assertEquals("PENDING_PAYMENT", orderStatusOf(audience, orderId),
                "A4 状态守卫：核销被拒后订单应保持待支付");
    }

    @Test
    @DisplayName("A4 待支付订单退票 13001 / 改签 13010 被拒，状态保持 PENDING_PAYMENT")
    void a4_refundAndChangeOnPendingOrderRejectedAndStateUnchanged() throws Exception {
        ApiUser schedulerUser = scheduler();
        String tag = String.valueOf(System.nanoTime());
        long movieShow1 = createShowing(schedulerUser, tag + "s1", 3);
        long movieShow2 = createShowing(schedulerUser, tag + "s2", 5);

        ApiUser audience = registerAndLogin("a4pnd");
        long orderId = lockFirstSeat(audience, movieShow1);
        assertEquals("PENDING_PAYMENT", orderStatusOf(audience, orderId), "前置：待支付");

        // 待支付退票 → 13001（仅"已出票且未检票"可退）
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.POST, "/api/orders/" + orderId + "/refund",
                Map.of()), 409, 13001, "A4 待支付退票");
        assertEquals("PENDING_PAYMENT", orderStatusOf(audience, orderId),
                "A4 状态守卫：退票被拒后订单应保持待支付");

        // 待支付改签 → 13010（仅"已出票"可改签）
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.POST, "/api/orders/" + orderId + "/change",
                Map.of("targetShowingId", movieShow2)), 409, 13010, "A4 待支付改签");
        assertEquals("PENDING_PAYMENT", orderStatusOf(audience, orderId),
                "A4 状态守卫：改签被拒后订单应保持待支付");
    }

    @Test
    @DisplayName("A4 已取消订单支付 → 409+12002 作废（UC205-4a）；已出票重复支付 → 409+12003；状态均不变")
    void a4_payCanceledAndIssuedOrdersRejectedAndStateUnchanged() throws Exception {
        ApiUser schedulerUser = scheduler();
        String tag = String.valueOf(System.nanoTime());
        long showingForCancel = createShowing(schedulerUser, tag + "x", 2);
        long showingForIssued = createShowing(schedulerUser, tag + "i", 2);

        // ---- 场景1：CANCELED 订单支付 → 12002 作废 ----
        // 造 CANCELED 订单：同场次二次锁座触发"重选放弃"（UC204 主动放弃语义）取消首单。
        // 注意：作废补记（finalizeVoid）会释放本人在该场次全部残留锁定（UC205-4a"不得悬挂锁定"），
        // 因此本用户后续不得再在该场次发起支付相关操作——ISSUED 重复支付场景另起用户+场次。
        ApiUser cancelUser = registerAndLogin("a4cxl");
        long canceledOrderId = lockFirstSeat(cancelUser, showingForCancel);
        lockFirstSeat(cancelUser, showingForCancel);
        assertEquals("CANCELED", orderStatusOf(cancelUser, canceledOrderId),
                "前置：二次锁座后首单应已自动取消");

        // CANCELED 订单支付 → 12002 支付作废（网关后置守卫，模拟退款）
        assertHttpAndCode(cancelUser.exchangeRaw(HttpMethod.POST,
                "/api/orders/" + canceledOrderId + "/pay", Map.of()), 409, 12002, "A4 已取消订单支付");
        assertEquals("CANCELED", orderStatusOf(cancelUser, canceledOrderId),
                "A4 状态守卫：作废支付后订单应保持已取消");

        // ---- 场景2：ISSUED 订单重复支付 → 12003 ORDER_NOT_PAYABLE ----
        ApiUser issuedUser = registerAndLogin("a4iss");
        long issuedOrderId = lockFirstSeat(issuedUser, showingForIssued);
        payUntilIssued(issuedUser, issuedOrderId);
        assertHttpAndCode(issuedUser.exchangeRaw(HttpMethod.POST,
                "/api/orders/" + issuedOrderId + "/pay", Map.of()), 409, 12003, "A4 已出票重复支付");
        assertEquals("ISSUED", orderStatusOf(issuedUser, issuedOrderId),
                "A4 状态守卫：重复支付被拒后订单应保持已出票");
    }

    @Test
    @DisplayName("A4 已检票订单退票 13001 / 改签 13010 / 再支付 12003 全拒，状态保持 CHECKED_IN")
    void a4_terminalCheckedInOrderWritesAllRejectedAndStateUnchanged() throws Exception {
        ApiUser schedulerUser = scheduler();
        String tag = String.valueOf(System.nanoTime());
        long showing1 = createShowing(schedulerUser, tag + "c1", 3);
        long showing2 = createShowing(schedulerUser, tag + "c2", 5);

        ApiUser audience = registerAndLogin("a4chk2");
        long orderId = lockFirstSeat(audience, showing1);
        String orderNo = payUntilIssued(audience, orderId);
        checkinByOrderNo(orderNo);
        assertEquals("CHECKED_IN", orderStatusOf(audience, orderId), "前置：已检票");

        // 已检票退票 → 13001（UC302-4a：已检票为终态）
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.POST, "/api/orders/" + orderId + "/refund",
                Map.of()), 409, 13001, "A4 已检票退票");
        assertEquals("CHECKED_IN", orderStatusOf(audience, orderId),
                "A4 状态守卫：退票被拒后订单应保持已检票");

        // 已检票改签 → 13010
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.POST, "/api/orders/" + orderId + "/change",
                Map.of("targetShowingId", showing2)), 409, 13010, "A4 已检票改签");
        assertEquals("CHECKED_IN", orderStatusOf(audience, orderId),
                "A4 状态守卫：改签被拒后订单应保持已检票");

        // 已检票再支付 → 12003
        assertHttpAndCode(audience.exchangeRaw(HttpMethod.POST, "/api/orders/" + orderId + "/pay",
                Map.of()), 409, 12003, "A4 已检票再支付");
        assertEquals("CHECKED_IN", orderStatusOf(audience, orderId),
                "A4 状态守卫：再支付被拒后订单应保持已检票");
    }
}
