package com.amusement.park.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.amusement.park.common.BizException;
import com.amusement.park.entity.SeasonPass;
import com.amusement.park.service.SeasonPassService;

/**
 * 办卡 / 续期接口契约：日期区间非法、会员或卡片不存在时 400 + 中文原因，
 * 与会员页表单展示的提示一致。
 */
@WebMvcTest(SeasonPassController.class)
class SeasonPassControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SeasonPassService seasonPassService;

    @Test
    void issueRejectsInvalidRangeWithConsistentMessage() throws Exception {
        when(seasonPassService.issue(eq(1L), any()))
                .thenThrow(new BizException("有效期起必须早于有效期止"));

        mockMvc.perform(post("/api/season-passes").param("memberId", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validFrom\":\"2026-10-06\",\"validTo\":\"2026-10-06\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value("有效期起必须早于有效期止"));
    }

    @Test
    void issueRejectsUnknownMember() throws Exception {
        when(seasonPassService.issue(eq(99L), any()))
                .thenThrow(new BizException("会员不存在：99"));

        mockMvc.perform(post("/api/season-passes").param("memberId", "99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validFrom\":\"2026-01-01\",\"validTo\":\"2027-01-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value("会员不存在：99"));
    }

    @Test
    void renewSuccessReturnsNewDates() throws Exception {
        SeasonPass pass = new SeasonPass();
        pass.setMemberId(3L);
        pass.setPassType("年卡");
        pass.setValidFrom(LocalDate.parse("2026-10-06"));
        pass.setValidTo(LocalDate.parse("2027-10-05"));
        when(seasonPassService.renew(eq(3L), any())).thenReturn(pass);

        mockMvc.perform(put("/api/season-passes/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validFrom\":\"2026-10-06\",\"validTo\":\"2027-10-05\",\"passType\":\"年卡\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.message").value("已续期"))
                .andExpect(jsonPath("$.data.validFrom").value("2026-10-06"))
                .andExpect(jsonPath("$.data.validTo").value("2027-10-05"));
    }

    @Test
    void renewRejectsMissingCard() throws Exception {
        when(seasonPassService.renew(eq(7L), any()))
                .thenThrow(new BizException("未找到该会员的年卡/季卡，无法续期：7"));

        mockMvc.perform(put("/api/season-passes/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validFrom\":\"2026-10-06\",\"validTo\":\"2027-10-05\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value("未找到该会员的年卡/季卡，无法续期：7"));
    }
}
