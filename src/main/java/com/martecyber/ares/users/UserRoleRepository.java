package com.martecyber.ares.users;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {
    // LEFT JOIN FETCH r.permissions: ApiTokenAuthFilter reads role.getPermissions() outside
    // any transaction/session (it's a plain servlet filter, not @Transactional) — without
    // eagerly fetching this lazy collection here, that call throws LazyInitializationException
    // and every X-API-Key request 401s. DISTINCT avoids duplicate UserRole rows from the
    // permissions join (a role with N permissions would otherwise produce N copies).
    @Query("SELECT DISTINCT ur FROM UserRole ur JOIN FETCH ur.role r LEFT JOIN FETCH r.permissions WHERE ur.userId = :userId")
    List<UserRole> findByUserId(@Param("userId") Long userId);
    @Query("SELECT ur FROM UserRole ur JOIN FETCH ur.role WHERE ur.organizationId = :orgId")
    List<UserRole> findByOrganizationId(@Param("orgId") Long orgId);
    void deleteByUserIdAndRoleIdAndOrganizationId(Long userId, Long roleId, Long organizationId);

    @Query("SELECT ur FROM UserRole ur JOIN FETCH ur.role WHERE ur.userId = :userId AND ur.organizationId = :orgId")
    List<UserRole> findByUserIdAndOrganizationId(@Param("userId") Long userId, @Param("orgId") Long orgId);

    @Query("SELECT DISTINCT ur.organizationId FROM UserRole ur WHERE ur.userId = :userId")
    List<Long> findDistinctOrganizationIdsByUserId(@Param("userId") Long userId);
}
