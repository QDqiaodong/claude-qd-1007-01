package com.amusement.park.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.amusement.park.common.BizException;
import com.amusement.park.dto.BoardResult;
import com.amusement.park.entity.Member;
import com.amusement.park.entity.Ride;
import com.amusement.park.entity.SeasonPass;
import com.amusement.park.repository.MemberRepository;
import com.amusement.park.repository.RideRepository;
import com.amusement.park.repository.SeasonPassRepository;

/**
 * 乘坐核验：散客流程保持兼容；会员身份 — 卡片有效期 — 设施限制 一次完成。
 * 固定时钟 2026-10-06，覆盖会员不存在、卡片不存在、日期边界、有效卡不绕过
 * 设施限制、续期后立即生效等关键状态转换。
 */
class RideBoardServiceTest {

    private RideRepository rideRepository;
    private MemberRepository memberRepository;
    private SeasonPassRepository passRepository;
    private RideService service;

    @BeforeEach
    void setUp() {
        rideRepository = mock(RideRepository.class);
        memberRepository = mock(MemberRepository.class);
        passRepository = mock(SeasonPassRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        service = new RideService(rideRepository, mock(RideInspectionService.class),
                memberRepository, passRepository, clock);
        when(rideRepository.findById(1L)).thenReturn(Optional.of(ride("开放")));
    }

    private static Ride ride(String status) {
        Ride r = new Ride();
        r.setId(1L);
        r.setRideCode("R-001");
        r.setRideName("极速过山车");
        r.setMinHeightCm(130);
        r.setMaxAge(55);
        r.setStatus(status);
        return r;
    }

    private static Member member(long id, String tier) {
        Member m = new Member();
        m.setId(id);
        m.setName("会员" + id);
        m.setTier(tier);
        return m;
    }

    private static SeasonPass pass(long memberId, String from, String to) {
        SeasonPass p = new SeasonPass();
        p.setMemberId(memberId);
        p.setPassType("年卡");
        p.setValidFrom(LocalDate.parse(from));
        p.setValidTo(LocalDate.parse(to));
        return p;
    }

    private void givenMember(long memberId, String tier, SeasonPass pass) {
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member(memberId, tier)));
        when(passRepository.findById(memberId)).thenReturn(Optional.ofNullable(pass));
    }

    // ---------------- 散客流程（未选择会员，保持原有行为） ----------------

    @Test
    void walkInPassesWithoutMember() {
        BoardResult result = service.board(1L, 150, 30, null);
        assertNull(result.getMemberId());
        assertEquals("极速过山车", result.getRideName());
        assertEquals(4, result.getChecks().size(), "散客：身份 + 设施状态 + 身高 + 年龄");
    }

    @Test
    void walkInRejectedWhenRideNotOpen() {
        when(rideRepository.findById(1L)).thenReturn(Optional.of(ride("检修")));
        BizException e = assertThrows(BizException.class, () -> service.board(1L, 150, 30, null));
        assertEquals("设施「极速过山车」当前为「检修」，未开放无法乘坐", e.getMessage());
    }

    @Test
    void walkInRejectedByHeightAndAge() {
        BizException h = assertThrows(BizException.class, () -> service.board(1L, 120, 30, null));
        assertEquals("身高 120cm 不足，该设施要求至少 130cm", h.getMessage());
        BizException a = assertThrows(BizException.class, () -> service.board(1L, 150, 60, null));
        assertEquals("年龄 60 岁超过限制，该设施仅限 55 岁以下游客", a.getMessage());
    }

    @Test
    void walkInRequiresHeightAndAge() {
        assertEquals("请填写游客身高（厘米）",
                assertThrows(BizException.class, () -> service.board(1L, null, 30, null)).getMessage());
        assertEquals("请填写游客年龄",
                assertThrows(BizException.class, () -> service.board(1L, 150, null, null)).getMessage());
    }

    // ---------------- 会员身份与卡片有效期 ----------------

    @Test
    void memberNotFoundRejected() {
        when(memberRepository.findById(99L)).thenReturn(Optional.empty());
        BizException e = assertThrows(BizException.class, () -> service.board(1L, 150, 30, 99L));
        assertEquals("会员不存在：99", e.getMessage());
    }

    @Test
    void memberTierIsNotAValidCard() {
        // 金卡会员但未办卡：等级再高也不能当作有效卡
        givenMember(5L, "金卡", null);
        BizException e = assertThrows(BizException.class, () -> service.board(1L, 150, 30, 5L));
        assertTrue(e.getMessage().contains("未办理年卡/季卡"), e.getMessage());
    }

    @Test
    void cardNotYetEffectiveRejected() {
        givenMember(2L, "银卡", pass(2L, "2026-11-01", "2027-01-31"));
        BizException e = assertThrows(BizException.class, () -> service.board(1L, 150, 30, 2L));
        assertTrue(e.getMessage().contains("尚未生效"), e.getMessage());
        assertTrue(e.getMessage().contains("2026-11-01"), e.getMessage());
    }

    @Test
    void cardExpiredRejected() {
        givenMember(3L, "普通", pass(3L, "2026-01-01", "2026-10-05"));
        BizException e = assertThrows(BizException.class, () -> service.board(1L, 150, 30, 3L));
        assertTrue(e.getMessage().contains("已过期"), e.getMessage());
        assertTrue(e.getMessage().contains("2026-10-05"), e.getMessage());
    }

    @Test
    void cardValidOnBoundaryDays() {
        // 有效期起当日（含首）
        givenMember(2L, "银卡", pass(2L, "2026-10-06", "2027-10-05"));
        assertDoesNotThrow(() -> service.board(1L, 150, 30, 2L));
        // 有效期止当日（含尾）
        givenMember(3L, "普通", pass(3L, "2026-01-01", "2026-10-06"));
        assertDoesNotThrow(() -> service.board(1L, 150, 30, 3L));
    }

    // ---------------- 有效卡不能绕过设施限制（四类条件同时满足） ----------------

    @Test
    void validCardCannotBypassRideRestrictions() {
        givenMember(1L, "金卡", pass(1L, "2026-01-01", "2026-12-31"));
        // 设施关闭
        when(rideRepository.findById(1L)).thenReturn(Optional.of(ride("关闭")));
        BizException closed = assertThrows(BizException.class, () -> service.board(1L, 150, 30, 1L));
        assertTrue(closed.getMessage().contains("未开放无法乘坐"), closed.getMessage());
        // 设施检修
        when(rideRepository.findById(1L)).thenReturn(Optional.of(ride("检修")));
        assertThrows(BizException.class, () -> service.board(1L, 150, 30, 1L));
        // 恢复开放但身高不足
        when(rideRepository.findById(1L)).thenReturn(Optional.of(ride("开放")));
        BizException h = assertThrows(BizException.class, () -> service.board(1L, 120, 30, 1L));
        assertTrue(h.getMessage().contains("身高 120cm 不足"), h.getMessage());
        // 年龄超限
        BizException a = assertThrows(BizException.class, () -> service.board(1L, 150, 60, 1L));
        assertTrue(a.getMessage().contains("年龄 60 岁超过限制"), a.getMessage());
        // 四类条件同时满足才放行
        BoardResult ok = service.board(1L, 150, 30, 1L);
        assertEquals(1L, ok.getMemberId());
        assertEquals("年卡", ok.getPassType());
        assertEquals(5, ok.getChecks().size(), "会员身份 + 卡片有效期 + 设施状态 + 身高 + 年龄");
    }

    // ---------------- 续期后下一次核验立即使用新日期 ----------------

    @Test
    void renewedCardIsUsedImmediatelyByNextBoard() {
        // 第一次核验：卡已过期 → 拒绝
        givenMember(3L, "普通", pass(3L, "2026-01-01", "2026-10-05"));
        assertThrows(BizException.class, () -> service.board(1L, 150, 30, 3L));
        // 续期落库后第二次核验：实时读取新有效期 → 放行（不依赖重启或旧缓存）
        when(passRepository.findById(3L)).thenReturn(Optional.of(pass(3L, "2026-10-06", "2027-10-05")));
        BoardResult ok = service.board(1L, 150, 30, 3L);
        assertEquals(LocalDate.parse("2027-10-05"), ok.getValidTo());
        verify(passRepository, times(2)).findById(3L);
    }
}
