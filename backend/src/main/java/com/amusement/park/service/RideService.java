package com.amusement.park.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.amusement.park.common.BizException;
import com.amusement.park.common.Dict;
import com.amusement.park.dto.BoardResult;
import com.amusement.park.entity.Member;
import com.amusement.park.entity.Ride;
import com.amusement.park.entity.SeasonPass;
import com.amusement.park.repository.MemberRepository;
import com.amusement.park.repository.RideRepository;
import com.amusement.park.repository.SeasonPassRepository;

/**
 * 游乐设施业务。
 *
 * <p>核心规则：游客乘坐前（开放前校验）必须满足——
 * ① 设施处于「开放」状态；② 身高不低于最低身高限制；③ 年龄不超过上限。
 * 选择会员乘坐时，在设施限制之前还要先通过——
 * ⓪ 会员存在且持有共享主键的年卡/季卡，且当前日期落在卡片有效期起止（含首尾）内。
 * 会员等级（普通/银卡/金卡）本身不等于有效卡，未办卡、尚未生效、已过期一律拒绝。
 * 有效卡也不能绕过设施关闭/检修、身高、年龄限制，四类条件必须同时满足。
 * 任一不满足则抛出中文 {@link BizException} 拒绝乘坐，页面展示与接口返回的拒绝原因一致。</p>
 *
 * <p>检修闭环：设施进入检修自动生成安全复核批次；「检修 → 开放」不允许直接
 * 状态流转，必须走 {@link RideInspectionService#release} 放行（人员分离 + 批次
 * 未失效 + 并发只生效一次）。设施的最低身高/最高年龄/状态在批次提交后发生变化，
 * 待放行批次会被标记「已失效」（历史保留可追溯）。</p>
 */
@Service
public class RideService {

    private final RideRepository rideRepository;
    private final RideInspectionService inspectionService;
    private final MemberRepository memberRepository;
    private final SeasonPassRepository seasonPassRepository;
    private final Clock clock;

    public RideService(RideRepository rideRepository, RideInspectionService inspectionService,
                       MemberRepository memberRepository, SeasonPassRepository seasonPassRepository,
                       Clock clock) {
        this.rideRepository = rideRepository;
        this.inspectionService = inspectionService;
        this.memberRepository = memberRepository;
        this.seasonPassRepository = seasonPassRepository;
        this.clock = clock;
    }

    public List<Ride> list(String status) {
        if (status != null && !status.isBlank()) {
            return rideRepository.findByStatus(status);
        }
        return rideRepository.findAll();
    }

    public Ride get(Long id) {
        return rideRepository.findById(id)
                .orElseThrow(() -> new BizException("设施不存在：" + id));
    }

    @Transactional
    public Ride create(Ride input) {
        if (input.getRideCode() == null || input.getRideCode().isBlank()) {
            throw new BizException("设施编号不能为空");
        }
        if (!rideRepository.findByRideCode(input.getRideCode().trim()).isEmpty()) {
            throw new BizException("设施编号已存在：" + input.getRideCode());
        }
        if (input.getRideName() == null || input.getRideName().isBlank()) {
            throw new BizException("设施名称不能为空");
        }
        if (input.getMinHeightCm() != null && input.getMinHeightCm() < 0) {
            throw new BizException("最低身高不能为负数");
        }
        if (input.getMaxAge() != null && input.getMaxAge() < 0) {
            throw new BizException("最大年龄不能为负数");
        }
        Ride ride = new Ride();
        ride.setRideCode(input.getRideCode().trim());
        ride.setRideName(input.getRideName().trim());
        ride.setMinHeightCm(input.getMinHeightCm());
        ride.setMaxAge(input.getMaxAge());
        ride.setStatus(input.getStatus() == null || input.getStatus().isBlank() ? "开放" : input.getStatus());
        if (!Dict.isValidRideStatus(ride.getStatus())) {
            throw new BizException("设施状态不合法：" + ride.getStatus());
        }
        ride.setCreatedAt(LocalDateTime.now());
        Ride saved = rideRepository.saveAndFlush(ride);
        if ("检修".equals(saved.getStatus())) {
            inspectionService.ensureDraft(saved.getId());
        }
        return saved;
    }

