package com.telerad.auth;

import com.telerad.common.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByEmailIgnoreCase(String email);
    List<UserAccount> findByRole(Role role);
    List<UserAccount> findByTenantId(UUID tenantId);
}
