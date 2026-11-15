package com.cinema.model;

import com.cinema.model.enums.HallType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 影厅实体（表 cinema_hall）：影厅与座位模板，spec §1；1:N showing。
 *
 * <p>座位由工厂按模板生成（UC102/UC103，{@code SeatMatrixFactory} 工厂方法）；
 * zone_rule 存分区规则 JSON，与每个 seat.zone 一致。
 */
@Entity
@Table(name = "cinema_hall")
public class CinemaHall {

    /** 主键（自增）。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 影厅名称。 */
    @Column(name = "name", length = 50, nullable = false)
    private String name;

    /** 影厅类型：IMAX / STANDARD。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "hall_type", length = 20, nullable = false)
    private HallType hallType;

    /** 行数（rows 为 MySQL/H2 关键字，需转义）。 */
    @Column(name = "`rows`", nullable = false)
    private Integer rows;

    /** 列数（每排座位数）。 */
    @Column(name = "`cols`", nullable = false)
    private Integer cols;

    /** 分区规则 JSON，由座位模板工厂写入（如 {"type":"GOLDEN_ROWS","goldenRows":[2,3,4]}）。 */
    @Column(name = "zone_rule", length = 255)
    private String zoneRule;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public HallType getHallType() {
        return hallType;
    }

    public void setHallType(HallType hallType) {
        this.hallType = hallType;
    }

    public Integer getRows() {
        return rows;
    }

    public void setRows(Integer rows) {
        this.rows = rows;
    }

    public Integer getCols() {
        return cols;
    }

    public void setCols(Integer cols) {
        this.cols = cols;
    }

    public String getZoneRule() {
        return zoneRule;
    }

    public void setZoneRule(String zoneRule) {
        this.zoneRule = zoneRule;
    }
}
