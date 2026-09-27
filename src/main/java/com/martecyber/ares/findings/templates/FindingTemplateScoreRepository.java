package com.martecyber.ares.findings.templates;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FindingTemplateScoreRepository extends JpaRepository<FindingTemplateScore, Long> {
    List<FindingTemplateScore> findByTemplateId(Long templateId);
    void deleteByTemplateId(Long templateId);
    boolean existsBySsvcLeafNodeIdIn(List<Long> ssvcLeafNodeIds);
}
