package com.amusement.park.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.amusement.park.common.BizException;
import com.amusement.park.dto.SeasonPassView;
import com.amusement.park.entity.Member;
import com.amusement.park.entity.SeasonPass;
import com.amusement.park.repository.MemberRepository;
import com.amusement.park.repository.SeasonPassRepository;

/**
 * 年卡/季卡：日期区间校验、有效期状态边界、办卡/续期异常与重复提交。
 * 固定时钟 2026-10-06，用例可重复、不依赖运行日期。
 */
class SeasonPassServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    private SeasonPassRepository passRepository;
    private MemberRepository memberRepository;
    private SeasonPassService service;

    @BeforeEach
    void setUp() {
        passRepository = mock(SeasonPassRepository.class);
        memberRepository = mock(MemberRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        service = new SeasonPassService(passRepository, memberRepository, clock);
    }

    private static Member member(long id, String name, String tier) {
        Member m = new Member();
        m.setId(id);
        m.setName(name);
        m.setTier(tier);
        return m;
    }

    private static SeasonPassService.LocalDateHolder holder(String from, String to) {
        SeasonPassService.LocalDateHolder h = new SeasonPassService.LocalDateHolder();
        h.validFrom = LocalDate.parse(from);
        h.validTo = LocalDate.parse(to);
        return h;
    }

    // ---------------- 有效期状态边界（含首尾） ----------------

    @Test
    void statusBoundariesAreInclusive() {
        assertEquals("未生效", SeasonPassService.statusOf(
                LocalDate.parse("2026-10-07"), LocalDate.parse("2026-11-07"), TODAY), "生效日前一天");
        assertEquals("有效", SeasonPassService.statusOf(
                LocalDate.parse("2026-10-06"), LocalDate.parse("2026-11-06"), TODAY), "有效期起当日（含首）");
        assertEquals("有效", SeasonPassService.statusOf(
                LocalDate.parse("2026-09-06"), LocalDate.parse("2026-10-06"), TODAY), "有效期止当日（含尾）");
        assertEquals("已过期", SeasonPassService.statusOf(
                LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-05"), TODAY), "过期后一天");
    }

    // ---------------- 办卡 ----------------

    @Test
    void issueRejectsBlankDates() {
        BizException e = assertThrows(BizException.class,
                () -> service.issue(1L, new SeasonPassService.LocalDateHolder()));
        assertEquals("请填写有效期起和有效期止", e.getMessage());
        verify(passRepository, never()).save(any());
    }

    @Test
    void issueRejectsInvalidRange() {
        BizException equal = assertThrows(BizException.class, () -> service.issue(1L, holder("2026-10-06", "2026-10-06")));
        assertEquals("有效期起必须早于有效期止", equal.getMessage());
        BizException reversed = assertThrows(BizException.class, () -> service.issue(1L, holder("2027-01-01", "2026-01-01")));
        assertEquals("有效期起必须早于有效期止", reversed.getMessage());
        verify(passRepository, never()).save(any());
    }

    @Test
    void issueRejectsUnknownMember() {
        when(memberRepository.findByIdForUpdate(9L)).thenReturn(Optional.empty());
        BizException e = assertThrows(BizException.class, () -> service.issue(9L, holder("2026-01-01", "2027-01-01")));
        assertEquals("会员不存在：9", e.getMessage());
    }

    @Test
    void issueRejectsDuplicateOnRepeatedClick() {
        when(memberRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(member(1L, "林晓彤", "金卡")));
        when(passRepository.existsById(1L)).thenReturn(true);
        BizException e = assertThrows(BizException.class, () -> service.issue(1L, holder("2026-01-01", "2027-01-01")));
        assertEquals("该会员已持有年卡/季卡，请使用续期接口", e.getMessage());
        verify(passRepository, never()).save(any());
    }

    @Test
    void issueSuccessUsesSharedPrimaryKeyAndHonorsPassType() {
        when(memberRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(member(2L, "陈志远", "银卡")));
        when(passRepository.existsById(2L)).thenReturn(false);
        when(passRepository.save(any(SeasonPass.class))).thenAnswer(inv -> inv.getArgument(0));
        SeasonPassService.LocalDateHolder h = holder("2026-10-01", "2027-01-31");
        h.passType = "季卡";
        SeasonPass saved = service.issue(2L, h);
        assertEquals(2L, saved.getMemberId(), "卡片主键必须与会员共享主键");
        assertEquals("季卡", saved.getPassType());
        assertEquals(LocalDate.parse("2026-10-01"), saved.getValidFrom());
        assertEquals(LocalDate.parse("2027-01-31"), saved.getValidTo());
    }

    @Test
    void issueRejectsIllegalPassType() {
        when(memberRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(member(2L, "陈志远", "银卡")));
        when(passRepository.existsById(2L)).thenReturn(false);
        SeasonPassService.LocalDateHolder h = holder("2026-10-01", "2027-01-31");
        h.passType = "月卡";
        BizException e = assertThrows(BizException.class, () -> service.issue(2L, h));
        assertEquals("卡类型不合法：月卡", e.getMessage());
    }

    // ---------------- 续期 ----------------

    @Test
    void renewRejectsMissingCard() {
        when(passRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.empty());
        BizException e = assertThrows(BizException.class, () -> service.renew(7L, holder("2026-10-06", "2027-10-05")));
        assertEquals("未找到该会员的年卡/季卡，无法续期：7", e.getMessage());
    }

    @Test
    void renewRejectsInvalidRange() {
        BizException e = assertThrows(BizException.class, () -> service.renew(3L, holder("2027-01-01", "2026-01-01")));
        assertEquals("有效期起必须早于有效期止", e.getMessage());
        verify(passRepository, never()).save(any());
    }

    @Test
    void renewAppliesNewDatesImmediately() {
        SeasonPass existing = new SeasonPass();
        existing.setMemberId(3L);
        existing.setPassType("年卡");
        existing.setValidFrom(LocalDate.parse("2025-01-01"));
        existing.setValidTo(LocalDate.parse("2025-12-31"));
        when(passRepository.findByMemberIdForUpdate(3L)).thenReturn(Optional.of(existing));
        when(passRepository.save(any(SeasonPass.class))).thenAnswer(inv -> inv.getArgument(0));

        SeasonPass renewed = service.renew(3L, holder("2026-10-06", "2027-10-05"));

        assertEquals(LocalDate.parse("2026-10-06"), renewed.getValidFrom());
        assertEquals(LocalDate.parse("2027-10-05"), renewed.getValidTo());
        // 续期成功后按同一「今天」计算的状态立即变为有效，乘坐核验无需等待缓存刷新
        assertEquals("有效", SeasonPassService.statusOf(renewed.getValidFrom(), renewed.getValidTo(), TODAY));
    }

    @Test
    void repeatedRenewIsIdempotentAndNeverRolledBack() {
        SeasonPass existing = new SeasonPass();
        existing.setMemberId(3L);
        existing.setPassType("年卡");
        existing.setValidFrom(LocalDate.parse("2025-01-01"));
        existing.setValidTo(LocalDate.parse("2025-12-31"));
        when(passRepository.findByMemberIdForUpdate(3L)).thenReturn(Optional.of(existing));
        when(passRepository.save(any(SeasonPass.class))).thenAnswer(inv -> inv.getArgument(0));

        SeasonPass first = service.renew(3L, holder("2026-10-06", "2027-10-05"));
        SeasonPass second = service.renew(3L, holder("2026-10-06", "2027-10-05")); // 重复点击同一请求

        assertEquals(first.getValidFrom(), second.getValidFrom());
        assertEquals(first.getValidTo(), second.getValidTo());
        assertEquals("有效", SeasonPassService.statusOf(second.getValidFrom(), second.getValidTo(), TODAY));
    }

    // ---------------- 列表视图（关联会员 + 状态） ----------------

    @Test
    void listFillsStatusByToday() {
        SeasonPassView valid = new SeasonPassView(1L, "林晓彤", "金卡", "年卡",
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        SeasonPassView pending = new SeasonPassView(2L, "陈志远", "银卡", "季卡",
                LocalDate.parse("2026-11-01"), LocalDate.parse("2027-01-31"));
        SeasonPassView expired = new SeasonPassView(3L, "王梓涵", "普通", "年卡",
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"));
        when(passRepository.findAllWithMember()).thenReturn(List.of(valid, pending, expired));

        List<SeasonPassView> views = service.list();

        assertEquals(3, views.size());
        assertEquals("有效", views.get(0).getStatus());
        assertEquals("未生效", views.get(1).getStatus());
        assertEquals("已过期", views.get(2).getStatus());
        assertEquals("林晓彤", views.get(0).getMemberName());
    }
}
