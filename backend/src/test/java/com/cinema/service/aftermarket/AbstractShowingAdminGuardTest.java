package com.cinema.service.aftermarket;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.controller.admin.dto.HallCreateRequest;
import com.cinema.controller.admin.dto.MovieCreateRequest;
import com.cinema.controller.admin.dto.ShowingCreateRequest;
import com.cinema.controller.admin.dto.ShowingResponse;
import com.cinema.model.Movie;
import com.cinema.model.enums.HallType;
import com.cinema.model.enums.MovieStatus;
import com.cinema.model.enums.PriceType;
import com.cinema.repository.MovieRepository;
import com.cinema.service.admin.ShowingAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I13/I14 · 场次创建/取消守卫集成测试用例主体（测试计划 §3 I13/I14，验证 task-02 行为，P1 模块账）。
 *
 * <p>抽象基类只承载用例，profile 由子类声明（机制照搬 I1~I12）：
 * <ul>
 *   <li>{@link ShowingAdminGuardIntegrationTest}：H2 内存库 test 档（默认 {@code mvn test} 即跑）</li>
 *   <li>{@link ShowingAdminGuardMySQLIntegrationTest}：容器 MySQL 8 mysql-test 档显式复跑</li>
 * </ul>
 *
 * <p>I13（UC103 扩展点 2a/3a/5a）：时段冲突拒绝 / 无策略默认套原价 / 影片已下架终止创建；
 * I14（UC104）：已售票场次取消禁止并提示。夹具全部随测试事务回滚，种子数据不受影响。
 */
@SpringBootTest
@Transactional
@DisplayName("I13/I14 · 场次创建与取消守卫（UC103/UC104，验证 task-02 行为），用例主体")
abstract class AbstractShowingAdminGuardTest {

    @Autowired
    protected com.cinema.service.admin.ShowingAdminService showingAdminService;
    @Autowired
    protected com.cinema.service.admin.MovieAdminService movieAdminService;
    @Autowired
    protected com.cinema.service.admin.HallAdminService hallAdminService;
    @Autowired
    protected MovieRepository movieRepository;
    @Autowired
    protected ObjectMapper objectMapper;

    /** 普通厅（48 座，模板由工厂生成）专用夹具名。 */
    protected long createHall(String tag) {
        return hallAdminService.create(new HallCreateRequest("守卫测试厅" + tag, HallType.STANDARD)).id();
    }

    /** 专用影片（90 分钟片长，时段冲突推算用）。 */
    protected long createMovie(String tag) {
        return movieAdminService.create(new MovieCreateRequest("守卫测试影片" + tag, "科幻", 90, null)).id();
    }

    // =====================================================================
    // I13 · 场次创建校验（UC103-2a 时段冲突 / 3a 默认原价 / 5a 影片已下架）
    // =====================================================================

    @Test
    @DisplayName("I13 场次创建校验：时段冲突拒绝 11002 / 无策略默认套原价 / 影片已下架终止创建 11007")
    void i13_showingCreateGuards() throws Exception {
        long hallId = createHall("I13" + System.nanoTime());
        long movieId = createMovie("I13a" + System.nanoTime());
        LocalDateTime start = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).plusDays(3);

        // 基线场次：占住 hallId 时段 [start, start+90min]
        ShowingResponse baseline = showingAdminService.create(new ShowingCreateRequest(
                movieId, hallId, start, PriceType.ORIGINAL,
                objectMapper.readTree("{\"basePrice\":10.00}"))).showing();
        assertEquals(PriceType.ORIGINAL, baseline.priceType());
        assertTrue(baseline.totalSeats() > 0, "基线场次应按模板生成座位");

        // UC103-2a：同影厅时段重叠（start+30min，落在基线区间内）→ 拒绝 11002
        BizException conflict = assertThrows(BizException.class,
                () -> showingAdminService.create(new ShowingCreateRequest(
                        movieId, hallId, start.plusMinutes(30), PriceType.ORIGINAL,
                        objectMapper.readTree("{\"basePrice\":10.00}"))));
        assertEquals(ErrorCode.HALL_TIME_CONFLICT.getCode(), conflict.getCode(), "时段冲突应拒绝 11002");

        // UC103-3a：请求未携带 priceType → 默认套用全场原价（strategyDefaulted=true）
        ShowingAdminService.CreateResult defaulted = showingAdminService.create(new ShowingCreateRequest(
                movieId, hallId, start.plusDays(1), null,
                objectMapper.readTree("{\"basePrice\":12.00}")));
        assertTrue(defaulted.strategyDefaulted(), "未配置策略应命中 UC103-3a 默认档");
        assertEquals(PriceType.ORIGINAL, defaulted.showing().priceType(), "默认策略应为全场原价");

        // UC103-5a：影片已下架 → 终止创建
        Movie offShow = movieRepository.findById(movieId).orElseThrow();
        offShow.setStatus(MovieStatus.OFF_SHOW);
        movieRepository.saveAndFlush(offShow);
        BizException offShowRejected = assertThrows(BizException.class,
                () -> showingAdminService.create(new ShowingCreateRequest(
                        movieId, hallId, start.plusDays(2), null,
                        objectMapper.readTree("{\"basePrice\":12.00}"))));
        assertEquals(ErrorCode.MOVIE_OFF_SHOW.getCode(), offShowRejected.getCode(), "影片已下架应拒绝 11007");
    }

    // =====================================================================
    // I14 · 已售票场次取消（UC104）：禁止并提示
    // =====================================================================

    @Test
    @DisplayName("I14 已售票场次取消：禁止 11004；无票场次可取消（对照）")
    void i14_cancelSoldShowingForbidden() throws Exception {
        // 对照：全部座位空闲的专用场次可正常取消（UC104 基本路径）
        long movieId = createMovie("I14ok" + System.nanoTime());
        long freeHallId = createHall("I14free" + System.nanoTime());
        ShowingResponse freeShowing = showingAdminService.create(new ShowingCreateRequest(
                movieId, freeHallId, LocalDateTime.now().plusDays(3), null,
                objectMapper.readTree("{\"basePrice\":10.00}"))).showing();
        ShowingResponse canceled = showingAdminService.cancel(freeShowing.id());
        assertEquals("CANCELED", canceled.status().name(), "无票场次应可取消");

        // I14：已售票场次（种子满座场，50 座全 SOLD）取消 → 禁止并提示
        ShowingResponse soldShowing = showingAdminService.list(null, null, null).stream()
                .filter(s -> s.soldSeats() > 0 && s.soldSeats().equals(s.totalSeats()))
                .findFirst().orElseThrow();
        BizException forbidden = assertThrows(BizException.class, () -> showingAdminService.cancel(soldShowing.id()));
        assertEquals(ErrorCode.SHOWING_CANCEL_FORBIDDEN.getCode(), forbidden.getCode(), "已售票场次取消应拒绝 11004");
        assertTrue(forbidden.getMessage().contains("禁止取消") || forbidden.getMessage().contains("已售"),
                "提示应说明禁止原因：" + forbidden.getMessage());
        // 状态不变
        ShowingResponse after = showingAdminService.list(null, null, null).stream()
                .filter(s -> s.id().equals(soldShowing.id())).findFirst().orElseThrow();
        assertEquals("PUBLISHED", after.status().name(), "被拒绝的取消不得改变场次状态");
    }
}
