package com.martecyber.ares.plugins;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PluginRepositorySourceRepository extends JpaRepository<PluginRepositorySource, Long> {

    List<PluginRepositorySource> findAllByOrderByAddedAtAsc();

    List<PluginRepositorySource> findAllByEnabledTrue();
}
