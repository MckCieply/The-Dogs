package com.thedogs.modules.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.thedogs.modules.auth.exception.PasswordTooWeakException;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit tests for the AUTH-04 zxcvbn strength gate. No Spring context needed. */
class PasswordStrengthValidatorTest {

  private final PasswordStrengthValidator validator = new PasswordStrengthValidator();

  @Test
  void validate_acceptsStrongRandomPassword() {
    assertThatCode(
            () -> validator.validate("kR7#vetch-Quasar93!plinth", List.of("a@b.com", "Alice")))
        .doesNotThrowAnyException();
  }

  @Test
  void validate_rejectsCommonDictionaryPassword() {
    assertThatThrownBy(() -> validator.validate("password12", List.of("a@b.com", "Alice")))
        .isInstanceOf(PasswordTooWeakException.class);
  }

  @Test
  void validate_rejectsUsersOwnEmailAsPassword() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    "trainer@thedogs.example", List.of("trainer@thedogs.example", "Trainer")))
        .isInstanceOf(PasswordTooWeakException.class);
  }

  @Test
  void validate_exceptionCarriesMeasuredScore() {
    PasswordTooWeakException ex =
        catchThrowableOfType(
            PasswordTooWeakException.class,
            () -> validator.validate("password12", List.of("a@b.com", "Alice")));
    Assertions.assertThat(ex.getScore()).isBetween(0, 2);
  }
}
