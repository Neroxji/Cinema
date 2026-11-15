package com.cinema.service.admin;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.common.factory.ImaxSeatFactory;
import com.cinema.common.factory.SeatMatrixFactory;
import com.cinema.common.factory.StandardSeatFactory;
import com.cinema.controller.admin.dto.ShowingCreateRequest;
import com.cinema.controller.admin.dto.ShowingResponse;
import com.cinema.controller.admin.dto.StrategyResponse;
import com.cinema.controller.admin.dto.StrategyUpdateRequest;
import com.cinema.model.CinemaHall;
import com.cinema.model.Movie;
import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.enums.HallType;
import com.cinema.model.enums.MovieStatus;
import com.cinema.model.enums.PriceType;
import com.cinema.model.enums.SeatState;
import com.cinema.model.enums.SeatZone;
import com.cinema.model.enums.ShowingStatus;
import com.cinema.repository.CinemaHallRepository;
import com.cinema.repository.MovieRepository;
import com.cinema.repository.SeatRepository;
import com.cinema.repository.ShowingRepository;
import com.cinema.service.admin.strategy.PriceStrategy;
import com.cinema.service.admin.strategy.PriceStrategyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 管理端场次服务（UC103 创建场次并按模板生成座位 / UC104 取消场次 / UC105 配置票价策略 /
 * 排片列表查询）。
 *
 * <p>// @UseCase: UC103/UC104/UC105
 *
 * <p>关键约定（spec 沉默处的最简解读，与字段表一致）：
 * <ol>
 *   <li>时段冲突 = 同影厅 PUBLISHED 场次区间重叠（UC103-2a）；放映时长取影片 duration_min
 *       （缺省按 0 分钟），缓冲 0 分钟（紧接排片允许）；CANCELED 场次不占用时段。</li>
 *   <li>UC103-3a"未配置票价策略"= 请求未携带 priceType → 默认套用全场原价（ORIGINAL）
 *       并在响应 message 提示；priceParams（含 basePrice）始终必填——无价场次不可售。</li>
 *   <li>取消场次（UC104/I14）：存在已售<b>或锁定中</b>座位均拒绝（锁定中取消会产生悬挂锁定，
 *       保守处理）；仅置 CANCELED，不级联改动座位/订单/候补。</li>
 *   <li>策略切换（UC105）：即时生效，只改 showing.price_type/price_params；
 *       已出票订单金额在 order_item.price_snapshot 快照，不受影响。</li>
 * </ol>
 */
@Service
public class ShowingAdminService {

    private final ShowingRepository showingRepository;
    private final MovieRepository movieRepository;
    private final CinemaHallRepository cinemaHallRepository;
    private final SeatRepository seatRepository;
    private final PriceStrategyResolver strategyResolver;
    private final ImaxSeatFactory imaxSeatFactory;
    private final StandardSeatFactory standardSeatFactory;
    private final ObjectMapper objectMapper;

    /** 创建场次结果：场次响应 + 是否命中 UC103-3a 默认策略（供 controller 组装提示消息）。 */
    public record CreateResult(ShowingResponse showing, boolean strategyDefaulted) {
    }

    public ShowingAdminService(ShowingRepository showingRepository,
                               MovieRepository movieRepository,
                               CinemaHallRepository cinemaHallRepository,
                               SeatRepository seatRepository,
                               PriceStrategyResolver strategyResolver,
                               ImaxSeatFactory imaxSeatFactory,
                               StandardSeatFactory standardSeatFactory,
                               ObjectMapper objectMapper) {
        this.showingRepository = showingRepository;
        this.movieRepository = movieRepository;
        this.cinemaHallRepository = cinemaHallRepository;
        this.seatRepository = seatRepository;
        this.strategyResolver = strategyResolver;
        this.imaxSeatFactory = imaxSeatFactory;
        this.standardSeatFactory = standardSeatFactory;
        this.objectMapper = objectMapper;
    }

    /** 创建场次并按模板生成座位（UC103；扩展点 2a/3a/5a）。 */
    @Transactional
    public CreateResult create(ShowingCreateRequest request) {
        // UC103 前置：影片已上架（5a：已下架终止创建）
        Movie movie = movieRepository.findById(request.movieId())
                .orElseThrow(() -> new BizException(ErrorCode.MOVIE_NOT_FOUND));
        if (movie.getStatus() != MovieStatus.ON_SHOW) {
            throw new BizException(ErrorCode.MOVIE_OFF_SHOW);
        }
        CinemaHall hall = cinemaHallRepository.findById(request.hallId())
                .orElseThrow(() -> new BizException(ErrorCode.HALL_NOT_FOUND));

        // UC103-2a：同影厅时段无排片冲突
        assertNoTimeConflict(hall, request.startTime(), movie);

        // UC103-3a：未配置票价策略 → 默认套用全场原价（priceParams 仍必填）
        boolean strategyDefaulted = request.priceType() == null;
        PriceType priceType = strategyDefaulted ? PriceType.ORIGINAL : request.priceType();
        JsonNode params = requireParams(request.priceParams());
        PriceStrategy strategy = strategyResolver.of(priceType);
        strategy.validate(params);

        Showing showing = new Showing();
        showing.setMovie(movie);
        showing.setHall(hall);
        showing.setStartTime(request.startTime());
        showing.setStatus(ShowingStatus.PUBLISHED);
        showing.setPriceType(priceType);
        showing.setPriceParams(params.toString());
        showing = showingRepository.save(showing);

        // UC103-4：按影厅座位模板为该场次生成全部"空闲"座位
        seatRepository.saveAll(factoryOf(hall.getHallType()).createSeats(showing));

        return new CreateResult(toResponse(showing), strategyDefaulted);
    }