    @Transactional
    public Ride update(Long id, Ride input) {
        Ride ride = get(id);
        boolean limitChanged = false;
        boolean statusChanged = false;
        String oldStatus = ride.getStatus();
        if (input.getRideName() != null && !input.getRideName().isBlank()) {
            ride.setRideName(input.getRideName().trim());
        }
        if (input.getMinHeightCm() != null) {
            if (input.getMinHeightCm() < 0) {
                throw new BizException("最低身高不能为负数");
            }
            if (!input.getMinHeightCm().equals(ride.getMinHeightCm())) {
                ride.setMinHeightCm(input.getMinHeightCm());
                limitChanged = true;
            }
        }
        if (input.getMaxAge() != null) {
            if (input.getMaxAge() < 0) {
                throw new BizException("最大年龄不能为负数");
            }
            if (!input.getMaxAge().equals(ride.getMaxAge())) {
                ride.setMaxAge(input.getMaxAge());
                limitChanged = true;
            }
        }
        if (input.getStatus() != null && !input.getStatus().isBlank()) {
            if (!Dict.isValidRideStatus(input.getStatus())) {
                throw new BizException("设施状态不合法：" + input.getStatus());
            }
            if ("检修".equals(oldStatus) && "开放".equals(input.getStatus())) {
                throw new BizException("检修后恢复开放须完成安全复核并由负责人放行，不能直接改状态");
            }
            if (!input.getStatus().equals(oldStatus)) {
                ride.setStatus(input.getStatus());
                statusChanged = true;
            }
        }
        Ride saved = rideRepository.saveAndFlush(ride);
        // 提交后限制/状态变化 → 待放行批次失效（历史保留），仍在检修则自动补发新批次
        if (limitChanged) {
            inspectionService.invalidatePending(id, "设施乘坐限制已变更（最低身高/最高年龄），复核内容过期");
        }
        if (statusChanged) {
            if ("检修".equals(saved.getStatus())) {
                inspectionService.ensureDraft(id);
            } else {
                inspectionService.invalidatePending(id,
                        "设施状态由「" + oldStatus + "」变更为「" + saved.getStatus() + "」，复核内容过期");
            }
        }
        return saved;
    }

    /** 状态流转。「检修 → 开放」必须走安全复核放行，此处拦截 */
    @Transactional
    public Ride transfer(Long id, String target) {
        Ride ride = get(id);
        if (!Dict.isValidRideStatus(target)) {
            throw new BizException("设施状态不合法：" + target);
        }
        if (!Dict.canTransferRide(ride.getStatus(), target)) {
            throw new BizException("不允许从「" + ride.getStatus() + "」转为「" + target + "」");
        }
        if ("检修".equals(ride.getStatus()) && "开放".equals(target)) {
            throw new BizException("检修后恢复开放须完成安全复核并由负责人放行，不能直接用状态流转");
        }
        ride.setStatus(target);
        Ride saved = rideRepository.saveAndFlush(ride);
        if ("检修".equals(target)) {
            inspectionService.ensureDraft(id);
        } else {
            inspectionService.invalidatePending(id, "设施状态变更为「" + target + "」，复核内容过期");
        }
        return saved;
    }

