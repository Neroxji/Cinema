package com.cinema.model;

import com.cinema.model.enums.MovieStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 影片实体（表 movie）：影片基础信息，spec §1；1:N showing。
 */
@Entity
@Table(name = "movie")
public class Movie {

    /** 主键（自增）。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 片名。 */
    @Column(name = "title", length = 100, nullable = false)
    private String title;

    /** 类型（科幻/动作/喜剧…）。 */
    @Column(name = "genre", length = 50)
    private String genre;

    /** 片长（分钟）。 */
    @Column(name = "duration_min")
    private Integer durationMin;

    /** 海报地址。 */
    @Column(name = "poster_url", length = 255)
    private String posterUrl;

    /** 状态：ON_SHOW 上映中 / OFF_SHOW 已下架（UC104）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private MovieStatus status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getGenre() {
        return genre;
    }

    public void setGenre(String genre) {
        this.genre = genre;
    }

    public Integer getDurationMin() {
        return durationMin;
    }

    public void setDurationMin(Integer durationMin) {
        this.durationMin = durationMin;
    }

    public String getPosterUrl() {
        return posterUrl;
    }

    public void setPosterUrl(String posterUrl) {
        this.posterUrl = posterUrl;
    }

    public MovieStatus getStatus() {
        return status;
    }

    public void setStatus(MovieStatus status) {
        this.status = status;
    }
}
