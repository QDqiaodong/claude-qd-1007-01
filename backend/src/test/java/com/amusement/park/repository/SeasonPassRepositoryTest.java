package com.amusement.park.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.amusement.park.dto.SeasonPassView;
import com.amusement.park.entity.Member;
import com.amusement.park.entity.SeasonPass;

/**
 * 持久层：共享主键映射与「卡片 ⋈ 会员」关联查询。
 * 用 H2 内存库按 JPA 注解建表，验证结果与 MySQL 行为一致，可重复执行。
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SeasonPassRepositoryTest {

    @Autowired
    private SeasonPassRepository passRepository;

    @Autowired
    private MemberRepository memberRepository;

    private Member newMember(String name, String tier) {
        Member m = new Member();
        m.setName(name);
        m.setTier(tier);
        m.setCreatedAt(LocalDateTime.now());
        return memberRepository.save(m);
    }

    private SeasonPass newPass(Member m, String from, String to) {
        SeasonPass p = new SeasonPass();
        p.setMemberId(m.getId());
        p.setMember(m);
        p.setPassType("年卡");
        p.setValidFrom(LocalDate.parse(from));
        p.setValidTo(LocalDate.parse(to));
        // createdAt 留空（与服务层办卡一致）：isNew() 为 true → save() 走 persist，@PrePersist 补 createdAt
        return passRepository.save(p);
    }

    @Test
    void seasonPassSharesPrimaryKeyWithMember() {
        Member m = newMember("林晓彤", "金卡");
        SeasonPass saved = newPass(m, "2026-01-01", "2026-12-31");

        assertEquals(m.getId(), saved.getMemberId(), "卡片主键必须与会员主键一致（共享主键）");
        assertTrue(passRepository.findById(m.getId()).isPresent(), "乘坐核验按会员 id 直连读取卡片");
    }

    @Test
    void findAllWithMemberJoinsMemberInfo() {
        Member a = newMember("林晓彤", "金卡");
        newPass(a, "2026-01-01", "2026-12-31");
        Member b = newMember("陈志远", "银卡");
        newPass(b, "2026-04-01", "2026-06-30");
        newMember("王梓涵", "普通"); // 未办卡的会员不出现在卡片列表

        List<SeasonPassView> views = passRepository.findAllWithMember();

        assertEquals(2, views.size());
        SeasonPassView first = views.get(0);
        assertEquals(a.getId(), first.getMemberId());
        assertEquals("林晓彤", first.getMemberName());
        assertEquals("金卡", first.getTier());
        assertEquals("年卡", first.getPassType());
        assertEquals(LocalDate.parse("2026-01-01"), first.getValidFrom());
        assertEquals(LocalDate.parse("2026-12-31"), first.getValidTo());
        assertEquals("陈志远", views.get(1).getMemberName());
    }

    @Test
    void findByMemberIdForUpdateFindsSharedPkRow() {
        Member m = newMember("孙佳怡", "金卡");
        newPass(m, "2026-05-01", "2026-07-31");

        assertTrue(passRepository.findByMemberIdForUpdate(m.getId()).isPresent());
        assertTrue(passRepository.findByMemberIdForUpdate(m.getId() + 100).isEmpty());
    }
}
