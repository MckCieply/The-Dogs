package com.thedogs.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test for AC-8 of AUTH-01: running Flyway migrations twice against the same schema is
 * a no-op (idempotent). The first invocation is performed by Spring Boot on application startup;
 * this test calls {@link Flyway#migrate()} a second time and asserts that no exception is thrown
 * and zero new migrations are applied.
 *
 * <p>Uses the TC JDBC URL from application-test.yml (Docker required). Named *IT so Maven Failsafe
 * picks it up.
 *
 * <p>Note: {@code @Transactional} is intentionally omitted here. Flyway manages its own
 * transactions internally and must be able to commit the schema history table update. Wrapping the
 * call in an outer test transaction would cause a deadlock on the {@code flyway_schema_history}
 * advisory lock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FlywayIdempotencyIT {

  @Autowired private Flyway flyway;

  // ---------------------------------------------------------------------------
  // AC-8: calling migrate() twice does not throw and applies 0 migrations on
  //       the second call (all migrations already present in schema history)
  // ---------------------------------------------------------------------------

  @Test
  void migrate_calledTwice_doesNotThrow() {
    // Spring Boot already called migrate() once during context startup.
    // The second call here must be a no-op without raising any exception.
    assertThatCode(() -> flyway.migrate()).doesNotThrowAnyException();
  }

  @Test
  void migrate_calledTwice_appliesZeroMigrationsOnSecondCall() {
    // Spring Boot already called migrate() once during context startup.
    // The result of the second call must report zero migrations executed.
    MigrateResult result = flyway.migrate();

    assertThat(result.migrationsExecuted)
        .as("Second call to migrate() must apply zero new migrations (idempotency)")
        .isEqualTo(0);
  }
}
