package com.martecyber.ares.aql.dto;

import java.util.List;

/** {@code groupable}: usable as a dashboard chart's "Group by" field. */
public record AqlFieldDto(String name, String type, String kind, List<String> operators, List<String> allowedValues,
                          boolean groupable) {
}
