package com.thedogs.modules.scheduler;

import com.thedogs.common.PageResponse;
import com.thedogs.modules.scheduler.dto.AppointmentDto;
import com.thedogs.modules.scheduler.dto.AppointmentRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/appointments")
@RequiredArgsConstructor
public class AppointmentController {

  private final AppointmentService appointmentService;

  @GetMapping
  public ResponseEntity<PageResponse<AppointmentDto>> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "20") int limit) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.ok(appointmentService.listAppointments(trainerId, limit));
  }

  @GetMapping("/{id}")
  public ResponseEntity<AppointmentDto> get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.ok(appointmentService.getAppointment(id, trainerId));
  }

  @PostMapping
  public ResponseEntity<AppointmentDto> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AppointmentRequest request) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(appointmentService.createAppointment(request, trainerId));
  }

  @PutMapping("/{id}")
  public ResponseEntity<AppointmentDto> update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody AppointmentRequest request) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.ok(appointmentService.updateAppointment(id, request, trainerId));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    appointmentService.deleteAppointment(id, trainerId);
    return ResponseEntity.noContent().build();
  }
}
