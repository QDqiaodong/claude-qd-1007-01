package com.amusement.park.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.amusement.park.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {

    /** 悲观锁当前读：办卡时串行化同一会员的并发/重复提交，第二个请求明确拒绝而非互相回滚 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") Long id);
}
