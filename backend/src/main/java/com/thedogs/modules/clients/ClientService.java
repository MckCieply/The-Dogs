package com.thedogs.modules.clients;

import com.thedogs.common.PageResponse;
import com.thedogs.modules.clients.dto.ClientDto;
import com.thedogs.modules.clients.dto.ClientRequest;
import com.thedogs.modules.clients.mapper.ClientMapper;
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
public class ClientService {

  private static final int DEFAULT_PAGE_SIZE = 20;

  private final ClientRepository clientRepository;
  private final UserRepository userRepository;
  private final ClientMapper clientMapper;

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  public PageResponse<ClientDto> listClients(UUID trainerId, int limit) {
    List<Client> clients =
        clientRepository.findByTrainerId(trainerId, PageRequest.of(0, limit + 1));
    boolean hasMore = clients.size() > limit;
    List<Client> page = hasMore ? clients.subList(0, limit) : clients;
    String cursor = hasMore ? page.get(page.size() - 1).getId().toString() : null;
    return PageResponse.of(page.stream().map(clientMapper::toDto).toList(), cursor);
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  public ClientDto getClient(UUID clientId, UUID trainerId) {
    Client client =
        clientRepository
            .findByIdAndTrainerId(clientId, trainerId)
            .orElseThrow(() -> new EntityNotFoundException("Client not found: " + clientId));
    return clientMapper.toDto(client);
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  @Transactional
  public ClientDto createClient(ClientRequest request, UUID trainerId) {
    User trainer =
        userRepository
            .findById(trainerId)
            .orElseThrow(() -> new EntityNotFoundException("Trainer not found: " + trainerId));
    Client client = clientMapper.toEntity(request);
    client.setTrainer(trainer);
    return clientMapper.toDto(clientRepository.save(client));
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  @Transactional
  public ClientDto updateClient(UUID clientId, ClientRequest request, UUID trainerId) {
    Client client =
        clientRepository
            .findByIdAndTrainerId(clientId, trainerId)
            .orElseThrow(() -> new EntityNotFoundException("Client not found: " + clientId));
    clientMapper.updateEntity(request, client);
    return clientMapper.toDto(clientRepository.save(client));
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  @Transactional
  public void deleteClient(UUID clientId, UUID trainerId) {
    Client client =
        clientRepository
            .findByIdAndTrainerId(clientId, trainerId)
            .orElseThrow(() -> new EntityNotFoundException("Client not found: " + clientId));
    clientRepository.delete(client);
  }
}
