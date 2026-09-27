package com.martecyber.ares.projects.rules.dto;

import java.util.Map;

public record UpdateProjectRuleRequest(
    Boolean enabled,
    String note,
    Map<String, Object> config
) {}
