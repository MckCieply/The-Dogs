package com.thedogs.common;

import jakarta.persistence.EntityNotFoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 7807 ProblemDetail error responses for all REST controllers. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(EntityNotFoundException.class)
  public ProblemDetail handleNotFound(EntityNotFoundException ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create("https://thedogs.com/errors/not-found"));
    problem.setTitle("Resource Not Found");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create("https://thedogs.com/errors/validation"));
    problem.setTitle("Validation Failed");
    problem.setDetail("One or more fields are invalid");

    var fieldErrors =
        ex.getBindingResult().getAllErrors().stream()
            .map(
                error -> {
                  if (error instanceof FieldError fe) {
                    return fe.getField() + ": " + fe.getDefaultMessage();
                  }
                  return error.getDefaultMessage();
                })
            .toList();
    problem.setProperty("errors", fieldErrors);
    return problem;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create("https://thedogs.com/errors/bad-request"));
    problem.setTitle("Bad Request");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  @ExceptionHandler(IllegalStateException.class)
  public ProblemDetail handleConflict(IllegalStateException ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create("https://thedogs.com/errors/conflict"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
    problem.setType(URI.create("https://thedogs.com/errors/forbidden"));
    problem.setTitle("Forbidden");
    problem.setDetail("You do not have permission to access this resource");
    return problem;
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleGeneric(Exception ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    problem.setType(URI.create("https://thedogs.com/errors/internal"));
    problem.setTitle("Internal Server Error");
    problem.setDetail("An unexpected error occurred");
    return problem;
  }
}
