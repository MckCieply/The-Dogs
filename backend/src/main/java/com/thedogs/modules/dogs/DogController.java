package com.thedogs.modules.dogs;

import com.thedogs.common.PageResponse;
import com.thedogs.modules.dogs.dto.DogDto;
import com.thedogs.modules.dogs.dto.DogRequest;
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
@RequestMapping("/api/v1/dogs")
@RequiredArgsConstructor
public class DogController {

  private final DogService dogService;

  @GetMapping
  public ResponseEntity<PageResponse<DogDto>> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "20") int limit) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.ok(dogService.listDogs(trainerId, limit));
  }

  @GetMapping("/{id}")
  public ResponseEntity<DogDto> get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.ok(dogService.getDog(id, trainerId));
  }

  @PostMapping
  public ResponseEntity<DogDto> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DogRequest request) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.status(HttpStatus.CREATED).body(dogService.createDog(request, trainerId));
  }

  @PutMapping("/{id}")
  public ResponseEntity<DogDto> update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody DogRequest request) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.ok(dogService.updateDog(id, request, trainerId));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public ResponseEntity<Void> archive(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    dogService.archiveDog(id, trainerId);
    return ResponseEntity.noContent().build();
  }
}
