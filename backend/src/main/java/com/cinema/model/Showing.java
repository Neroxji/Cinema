package com.cinema.model;

import com.cinema.model.enums.PriceType;
import com.cinema.model.enums.ShowingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 场次实体（表 showing）：场次排片，spec §1；N:1 movie、cinema_hall；1:N seat、ticket_order、queue。
 *
 * <p>price_params 为票价策略参数 JSON（见 {@link PriceType} 的参数约定）；
 * 已出票订单价格不受策略切换影响（UC105 后置条件）。
 */
@Entity
@Table(name = "showing")
public class Showing {

    /** 主键（自增）。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 放映影片。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    /** 放映影厅（座位模板来源）。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hall_id", nullable = false)
    private CinemaHall hall;

    /** 开映时间（退票截止 = 开映前 2 小时；检票宽限以开映时间推算）。 */
    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    /** 场次状态：PUBLISHED 已发布 / CANCELED 已取消 / FINISHED 已结束（预留）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ShowingStatus status;

    /** 票价策略类型：ORIGINAL / MEMBER_DISCOUNT / ZONE_PRICING（三档预设）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "price_type", length = 20, nullable = false)
    private PriceType priceType;

    /** 计价参数 JSON（字段含义见 {@link PriceType} 注释）。 */
    @Column(name = "price_params", length = 255)
    private String priceParams;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Movie getMovie() {
        return movie;
    }

    public void setMovie(Movie movie) {
        this.movie = movie;
    }

    public CinemaHall getHall() {
        return hall;
    }

    public void setHall(CinemaHall hall) {
        this.hall = hall;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public ShowingStatus getStatus() {
        return status;
    }

    public void setStatus(ShowingStatus status) {
        this.status = status;
    }

    public PriceType getPriceType() {
        return priceType;
    }

    public void setPriceType(PriceType priceType) {
        this.priceType = priceType;
    }

    public String getPriceParams() {
        return priceParams;
    }

    public void setPriceParams(String priceParams) {
        this.priceParams = priceParams;
    }
}
