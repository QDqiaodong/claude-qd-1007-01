package com.amusement.park.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.amusement.park.common.BizException;
import com.amusement.park.common.Dict;
import com.amusement.park.entity.Member;
import com.amusement.park.repository.MemberRepository;

/**
 * 会员业务：园区会员基础档案，含 普通 / 银卡 / 金卡 三个等级。
 */
@Service
public class MemberService {

    private final MemberRepository memberRepository;

    public MemberService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    public List<Member> list(String tier) {
        if (tier != null && !tier.isBlank()) {
            return memberRepository.findAll().stream()
                    .filter(m -> tier.equals(m.getTier()))
                    .toList();
        }
        return memberRepository.findAll();
    }

    public Member get(Long id) {
        return memberRepository.findById(id)
                .orElseThrow(() -> new BizException("会员不存在：" + id));
    }

    @Transactional
    public Member create(Member input) {
        if (input.getName() == null || input.getName().isBlank()) {
            throw new BizException("会员姓名不能为空");
        }
        if (!Dict.isValidMemberTier(input.getTier())) {
            throw new BizException("会员等级不合法：" + input.getTier());
        }
        Member member = new Member();
        member.setName(input.getName().trim());
        member.setTier(input.getTier());
        member.setPhone(input.getPhone());
        member.setCreatedAt(LocalDateTime.now());
        return memberRepository.save(member);
    }

    @Transactional
    public Member update(Long id, Member input) {
        Member member = get(id);
        if (input.getName() != null && !input.getName().isBlank()) {
            member.setName(input.getName().trim());
        }
        if (input.getTier() != null && !input.getTier().isBlank()) {
            if (!Dict.isValidMemberTier(input.getTier())) {
                throw new BizException("会员等级不合法：" + input.getTier());
            }
            member.setTier(input.getTier());
        }
        if (input.getPhone() != null) {
            member.setPhone(input.getPhone());
        }
        return memberRepository.save(member);
    }
}
