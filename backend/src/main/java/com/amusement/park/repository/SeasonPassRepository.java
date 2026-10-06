package com.amusement.park.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.amusement.park.dto.SeasonPassView;
import com.amusement.park.entity.SeasonPass;

public interface SeasonPassRepository extends JpaRepository<SeasonPass, Long> {

    /** 悲观锁当前读：续期时串行化同一卡片的并发写入，重复提交幂等生效，不会一方成功一方回滚 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SeasonPass p where p.memberId = :memberId")
    Optional<SeasonPass> findByMemberIdForUpdate(@Param("memberId") Long memberId);

    /** 关联查询：卡片 ⋈ 会员，一次带出会员姓名与等级（卡片状态由服务层按当前日期填充） */
    @Query("select new com.amusement.park.dto.SeasonPassView("
            + "p.memberId, m.name, m.tier, p.passType, p.validFrom, p.validTo) "
            + "from SeasonPass p join p.member m order by p.memberId")
    List<SeasonPassView> findAllWithMember();
}
