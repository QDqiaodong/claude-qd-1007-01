package com.amusement.park.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.amusement.park.common.BizException;
import com.amusement.park.common.Dict;
import com.amusement.park.dto.SeasonPassView;
import com.amusement.park.entity.Member;
import com.amusement.park.entity.SeasonPass;
import com.amusement.park.repository.MemberRepository;
import com.amusement.park.repository.SeasonPassRepository;

/**
 * 年卡 / 季卡业务（与会员共享主键）。
 *
 * <p>有效期规则：当前日期落在 [validFrom, validTo] 闭区间（含首尾两日）内为「有效」，
 * 早于 validFrom 为「未生效」，晚于 validTo 为「已过期」。办卡 / 续期要求
 * 有效期起早于有效期止，非法区间抛出中文 {@link BizException}。</p>
 *
 * <p>并发约定：办卡先锁会员行、续期先锁卡片行（与全系统 ride → inspection 的
 * 加锁顺序不交叉，不会死锁）。重复点击 / 并发提交串行生效：重复办卡明确拒绝、
 * 重复续期幂等落地，不会出现一次请求成功而另一请求把结果回滚。</p>
 *
 * <p>续期提交即生效：不依赖任何缓存，下一次乘坐核验实时读取最新有效期。</p>
 */
@Service
public class SeasonPassService {

    private final SeasonPassRepository seasonPassRepository;
    private final MemberRepository memberRepository;
    private final Clock clock;

    public SeasonPassService(SeasonPassRepository seasonPassRepository,
                             MemberRepository memberRepository,
                             Clock clock) {
        this.seasonPassRepository = seasonPassRepository;
        this.memberRepository = memberRepository;
        this.clock = clock;
    }

    /** 卡片在指定日期的状态：未生效 / 有效 / 已过期（有效期含首尾两日） */
    public static String statusOf(LocalDate validFrom, LocalDate validTo, LocalDate today) {
        if (validFrom == null || validTo == null) {
            return "已过期";
        }
        if (today.isBefore(validFrom)) {
            return "未生效";
        }
        if (today.isAfter(validTo)) {
            return "已过期";
        }
        return "有效";
    }

    /** 指定日期是否落在有效期起止（含首尾）内 */
    public static boolean isValidOn(LocalDate validFrom, LocalDate validTo, LocalDate today) {
        return "有效".equals(statusOf(validFrom, validTo, today));
    }

    /** 卡片列表（关联会员姓名/等级），并按当前日期填充 有效/未生效/已过期 状态 */
    public List<SeasonPassView> list() {
        LocalDate today = LocalDate.now(clock);
        List<SeasonPassView> views = seasonPassRepository.findAllWithMember();
        views.forEach(v -> v.setStatus(statusOf(v.getValidFrom(), v.getValidTo(), today)));
        return views;
    }

    public SeasonPass getByMember(Long memberId) {
        return seasonPassRepository.findById(memberId)
                .orElseThrow(() -> new BizException("未找到该会员的年卡/季卡：" + memberId));
    }

    @Transactional
    public SeasonPass issue(Long memberId, LocalDateHolder holder) {
        validateHolder(holder);
        // 锁会员行：并发/重复点击办卡串行化，后到请求看到已存在的卡后明确拒绝
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BizException("会员不存在：" + memberId));
        if (seasonPassRepository.existsById(memberId)) {
            throw new BizException("该会员已持有年卡/季卡，请使用续期接口");
        }
        SeasonPass pass = new SeasonPass();
        pass.setMemberId(memberId);
        pass.setMember(member);
        pass.setValidFrom(holder.validFrom);
        pass.setValidTo(holder.validTo);
        pass.setPassType(resolvePassType(holder.passType, "年卡"));
        // createdAt 留空 → isNew() 为 true → save() 走 persist，@PrePersist 落库前补 createdAt
        return seasonPassRepository.save(pass);
    }

    @Transactional
    public SeasonPass renew(Long memberId, LocalDateHolder holder) {
        validateHolder(holder);
        // 锁卡片行：并发续期串行生效，同一区间重复提交幂等，不会互相回滚
        SeasonPass pass = seasonPassRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> new BizException("未找到该会员的年卡/季卡，无法续期：" + memberId));
        pass.setValidFrom(holder.validFrom);
        pass.setValidTo(holder.validTo);
        pass.setPassType(resolvePassType(holder.passType, pass.getPassType()));
        pass.setCreatedAt(LocalDateTime.now(clock));
        return seasonPassRepository.save(pass);
    }

    private static void validateHolder(LocalDateHolder holder) {
        if (holder == null || holder.validFrom == null || holder.validTo == null) {
            throw new BizException("请填写有效期起和有效期止");
        }
        if (!holder.validFrom.isBefore(holder.validTo)) {
            throw new BizException("有效期起必须早于有效期止");
        }
    }

    private static String resolvePassType(String passType, String fallback) {
        if (passType == null || passType.isBlank()) {
            return fallback;
        }
        if (!Dict.isValidPassType(passType)) {
            throw new BizException("卡类型不合法：" + passType);
        }
        return passType;
    }

    /** 有效期区间载体（开放给 Controller 作为请求体）。 */
    public static class LocalDateHolder {
        public LocalDate validFrom;
        public LocalDate validTo;
        /** 卡类型：年卡 / 季卡；留空时办卡默认年卡、续期保持原类型 */
        public String passType;

        public LocalDateHolder() {
        }
    }
}
