package com.thedogs.modules.scheduler;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

  List<Appointment> findByTrainerIdOrderByStartsAtAsc(UUID trainerId, Pageable pageable);

  Optional<Appointment> findByIdAndTrainerId(UUID id, UUID trainerId);

  @Query(
      "SELECT a FROM Appointment a WHERE a.trainer.id = :trainerId"
          + " AND a.startsAt >= :from AND a.endsAt <= :to ORDER BY a.startsAt ASC")
  List<Appointment> findByTrainerIdAndDateRange(
      @Param("trainerId") UUID trainerId,
      @Param("from") Instant from,
      @Param("to") Instant to);
}
