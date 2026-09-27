package com.martecyber.ares.agents.pools;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentPoolRepository extends JpaRepository<AgentPool, Long> {

    List<AgentPool> findAllByOrderByNameAsc();
}
