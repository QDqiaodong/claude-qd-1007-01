package com.amusement.park.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.amusement.park.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByPhone(String phone);
}
