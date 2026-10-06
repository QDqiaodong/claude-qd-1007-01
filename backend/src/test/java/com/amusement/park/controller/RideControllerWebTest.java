package com.amusement.park.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.amusement.park.common.BizException;
import com.amusement.park.dto.BoardResult;
import com.amusement.park.entity.Member;
import com.amusement.park.entity.Ride;
import com.amusement.park.entity.SeasonPass;
import com.amusement.park.service.RideService;

/**
 * 乘坐核验接口契约：成功返回可解释结果；拒绝时 HTTP 400 + 中文原因，
 * 页面展示的拒绝原因与接口 message 完全一致。
 */
@WebMvcTest(RideController.class)
class RideControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RideService rideService;

    private static Ride ride() {
        Ride r = new Ride();
        r.setId(1L);
        r.setRideName("极速过山车");
        return r;
    }

    @Test
    void boardSuccessReturnsExplainableResult() throws Exception {
        Member member = new Member();
        member.setId(2L);
        member.setName("陈志远");
        member.setTier("银卡");
        SeasonPass pass = new SeasonPass();
        pass.setPassType("季卡");
        pass.setValidFrom(LocalDate.parse("2026-10-01"));
        pass.setValidTo(LocalDate.parse("2026-12-31"));
        when(rideService.board(eq(1L), eq(150), eq(30), eq(2L)))
                .thenReturn(new BoardResult(ride(), member, pass, List.of(
                        "会员身份：陈志远（银卡）",
                        "卡片有效期：季卡 2026-10-01 至 2026-12-31，今日在有效期内",
                        "设施状态：开放",
                        "身高：150cm ≥ 最低要求 130cm",
                        "年龄：30 岁 ≤ 上限 55 岁")));

        mockMvc.perform(post("/api/rides/1/board")
                        .param("heightCm", "150").param("age", "30").param("memberId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.message").value("核验通过，可以乘坐"))
                .andExpect(jsonPath("$.data.memberName").value("陈志远"))
                .andExpect(jsonPath("$.data.passType").value("季卡"))
                .andExpect(jsonPath("$.data.validTo").value("2026-12-31"))
                .andExpect(jsonPath("$.data.checks[0]").value("会员身份：陈志远（银卡）"));
    }

    @Test
    void boardRejectionReturnsExactReasonForPage() throws Exception {
        when(rideService.board(eq(1L), eq(150), eq(30), eq(2L)))
                .thenThrow(new BizException("会员「陈志远」的季卡已过期，有效期为 2026-04-01 至 2026-06-30"));

        mockMvc.perform(post("/api/rides/1/board")
                        .param("heightCm", "150").param("age", "30").param("memberId", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value("会员「陈志远」的季卡已过期，有效期为 2026-04-01 至 2026-06-30"));
    }

    @Test
    void walkInBoardWithoutMemberStillWorks() throws Exception {
        when(rideService.board(eq(1L), eq(150), eq(30), isNull()))
                .thenReturn(new BoardResult(ride(), null, null, List.of(
                        "游客身份：散客（未选择会员，按设施状态/身高/年龄核验）",
                        "设施状态：开放",
                        "身高：150cm ≥ 最低要求 130cm",
                        "年龄：30 岁 ≤ 上限 55 岁")));

        mockMvc.perform(post("/api/rides/1/board").param("heightCm", "150").param("age", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.rideName").value("极速过山车"))
                .andExpect(jsonPath("$.data.checks[0]").value("游客身份：散客（未选择会员，按设施状态/身高/年龄核验）"));
    }
}
