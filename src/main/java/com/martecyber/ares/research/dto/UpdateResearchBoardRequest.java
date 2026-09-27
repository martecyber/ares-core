package com.martecyber.ares.research.dto;

/** Both fields optional — only non-null ones are applied (PATCH semantics), same convention as
 *  UpdateProjectRuleRequest. */
public record UpdateResearchBoardRequest(String title, String notes) {}
