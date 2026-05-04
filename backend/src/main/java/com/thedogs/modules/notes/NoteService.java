package com.thedogs.modules.notes;

import com.thedogs.common.PageResponse;
import com.thedogs.modules.dogs.Dog;
import com.thedogs.modules.dogs.DogRepository;
import com.thedogs.modules.notes.dto.NoteDto;
import com.thedogs.modules.notes.dto.NoteRequest;
import com.thedogs.modules.notes.mapper.NoteMapper;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NoteService {

  private final NoteRepository noteRepository;
  private final DogRepository dogRepository;
  private final UserRepository userRepository;
  private final NoteMapper noteMapper;

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  public PageResponse<NoteDto> listNotes(UUID dogId, UUID trainerId, int limit) {
    // Verify dog belongs to trainer
    dogRepository
        .findByIdAndTrainerId(dogId, trainerId)
        .orElseThrow(() -> new EntityNotFoundException("Dog not found: " + dogId));

    List<Note> notes =
        noteRepository.findByDogIdAndTrainerId(dogId, trainerId, PageRequest.of(0, limit + 1));
    boolean hasMore = notes.size() > limit;
    List<Note> page = hasMore ? notes.subList(0, limit) : notes;
    String cursor = hasMore ? page.get(page.size() - 1).getId().toString() : null;
    return PageResponse.of(page.stream().map(noteMapper::toDto).toList(), cursor);
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  public NoteDto getNote(UUID dogId, UUID noteId, UUID trainerId) {
    Note note =
        noteRepository
            .findByIdAndDogIdAndTrainerId(noteId, dogId, trainerId)
            .orElseThrow(() -> new EntityNotFoundException("Note not found: " + noteId));
    return noteMapper.toDto(note);
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  @Transactional
  public NoteDto createNote(UUID dogId, NoteRequest request, UUID trainerId) {
    Dog dog =
        dogRepository
            .findByIdAndTrainerId(dogId, trainerId)
            .orElseThrow(() -> new EntityNotFoundException("Dog not found: " + dogId));
    User trainer =
        userRepository
            .findById(trainerId)
            .orElseThrow(() -> new EntityNotFoundException("Trainer not found: " + trainerId));

    Note note = noteMapper.toEntity(request);
    note.setDog(dog);
    note.setTrainer(trainer);
    return noteMapper.toDto(noteRepository.save(note));
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  @Transactional
  public NoteDto updateNote(UUID dogId, UUID noteId, NoteRequest request, UUID trainerId) {
    Note note =
        noteRepository
            .findByIdAndDogIdAndTrainerId(noteId, dogId, trainerId)
            .orElseThrow(() -> new EntityNotFoundException("Note not found: " + noteId));
    noteMapper.updateEntity(request, note);
    return noteMapper.toDto(noteRepository.save(note));
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  @Transactional
  public void deleteNote(UUID dogId, UUID noteId, UUID trainerId) {
    Note note =
        noteRepository
            .findByIdAndDogIdAndTrainerId(noteId, dogId, trainerId)
            .orElseThrow(() -> new EntityNotFoundException("Note not found: " + noteId));
    noteRepository.delete(note);
  }
}