    /**
     * 乘坐校验（开放前校验），「会员身份 — 卡片有效期 — 设施限制」一次完成。
     *
     * <p>未选择会员（memberId 为 null）时按散客流程：仅核验设施状态、身高、年龄。
     * 选择会员后必须先通过会员/卡片核验，再逐项核验设施限制；有效卡不能绕过
     * 设施关闭/检修、身高、年龄限制，四类条件同时满足才放行。</p>
     *
     * <p>每次实时读取设施最新状态与限制、以及会员卡片的最新有效期：续期成功后
     * 下一次核验立即按新日期生效，无需重启也不依赖任何缓存。</p>
     *
     * @param heightCm 游客身高（厘米）
     * @param age      游客年龄
     * @param memberId 可选会员 id；null 表示散客
     * @return 可解释核验结果（逐项通过结论）
     */
    @Transactional(readOnly = true)
    public BoardResult board(Long id, Integer heightCm, Integer age, Long memberId) {
        Ride ride = get(id);
        List<String> checks = new ArrayList<>();
        Member member = null;
        SeasonPass pass = null;

        // ① 会员身份 + ② 卡片有效期（选择会员时）
        if (memberId != null) {
            Member m = memberRepository.findById(memberId)
                    .orElseThrow(() -> new BizException("会员不存在：" + memberId));
            // 共享主键读取卡片：会员等级（普通/银卡/金卡）不等于有效卡
            SeasonPass p = seasonPassRepository.findById(memberId)
                    .orElseThrow(() -> new BizException("会员「" + m.getName() + "」（" + m.getTier()
                            + "）未办理年卡/季卡，会员等级不能当作有效卡"));
            LocalDate today = LocalDate.now(clock);
            if (p.getValidFrom() == null || p.getValidTo() == null) {
                throw new BizException("会员「" + m.getName() + "」的" + p.getPassType()
                        + "有效期缺失，请先续期补全有效期");
            }
            if (today.isBefore(p.getValidFrom())) {
                throw new BizException("会员「" + m.getName() + "」的" + p.getPassType()
                        + "尚未生效，有效期为 " + p.getValidFrom() + " 至 " + p.getValidTo());
            }
            if (today.isAfter(p.getValidTo())) {
                throw new BizException("会员「" + m.getName() + "」的" + p.getPassType()
                        + "已过期，有效期为 " + p.getValidFrom() + " 至 " + p.getValidTo());
            }
            checks.add("会员身份：" + m.getName() + "（" + m.getTier() + "）");
            checks.add("卡片有效期：" + p.getPassType() + " " + p.getValidFrom() + " 至 "
                    + p.getValidTo() + "，今日在有效期内");
            member = m;
            pass = p;
        } else {
            checks.add("游客身份：散客（未选择会员，按设施状态/身高/年龄核验）");
        }

        // ③ 设施状态
        if (!"开放".equals(ride.getStatus())) {
            throw new BizException("设施「" + ride.getRideName() + "」当前为「" + ride.getStatus() + "」，未开放无法乘坐");
        }
        checks.add("设施状态：开放");

        // ④ 身高 / 年龄限制
        if (heightCm == null) {
            throw new BizException("请填写游客身高（厘米）");
        }
        if (ride.getMinHeightCm() != null && heightCm < ride.getMinHeightCm()) {
            throw new BizException("身高 " + heightCm + "cm 不足，该设施要求至少 " + ride.getMinHeightCm() + "cm");
        }
        checks.add(ride.getMinHeightCm() == null
                ? "身高：" + heightCm + "cm（本设施不限身高）"
                : "身高：" + heightCm + "cm ≥ 最低要求 " + ride.getMinHeightCm() + "cm");
        if (age == null) {
            throw new BizException("请填写游客年龄");
        }
        if (ride.getMaxAge() != null && age > ride.getMaxAge()) {
            throw new BizException("年龄 " + age + " 岁超过限制，该设施仅限 " + ride.getMaxAge() + " 岁以下游客");
        }
        checks.add(ride.getMaxAge() == null
                ? "年龄：" + age + " 岁（本设施不限年龄）"
                : "年龄：" + age + " 岁 ≤ 上限 " + ride.getMaxAge() + " 岁");

        return new BoardResult(ride, member, pass, checks);
    }
}
