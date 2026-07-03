package com.thedogs.modules.auth;

import com.nulabinc.zxcvbn.Strength;
import com.nulabinc.zxcvbn.Zxcvbn;
import com.thedogs.modules.auth.exception.PasswordTooWeakException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Server-side password strength gate (AUTH-04). Uses zxcvbn entropy estimation instead of
 * character-class rules, per NIST SP 800-63B. The frontend meter (AUTH-07) mirrors this check for
 * UX only — this component remains the security boundary.
 *
 * <p>Reused by AUTH-05 (reset password) so both credential-setting paths enforce the same policy.
 */
@Component
public class PasswordStrengthValidator {

  /** Minimum acceptable zxcvbn score on the 0–4 scale. */
  static final int MIN_SCORE = 3;

  /**
   * The Zxcvbn instance loads its ~1 MB ranked dictionaries at construction; build it once per
   * application, not per request. The instance itself is thread-safe (measure() shares only
   * immutable dictionary state).
   */
  private final Zxcvbn zxcvbn = new Zxcvbn();

  /**
   * Measures the password and throws when the score is below {@link #MIN_SCORE}.
   *
   * @param password the candidate password (never logged)
   * @param userInputs user-specific strings (email, display name) added to the penalty dictionary
   *     so users cannot use their own identifiers as a "strong" password
   * @throws PasswordTooWeakException when the zxcvbn score is below the minimum; carries the score
   */
  public void validate(String password, List<String> userInputs) {
    Strength strength = zxcvbn.measure(password, userInputs);
    if (strength.getScore() < MIN_SCORE) {
      throw new PasswordTooWeakException(strength.getScore());
    }
  }
}
