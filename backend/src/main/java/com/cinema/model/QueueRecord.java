package com.cinema.model;

import com.cinema.model.enums.QueueStatus;
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

/**
 * 候补队列记录实体（表 queue），spec §1；N:1 showing、user。
 *
 * <p>座位释放时按 rank 升序递补（UC304/UC305，行为属 task-05）。
 * 类名 QueueRecord 规避 Queue/Order 等通用词与 SQL 关键字的混淆；表名仍按 spec 为 queue。
 */
@Entity
@Table(name = "queue")
public class QueueRecord {

    /** 主键（自增）。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 候补的场次。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "showing_id", nullable = false)
    private Showing showing;

    /** 候补观众。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 候补序号（越小越优先；rank 为 MySQL 8/H2 关键字，需转义）。 */
    @Column(name = "`rank`", nullable = false)
    private Integer rank;

    /** 候补状态：WAITING 排队 / MATCHED 已递补 / EXPIRED 已过期 / CANCELED 已取消。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private QueueStatus status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Showing getShowing() {
        return showing;
    }

    public void setShowing(Showing showing) {
        this.showing = showing;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Integer getRank() {
        return rank;
    }

    public void setRank(Integer rank) {
        this.rank = rank;
    }

    public QueueStatus getStatus() {
        return status;
    }

    public void setStatus(QueueStatus status) {
        this.status = status;
    }
}
