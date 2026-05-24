package com.thedogs.modules.notes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoteRepository extends JpaRepository<Note, UUID> {

  @Query(
      "SELECT n FROM Note n WHERE n.dog.id = :dogId AND n.trainer.id = :trainerId"
          + " ORDER BY n.createdAt DESC")
  List<Note> findByDogIdAndTrainerId(
      @Param("dogId") UUID dogId, @Param("trainerId") UUID trainerId, Pageable pageable);

  @Query(
      "SELECT n FROM Note n WHERE n.id = :noteId AND n.dog.id = :dogId"
          + " AND n.trainer.id = :trainerId")
  Optional<Note> findByIdAndDogIdAndTrainerId(
      @Param("noteId") UUID noteId, @Param("dogId") UUID dogId, @Param("trainerId") UUID trainerId);
}
