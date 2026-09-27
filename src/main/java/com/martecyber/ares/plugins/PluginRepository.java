package com.martecyber.ares.plugins;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluginRepository extends JpaRepository<Plugin, Long> {

    Optional<Plugin> findByPluginId(String pluginId);

    List<Plugin> findAllByOrderByInstalledAtAsc();
}
