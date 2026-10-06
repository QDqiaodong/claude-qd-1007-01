package com.amusement.park.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

import lombok.Getter;
import lombok.Setter;

/**
 * 年卡 / 季卡：与 {@link Member} 共享主键——复用会员主键 id 作为本表主键 member_id，
 * 同时作为外键指向 member.id，因此本实体不另设自增主键。
 *
 * <p>映射说明（两条约束，均有回归测试覆盖）：</p>
 * <ul>
 *   <li>member_id 是显式 @Id，办卡时由服务层显式赋值；member 关联仅作只读导航
 *       （insertable/updatable = false），不作为派生标识来源。</li>
 *   <li>实现 {@link Persistable}：新卡（createdAt 为空）让 Spring Data 的 save()
 *       走 persist 而非 merge——主键列上的一对一关联在 merge 的 copyValues 阶段会
 *       触发 Hibernate「null identifier」断言异常，persist 路径无此问题。</li>
 * </ul>
 */
@Entity
@Table(name = "season_pass")
@Getter
@Setter
public class SeasonPass implements Persistable<Long> {

    /** 共享主键：与会员 id 一致 */
    @Id
    @Column(name = "member_id")
    private Long memberId;

    /** 只读一对一导航：member_id 既是本表主键也是指向 member 的外键 */
    @OneToOne
    @JoinColumn(name = "member_id", insertable = false, updatable = false)
    private Member member;

    /** 有效期起（含当日） */
    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** 有效期止（含当日） */
    @Column(name = "valid_to", nullable = false)
    private LocalDate validTo;

    /** 卡类型：年卡 / 季卡 */
    @Column(name = "pass_type", nullable = false, length = 16)
    private String passType;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** Spring Data 以 id 判空决定 persist/merge；本实体主键由办卡时显式赋值，改用 createdAt 判新 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Override
    @Transient
    public Long getId() {
        return memberId;
    }

    /** 新卡（尚未落库，createdAt 为空）→ save() 走 persist；已存在的卡 → merge（管理态直接返回） */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Override
    @Transient
    public boolean isNew() {
        return createdAt == null;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
