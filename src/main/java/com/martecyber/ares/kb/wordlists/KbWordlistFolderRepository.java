package com.martecyber.ares.kb.wordlists;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KbWordlistFolderRepository extends JpaRepository<KbWordlistFolder, Long> {
    List<KbWordlistFolder> findByParentIdIsNull();
    List<KbWordlistFolder> findByParentId(Long parentId);
}
