package com.cinema.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 站内通知实体（表 notification），spec §1；N:1 user。
 *
 * <p>由观察者订阅者写入（UC306，通知订阅者属 task-04/05）。
 * type 取值约定（与 common/event 四类事件对应）：
 * TICKET_ISSUED 出票成功 / SEAT_RELEASED 座位释放 / QUEUE_MATCHED 递补成功 / REFUND_COMPLETED 退款到账。
 */
@Entity
@Table(name = "notification")
public class Notification {

    /** 主键（自增）。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 通知接收用户。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 通知类型（取值约定见类注释）。 */
    @Column(name = "type", length = 20, nullable = false)
    private String type;

    /** 通知标题。 */
    @Column(name = "title", length = 100, nullable = false)
    private String title;

    /** 通知内容（VARCHAR(1000)：双方言兼容起见未用 TEXT，长度充裕）。 */
    @Column(name = "content", length = 1000)
    private String content;

    /** 已读标志（默认未读）。 */
    @Column(name = "read_flag", nullable = false)
    private boolean readFlag;

    /** 创建时间（Java 侧赋值；DDL 亦带 DEFAULT CURRENT_TIMESTAMP 兜底）。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isReadFlag() {
        return readFlag;
    }

    public void setReadFlag(boolean readFlag) {
        this.readFlag = readFlag;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
