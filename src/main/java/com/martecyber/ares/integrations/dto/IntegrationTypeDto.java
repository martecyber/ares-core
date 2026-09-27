package com.martecyber.ares.integrations.dto;

import com.martecyber.ares.workflows.integrations.IntegrationActionDescriptor;

import java.util.List;

/** One entry of {@code GET /api/v1/integrations/types} — every currently-registered {@code
 *  IntegrationActionHandler} type, built-in or plugin-provided. {@code icon} is null for a
 *  built-in type (the frontend's own curated static icon applies instead) and the plugin's
 *  {@code plugin.json}-configured icon for a plugin-provided one — see {@code
 *  IntegrationController#types}. */
public record IntegrationTypeDto(String type, String label, List<IntegrationActionDescriptor> capabilities,
                                  String icon, String iconLight) {}
