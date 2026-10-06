package com.amusement.park.dto;

import java.time.LocalDate;
import java.util.List;

import com.amusement.park.entity.Member;
import com.amusement.park.entity.Ride;
import com.amusement.park.entity.SeasonPass;

import lombok.Getter;

/**
 * 乘坐核验通过后的可解释结果：「会员身份 — 卡片有效期 — 设施限制」一次完成。
 *
 * <p>{@link #checks} 按核验顺序记录每一项已通过的结论，页面逐项展示；
 * 任一条件不满足时服务层直接抛出带中文原因的 BizException，页面展示的
 * 拒绝原因与接口返回的 message 完全一致。</p>
 */
@Getter
public class BoardResult {

    private final Long rideId;
    private final String rideName;

    /** 会员 id；散客（未选择会员）为 null */
    private final Long memberId;
    private final String memberName;
    private final String memberTier;

    /** 卡类型与有效期；散客为 null */
    private final String passType;
    private final LocalDate validFrom;
    private final LocalDate validTo;

    /** 逐项核验结论（按核验顺序：会员身份 → 卡片有效期 → 设施状态 → 身高 → 年龄） */
    private final List<String> checks;

    public BoardResult(Ride ride, Member member, SeasonPass pass, List<String> checks) {
        this.rideId = ride.getId();
        this.rideName = ride.getRideName();
        this.memberId = member == null ? null : member.getId();
        this.memberName = member == null ? null : member.getName();
        this.memberTier = member == null ? null : member.getTier();
        this.passType = pass == null ? null : pass.getPassType();
        this.validFrom = pass == null ? null : pass.getValidFrom();
        this.validTo = pass == null ? null : pass.getValidTo();
        this.checks = checks;
    }
}
