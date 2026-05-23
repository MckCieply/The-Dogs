package com.thedogs.modules.notes;

import com.thedogs.common.PageResponse;
import com.thedogs.modules.notes.dto.NoteDto;
import com.thedogs.modules.notes.dto.NoteRequest;
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
@RequestMapping("/api/v1/dogs/{dogId}/notes")
@RequiredArgsConstructor
public class NoteController {

  private final NoteService noteService;

  @GetMapping
  public ResponseEntity<PageResponse<NoteDto>> list(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID dogId,
      @RequestParam(defaultValue = "20") int limit) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.ok(noteService.listNotes(dogId, trainerId, limit));
  }

  @GetMapping("/{noteId}")
  public ResponseEntity<NoteDto> get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID dogId, @PathVariable UUID noteId) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.ok(noteService.getNote(dogId, noteId, trainerId));
  }

  @PostMapping
  public ResponseEntity<NoteDto> create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID dogId,
      @Valid @RequestBody NoteRequest request) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(noteService.createNote(dogId, request, trainerId));
  }

  @PutMapping("/{noteId}")
  public ResponseEntity<NoteDto> update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID dogId,
      @PathVariable UUID noteId,
      @Valid @RequestBody NoteRequest request) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.ok(noteService.updateNote(dogId, noteId, request, trainerId));
  }

  @DeleteMapping("/{noteId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID dogId, @PathVariable UUID noteId) {
    UUID trainerId = UUID.fromString(jwt.getSubject());
    noteService.deleteNote(dogId, noteId, trainerId);
    return ResponseEntity.noContent().build();
  }
}
