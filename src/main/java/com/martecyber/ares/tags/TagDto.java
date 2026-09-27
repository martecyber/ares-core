package com.martecyber.ares.tags;

public record TagDto(Long id, Long organizationId, String name, String color) {
    public static TagDto from(Tag t) {
        return new TagDto(t.getId(), t.getOrganizationId(), t.getName(), t.getColor());
    }
}
