package com.cinema.service.purchase;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.config.BookingConfig;
import com.cinema.controller.audience.dto.ShowingSeatMapResponse;
import com.cinema.controller.audience.dto.SeatView;
import com.cinema.model.Movie;
import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.enums.MovieStatus;
import com.cinema.repository.SeatRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 观众端只读查询服务（UC202 影片列表 / UC203 实时座位图轮询）。
 *
 * <p>// @UseCase: UC202(浏览影片)/UC203(实时座位图——NFR4 轮询轻量)
 *
 * <p>NFR4 轻量口径：座位图一次取场次+一次取座位（两次查询），不聚合订单/计价逻辑。
 * 响应组装放在事务内完成（场次关联 movie/hall 为懒加载，事务关闭后不可解引用）。
 */
// @UseCase: UC202/UC203
@Service
public class AudienceQueryService {

    private final EntityManager entityManager;
    private final SeatRepository seatRepository;
    private final BookingConfig bookingConfig;
    private final ObjectMapper objectMapper;

    public AudienceQueryService(EntityManager entityManager, SeatRepository seatRepository,
                                BookingConfig bookingConfig, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.seatRepository = seatRepository;
        this.bookingConfig = bookingConfig;
        this.objectMapper = objectMapper;
    }

    /** 上映中影片列表（UC202，按 id 升序）。 */
    @Transactional(readOnly = true)
    public List<Movie> listOnShowMovies() {
        return entityManager.createQuery(
                        "SELECT m FROM Movie m WHERE m.status = :onShow ORDER BY m.id", Movie.class)
                .setParameter("onShow", MovieStatus.ON_SHOW)
                .getResultList();
    }

    /** 场次座位图（UC203：场次详情 + 全部座位实时状态；事务内组装响应防懒加载越界）。 */
    @Transactional(readOnly = true)
    public ShowingSeatMapResponse seatMapResponse(Long showingId) {
        Showing showing = entityManager.find(Showing.class, showingId);
        if (showing == null) {
            throw new BizException(ErrorCode.SHOWING_NOT_FOUND, "场次不存在：" + showingId);
        }
        List<SeatView> seats = seatRepository.findByShowingId(showingId).stream()
                .sorted(Comparator.comparing(Seat::getRowNum).thenComparing(Seat::getColNum))
                .map(SeatView::from)
                .toList();
        return ShowingSeatMapResponse.of(showing, showing.getHall().getName(),
                parseParams(showing.getPriceParams()), lockDurationSeconds(), seats);
    }

    /** 锁定时长秒数（前端倒计时展示用；释放职责在服务端，前端倒计时仅展示，spec §4.5）。 */
    public long lockDurationSeconds() {
        return bookingConfig.getLockDuration().toSeconds();
    }

    private JsonNode parseParams(String priceParams) {
        try {
            return objectMapper.readTree(priceParams == null ? "{}" : priceParams);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
