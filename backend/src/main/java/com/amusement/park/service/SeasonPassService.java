package com.amusement.park.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.amusement.park.common.BizException;
import com.amusement.park.entity.Member;
import com.amusement.park.entity.SeasonPass;
import com.amusement.park.repository.MemberRepository;
import com.amusement.park.repository.SeasonPassRepository;

/**
 * 年卡 / 季卡业务（与会员共享主键）。
 *
 * <p>办卡 / 续期时若有效期起不早于有效期止，则视为非法区间，抛出中文 {@link BizException}。</p>
 */
@Service
public class SeasonPassService {

    private final SeasonPassRepository seasonPassRepository;
    private final MemberRepository memberRepository;

    public SeasonPassService(SeasonPassRepository seasonPassRepository,
                             MemberRepository memberRepository) {
        this.seasonPassRepository = seasonPassRepository;
        this.memberRepository = memberRepository;
    }

    public List<SeasonPass> list() {
        return seasonPassRepository.findAll();
    }

    public SeasonPass getByMember(Long memberId) {
        return seasonPassRepository.findById(memberId)
                .orElseThrow(() -> new BizException("未找到该会员的年卡/季卡：" + memberId));
    }

    @Transactional
    public SeasonPass issue(Long memberId, LocalDateHolder holder) {
        if (!holder.validFrom.isBefore(holder.validTo)) {
            throw new BizException("有效期起必须早于有效期止");
        }
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BizException("会员不存在：" + memberId));
        if (seasonPassRepository.existsById(memberId)) {
            throw new BizException("该会员已持有年卡/季卡，请使用续期接口");
        }
        SeasonPass pass = new SeasonPass();
        pass.setMemberId(memberId);
        pass.setMember(member);
        pass.setValidFrom(holder.validFrom);
        pass.setValidTo(holder.validTo);
        pass.setPassType("年卡");
        pass.setCreatedAt(LocalDateTime.now());
        return seasonPassRepository.save(pass);
    }

    @Transactional
    public SeasonPass renew(Long memberId, LocalDateHolder holder) {
        if (!holder.validFrom.isBefore(holder.validTo)) {
            throw new BizException("有效期起必须早于有效期止");
        }
        SeasonPass pass = seasonPassRepository.findById(memberId)
                .orElseThrow(() -> new BizException("未找到该会员的年卡/季卡，无法续期：" + memberId));
        pass.setValidFrom(holder.validFrom);
        pass.setValidTo(holder.validTo);
        pass.setCreatedAt(LocalDateTime.now());
        return seasonPassRepository.save(pass);
    }

    /** 有效期区间载体（开放给 Controller 作为请求体）。 */
    public static class LocalDateHolder {
        public LocalDate validFrom;
        public LocalDate validTo;

        public LocalDateHolder() {
        }
    }
}
