package com.thedogs.modules.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID> {

  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);

  /**
   * Case-insensitive duplicate check for registration (AUTH-04). Matches the semantics of the
   * unique index on lower(email) from V4 so the pre-check and the constraint agree.
   */
  boolean existsByEmailIgnoreCase(String email);

  /**
   * Serialises registrations behind a transaction-scoped PostgreSQL advisory lock so the
   * first-user-becomes-admin bootstrap (AUTH-04, AC-8) cannot race: two concurrent registrations on
   * an empty table would otherwise both read count()==0 and both become admin. Released
   * automatically at commit/rollback. The constant is an arbitrary application-unique key.
   */
  @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(7263847101)", nativeQuery = true)
  Integer acquireRegistrationLock();
}
