package com.thedogs.modules.dogs;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DogRepository extends JpaRepository<Dog, UUID> {

  /** Find dogs belonging to a trainer (via the client relationship). */
  @Query(
      "SELECT d FROM Dog d JOIN d.client c WHERE c.trainer.id = :trainerId AND d.archived = false")
  List<Dog> findByTrainerId(@Param("trainerId") UUID trainerId, Pageable pageable);

  /** Find a specific dog, ensuring it belongs to the given trainer. */
  @Query("SELECT d FROM Dog d JOIN d.client c WHERE d.id = :dogId AND c.trainer.id = :trainerId")
  Optional<Dog> findByIdAndTrainerId(
      @Param("dogId") UUID dogId, @Param("trainerId") UUID trainerId);

  List<Dog> findByClientId(UUID clientId, Pageable pageable);

  Optional<Dog> findByIdAndClientId(UUID dogId, UUID clientId);
}