    /** 取消场次（UC104；I14：已售票场次禁止取消；锁定中一并保守拒绝）。 */
    @Transactional
    public ShowingResponse cancel(Long id) {
        Showing showing = requireShowing(id);
        if (showing.getStatus() != ShowingStatus.PUBLISHED) {
            throw new BizException(ErrorCode.SHOWING_NOT_CANCELABLE);
        }
        List<Seat> seats = seatRepository.findByShowingId(id);
        boolean occupied = seats.stream().anyMatch(s ->
                s.getStatus() == SeatState.SOLD || s.getStatus() == SeatState.LOCKED);
        if (occupied) {
            throw new BizException(ErrorCode.SHOWING_CANCEL_FORBIDDEN);
        }
        showing.setStatus(ShowingStatus.CANCELED);
        return toResponse(showing);
    }

    /** 配置/切换票价策略（UC105；3a 参数非法拒绝；即时生效，快照价不受影响）。 */
    @Transactional
    public StrategyResponse updateStrategy(Long id, StrategyUpdateRequest request) {
        Showing showing = requireShowing(id);
        PriceStrategy strategy = strategyResolver.of(request.priceType());
        JsonNode params = requireParams(request.priceParams());
        strategy.validate(params);

        showing.setPriceType(request.priceType());
        showing.setPriceParams(params.toString());

        // UC105-4：返回策略作用后的示例价格预览（ZONE_PRICING 含黄金区两档）
        Map<String, BigDecimal> examplePrices = new LinkedHashMap<>();
        examplePrices.put("NORMAL", strategy.calculate(SeatZone.NORMAL, params));
        if (request.priceType() == PriceType.ZONE_PRICING) {
            examplePrices.put("GOLDEN", strategy.calculate(SeatZone.GOLDEN, params));
        }
        return new StrategyResponse(id, request.priceType(), params, examplePrices);
    }

    /** 排片列表查询（UC103/104）：按开映时间升序，movieId/hallId/status 可选过滤。 */
    @Transactional(readOnly = true)
    public List<ShowingResponse> list(Long movieId, Long hallId, String status) {
        ShowingStatus statusEnum = parseStatus(status);
        List<Showing> showings = showingRepository.findAll(Sort.by(Sort.Direction.ASC, "startTime"));
        return showings.stream()
                .filter(s -> movieId == null || movieId.equals(s.getMovie().getId()))
                .filter(s -> hallId == null || hallId.equals(s.getHall().getId()))
                .filter(s -> statusEnum == null || s.getStatus() == statusEnum)
                .map(this::toResponse)
                .toList();
    }

    // ---------------- 内部方法 ----------------

    /** UC103-2a：同影厅 PUBLISHED 场次区间重叠即冲突（时长缺省按 0，缓冲 0 分钟，紧接排片允许）。 */
    private void assertNoTimeConflict(CinemaHall hall, LocalDateTime start, Movie movie) {
        List<Showing> existing = showingRepository.findByHallIdAndStatus(hall.getId(), ShowingStatus.PUBLISHED);
        LocalDateTime newEnd = start.plusMinutes(durationOf(movie));
        for (Showing other : existing) {
            LocalDateTime otherStart = other.getStartTime();
            LocalDateTime otherEnd = otherStart.plusMinutes(durationOf(other.getMovie()));
            if (otherStart.isBefore(newEnd) && start.isBefore(otherEnd)) {
                throw new BizException(ErrorCode.HALL_TIME_CONFLICT,
                        "该影厅该时段已有场次（场次 " + other.getId() + "，" + otherStart + " 开映），请重选时间");
            }
        }
    }

    /** 放映时长（分钟）：duration_min 缺省按 0（区间退化为时刻，仅与自身完全同时刻冲突）。 */
    private int durationOf(Movie movie) {
        Integer duration = movie.getDurationMin();
        return duration == null ? 0 : duration;
    }

    /** 策略参数必填校验（缺失/空对象 → 11003，UC103-3 / UC105-3）。 */
    private JsonNode requireParams(JsonNode params) {
        if (params == null || params.isNull() || params.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_STRATEGY_PARAM, "票价策略参数非法：priceParams 缺失");
        }
        return params;
    }

    private Showing requireShowing(Long id) {
        return showingRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.SHOWING_NOT_FOUND));
    }

    private SeatMatrixFactory factoryOf(HallType hallType) {
        return hallType == HallType.IMAX ? imaxSeatFactory : standardSeatFactory;
    }

    /** status 过滤参数解析：大小写不敏感；非法取值 → INVALID_PARAM。 */
    private ShowingStatus parseStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        try {
            return ShowingStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INVALID_PARAM, "参数非法：status 取值无效");
        }
    }

    private ShowingResponse toResponse(Showing showing) {
        List<Seat> seats = seatRepository.findByShowingId(showing.getId());
        int sold = 0;
        int locked = 0;
        int free = 0;
        for (Seat seat : seats) {
            switch (seat.getStatus()) {
                case SOLD -> sold++;
                case LOCKED -> locked++;
                case FREE -> free++;
            }
        }
        return new ShowingResponse(showing.getId(),
                showing.getMovie().getId(), showing.getMovie().getTitle(),
                showing.getHall().getId(), showing.getHall().getName(),
                showing.getStartTime(), showing.getStatus(), showing.getPriceType(),
                parseParams(showing.getPriceParams()),
                seats.size(), free, locked, sold);
    }

    /** price_params JSON → 对象节点（响应以对象形式返回）；解析失败返回 null（不应发生）。 */
    private JsonNode parseParams(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
