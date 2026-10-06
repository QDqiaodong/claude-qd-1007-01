package com.amusement.park.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 系统时钟：业务代码一律通过注入的 {@link Clock} 取「今天」，
 * 不直接调 {@code LocalDate.now()}。这样有效期边界（起止当日是否算有效）
 * 在自动化测试里可以用固定时钟精确覆盖，用例可重复、不依赖运行日期。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock appClock() {
        return Clock.systemDefaultZone();
    }
}
