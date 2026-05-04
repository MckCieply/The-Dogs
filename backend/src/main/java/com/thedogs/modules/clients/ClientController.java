package com.thedogs.modules.clients;

import com.thedogs.common.PageResponse;
import com.thedogs.modules.clients.dto.ClientDto;
import com.thedogs.modules.clients.dto.ClientRequest;
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
@RequestMapping("/api/v1/clients")
@RequiredArgsConstructor
public class ClientController {

  private final ClientService clientService;

  @GetMapping
  public ResponseEntity<PageResponse<ClientDto>> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "20") int limit) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.ok(clientService.listClients(trainerId, limit));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ClientDto> get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.ok(clientService.getClient(id, trainerId));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<ClientDto> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ClientRequest request) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(clientService.createClient(request, trainerId));
  }

  @PutMapping("/{id}")
  public ResponseEntity<ClientDto> update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody ClientRequest request) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.ok(clientService.updateClient(id, request, trainerId));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    clientService.deleteClient(id, trainerId);
    return ResponseEntity.noContent().build();
  }
}
