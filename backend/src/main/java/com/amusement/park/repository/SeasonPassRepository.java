package com.amusement.park.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.amusement.park.entity.SeasonPass;

public interface SeasonPassRepository extends JpaRepository<SeasonPass, Long> {
}
