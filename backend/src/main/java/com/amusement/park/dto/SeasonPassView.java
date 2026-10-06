package com.amusement.park.dto;

import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

/**
 * 年卡/季卡视图：后端关联查询（season_pass ⋈ member）一次带出会员姓名与等级，
 * 并附带服务端按当前日期计算的卡片状态，供会员页与乘坐弹窗直接展示。
 */
@Getter
@Setter
public class SeasonPassView {

    /** 共享主键：与会员 id 一致 */
    private Long memberId;
    private String memberName;
    private String tier;
    private String passType;
    private LocalDate validFrom;
    private LocalDate validTo;

    /** 卡片状态：未生效 / 有效 / 已过期（有效期含首尾两日，由服务层按当前日期填充） */
    private String status;

    /** JPQL 构造表达式使用；status 由服务层计算后回填 */
    public SeasonPassView(Long memberId, String memberName, String tier, String passType,
                          LocalDate validFrom, LocalDate validTo) {
        this.memberId = memberId;
        this.memberName = memberName;
        this.tier = tier;
        this.passType = passType;
        this.validFrom = validFrom;
        this.validTo = validTo;
    }
}
