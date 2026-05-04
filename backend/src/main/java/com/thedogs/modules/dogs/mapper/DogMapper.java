package com.thedogs.modules.dogs.mapper;

import com.thedogs.modules.dogs.Dog;
import com.thedogs.modules.dogs.dto.DogDto;
import com.thedogs.modules.dogs.dto.DogRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface DogMapper {

  @Mapping(target = "clientId", source = "client.id")
  DogDto toDto(Dog dog);

  @Mapping(target = "client", ignore = true)
  @Mapping(target = "archived", constant = "false")
  Dog toEntity(DogRequest request);

  @Mapping(target = "client", ignore = true)
  @Mapping(target = "archived", ignore = true)
  void updateEntity(DogRequest request, @MappingTarget Dog dog);
}
