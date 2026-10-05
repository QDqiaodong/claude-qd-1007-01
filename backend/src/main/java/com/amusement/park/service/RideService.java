package com.amusement.park.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.amusement.park.common.BizException;
import com.amusement.park.common.Dict;
import com.amusement.park.entity.Ride;
import com.amusement.park.repository.RideRepository;

/**
 * 游乐设施业务。
 *
 * <p>核心规则：游客乘坐前（开放前校验）必须满足——
 * ① 设施处于「开放」状态；② 身高不低于最低身高限制；③ 年龄不超过上限。
 * 任一不满足则抛出中文 {@link BizException} 拒绝乘坐。</p>
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

    public RideService(RideRepository rideRepository, RideInspectionService inspectionService) {
        this.rideRepository = rideRepository;
        this.inspectionService = inspectionService;
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
     * 乘坐校验（开放前校验）。身高/年龄不满足限制则拒绝。
     *
     * <p>每次实时读取设施最新状态与限制：放行成功后立即按最新限制生效，
     * 限制变更也即时反映，无需等待任何缓存刷新。</p>
     *
     * @param heightCm 游客身高（厘米）
     * @param age      游客年龄
     */
    public Ride board(Long id, Integer heightCm, Integer age) {
        Ride ride = get(id);
        if (!"开放".equals(ride.getStatus())) {
            throw new BizException("设施「" + ride.getRideName() + "」当前为「" + ride.getStatus() + "」，未开放无法乘坐");
        }
        if (heightCm == null) {
            throw new BizException("请填写游客身高（厘米）");
        }
        if (ride.getMinHeightCm() != null && heightCm < ride.getMinHeightCm()) {
            throw new BizException("身高 " + heightCm + "cm 不足，该设施要求至少 " + ride.getMinHeightCm() + "cm");
        }
        if (age == null) {
            throw new BizException("请填写游客年龄");
        }
        if (ride.getMaxAge() != null && age > ride.getMaxAge()) {
            throw new BizException("年龄 " + age + " 岁超过限制，该设施仅限 " + ride.getMaxAge() + " 岁以下游客");
        }
        return ride;
    }
}
