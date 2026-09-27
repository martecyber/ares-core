package com.martecyber.ares.detectors;

import com.martecyber.ares.common.NotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class DetectorService {

    private final DetectorToolRepository toolRepo;
    private final DetectorPluginRepository pluginRepo;

    public DetectorService(DetectorToolRepository toolRepo, DetectorPluginRepository pluginRepo) {
        this.toolRepo = toolRepo;
        this.pluginRepo = pluginRepo;
    }

    public Page<DetectorTool> listTools(int page, int size) {
        return toolRepo.findAll(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
    }

    public DetectorTool getTool(Long id) {
        return toolRepo.findById(id).orElseThrow(() -> NotFoundException.of("detector_tool", id));
    }

    @Transactional
    public DetectorTool createTool(String name, String description) {
        DetectorTool t = new DetectorTool();
        t.setName(name);
        t.setDescription(description);
        return toolRepo.save(t);
    }

    @Transactional
    public void deleteTool(Long id) {
        if (!toolRepo.existsById(id)) throw NotFoundException.of("detector_tool", id);
        toolRepo.deleteById(id);
    }

    public Page<DetectorPlugin> listPlugins(Long toolId, int page, int size) {
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        return pluginRepo.findByToolId(toolId, p);
    }

    @Transactional
    public DetectorPlugin createPlugin(Long toolId, String code) {
        if (!toolRepo.existsById(toolId)) throw NotFoundException.of("detector_tool", toolId);
        DetectorPlugin dp = new DetectorPlugin();
        dp.setToolId(toolId);
        dp.setCode(code);
        return pluginRepo.save(dp);
    }

    @Transactional
    public void deletePlugin(Long id) {
        if (!pluginRepo.existsById(id)) throw NotFoundException.of("detector_plugin", id);
        pluginRepo.deleteById(id);
    }
}
