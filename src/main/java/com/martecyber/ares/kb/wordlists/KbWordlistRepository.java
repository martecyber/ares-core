package com.martecyber.ares.kb.wordlists;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface KbWordlistRepository extends JpaRepository<KbWordlist, Long> {
    List<KbWordlist> findByFolderIdIsNull();
    List<KbWordlist> findByFolderId(Long folderId);
    Page<KbWordlist> findByFolderIdIsNull(Pageable pageable);
    Page<KbWordlist> findByFolderId(Long folderId, Pageable pageable);
    Page<KbWordlist> findByFolderIdIsNullAndNameContainsIgnoreCase(String name, Pageable pageable);
    Page<KbWordlist> findByFolderIdAndNameContainsIgnoreCase(Long folderId, String name, Pageable pageable);
    Page<KbWordlist> findByNameContainsIgnoreCase(String name, Pageable pageable);
    List<KbWordlist> findBySourceRepoId(Long sourceRepoId);
    java.util.Optional<KbWordlist> findBySourceRepoIdAndSourceRepoPath(Long sourceRepoId, String sourceRepoPath);
    @Modifying
    @Transactional
    void deleteBySourceRepoIdAndSourceRepoPathNotIn(Long sourceRepoId, java.util.Collection<String> keepPaths);
}
