package com.amusement.park.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 年卡 / 季卡：与 {@link Member} 通过共享主键（@PrimaryKeyJoinColumn）一对一关联，
 * 复用会员主键 id 作为本表主键 member_id，同时作为外键指向 member.id。
 *
 * <p>因此本实体不另设自增主键，member_id 即主键也是外键。</p>
 */
@Entity
@Table(name = "season_pass")
@Getter
@Setter
public class SeasonPass {

    /** 共享主键：与会员 id 一致 */
    @Id
    @Column(name = "member_id")
    private Long memberId;

    /** 共享主键一对一：member_id 既是本表主键也是指向 member 的外键 */
    @OneToOne
    @PrimaryKeyJoinColumn(name = "member_id")
    private Member member;

    /** 有效期起 */
    @Column(name = "valid_from")
    private LocalDate validFrom;

    /** 有效期止 */
    @Column(name = "valid_to")
    private LocalDate validTo;

    /** 卡类型：年卡 / 季卡 */
    @Column(name = "pass_type", length = 16)
    private String passType;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
