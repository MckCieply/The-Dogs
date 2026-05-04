package com.thedogs.modules.clients.mapper;

import com.thedogs.modules.clients.Client;
import com.thedogs.modules.clients.dto.ClientDto;
import com.thedogs.modules.clients.dto.ClientRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface ClientMapper {

  @Mapping(target = "trainerId", source = "trainer.id")
  ClientDto toDto(Client client);

  @Mapping(target = "trainer", ignore = true)
  Client toEntity(ClientRequest request);

  @Mapping(target = "trainer", ignore = true)
  void updateEntity(ClientRequest request, @MappingTarget Client client);
}
