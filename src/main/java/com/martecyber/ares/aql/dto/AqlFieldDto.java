package com.martecyber.ares.aql.dto;

import java.util.List;

public record AqlFieldDto(String name, String type, String kind, List<String> operators, List<String> allowedValues) {
}
