package com.thedogs.modules.clients;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientRepository extends JpaRepository<Client, UUID> {

  List<Client> findByTrainerId(UUID trainerId, Pageable pageable);

  Optional<Client> findByIdAndTrainerId(UUID id, UUID trainerId);

  long countByTrainerId(UUID trainerId);
}
