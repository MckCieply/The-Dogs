package com.thedogs.common;

import jakarta.persistence.EntityNotFoundException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 7807 ProblemDetail error responses for all REST controllers. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final String BASE_TYPE = "https://api.thedogs.app/problems/";

  @ExceptionHandler(EntityNotFoundException.class)
  public ProblemDetail handleNotFound(EntityNotFoundException ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(BASE_TYPE + "not-found"));
    problem.setTitle("Not Found");
    // Static detail prevents leaking resource identifiers (UUIDs, emails) in API responses.
    problem.setDetail("The requested resource does not exist or is not visible to the caller.");
    problem.setProperty("errors", List.of(Map.of("code", "not_found")));
    return problem;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(BASE_TYPE + "validation"));
    problem.setTitle("Validation Failed");
    problem.setDetail("One or more fields are invalid");

    var errors =
        ex.getBindingResult().getAllErrors().stream()
            .map(
                error -> {
                  if (error instanceof FieldError fe) {
                    String code = resolveValidationCode(fe);
                    return Map.of("code", code, "field", fe.getField());
                  }
                  return Map.of("code", "field_invalid_format");
                })
            .toList();
    problem.setProperty("errors", errors);
    return problem;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(BASE_TYPE + "bad-request"));
    problem.setTitle("Bad Request");
    problem.setDetail(ex.getMessage());
    problem.setProperty("errors", List.of(Map.of("code", "field_invalid_format")));
    return problem;
  }

  @ExceptionHandler(IllegalStateException.class)
  public ProblemDetail handleConflict(IllegalStateException ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(BASE_TYPE + "conflict"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    problem.setProperty("errors", List.of(Map.of("code", "conflict")));
    return problem;
  }

  @ExceptionHandler(BadCredentialsException.class)
  public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
    problem.setType(URI.create(BASE_TYPE + "unauthorized"));
    problem.setTitle("Unauthorized");
    problem.setDetail("Invalid credentials");
    problem.setProperty("errors", List.of(Map.of("code", "unauthorized")));
    return problem;
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
    problem.setType(URI.create(BASE_TYPE + "forbidden"));
    problem.setTitle("Forbidden");
    problem.setDetail("You do not have permission to access this resource");
    problem.setProperty("errors", List.of(Map.of("code", "forbidden")));
    return problem;
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleGeneric(Exception ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    problem.setType(URI.create(BASE_TYPE + "internal"));
    problem.setTitle("Internal Server Error");
    problem.setDetail("An unexpected error occurred");
    return problem;
  }

  private String resolveValidationCode(FieldError fe) {
    String msg = fe.getDefaultMessage();
    if (msg == null) return "field_invalid_format";
    if (msg.contains("must not be blank") || msg.contains("must not be null"))
      return "field_required";
    if (msg.contains("size must be")) {
      // Hibernate Validator emits "size must be between <min> and <max>".
      // Inspect the rejected value length vs the constraint min to distinguish directions.
      Object rejected = fe.getRejectedValue();
      if (rejected != null) {
        int actualLen = rejected.toString().length();
        // Extract min from the constraint arguments: args[1] is min, args[2] is max
        Object[] args = fe.getArguments();
        if (args != null && args.length >= 3) {
          try {
            int min = Integer.parseInt(args[1].toString());
            if (actualLen < min) return "field_too_short";
          } catch (NumberFormatException ignored) {
            // Fall through to field_too_long
          }
        }
      }
      return "field_too_long";
    }
    return "field_invalid_format";
  }
}
