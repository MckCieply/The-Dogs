package com.thedogs.modules.dogs;

import com.thedogs.common.PageResponse;
import com.thedogs.modules.clients.Client;
import com.thedogs.modules.clients.ClientRepository;
import com.thedogs.modules.dogs.dto.DogDto;
import com.thedogs.modules.dogs.dto.DogRequest;
import com.thedogs.modules.dogs.mapper.DogMapper;
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
public class DogService {

  private final DogRepository dogRepository;
  private final ClientRepository clientRepository;
  private final DogMapper dogMapper;

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  public PageResponse<DogDto> listDogs(UUID trainerId, int limit) {
    List<Dog> dogs = dogRepository.findByTrainerId(trainerId, PageRequest.of(0, limit + 1));
    boolean hasMore = dogs.size() > limit;
    List<Dog> page = hasMore ? dogs.subList(0, limit) : dogs;
    String cursor = hasMore ? page.get(page.size() - 1).getId().toString() : null;
    return PageResponse.of(page.stream().map(dogMapper::toDto).toList(), cursor);
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  public DogDto getDog(UUID dogId, UUID trainerId) {
    Dog dog =
        dogRepository
            .findByIdAndTrainerId(dogId, trainerId)
            .orElseThrow(() -> new EntityNotFoundException("Dog not found: " + dogId));
    return dogMapper.toDto(dog);
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  @Transactional
  public DogDto createDog(DogRequest request, UUID trainerId) {
    Client client =
        clientRepository
            .findByIdAndTrainerId(request.clientId(), trainerId)
            .orElseThrow(
                () -> new EntityNotFoundException("Client not found: " + request.clientId()));
    Dog dog = dogMapper.toEntity(request);
    dog.setClient(client);
    return dogMapper.toDto(dogRepository.save(dog));
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  @Transactional
  public DogDto updateDog(UUID dogId, DogRequest request, UUID trainerId) {
    Dog dog =
        dogRepository
            .findByIdAndTrainerId(dogId, trainerId)
            .orElseThrow(() -> new EntityNotFoundException("Dog not found: " + dogId));
    dogMapper.updateEntity(request, dog);
    return dogMapper.toDto(dogRepository.save(dog));
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  @Transactional
  public void archiveDog(UUID dogId, UUID trainerId) {
    Dog dog =
        dogRepository
            .findByIdAndTrainerId(dogId, trainerId)
            .orElseThrow(() -> new EntityNotFoundException("Dog not found: " + dogId));
    dog.setArchived(true);
    dogRepository.save(dog);
  }
}
