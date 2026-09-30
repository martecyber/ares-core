package com.martecyber.ares.editorimages;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EditorImageRepository extends JpaRepository<EditorImage, Long> {
    Optional<EditorImage> findByToken(UUID token);
}
