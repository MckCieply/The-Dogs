package com.thedogs.modules.scheduler.mapper;

import com.thedogs.modules.scheduler.Appointment;
import com.thedogs.modules.scheduler.dto.AppointmentDto;
import com.thedogs.modules.scheduler.dto.AppointmentRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface AppointmentMapper {

  @Mapping(target = "trainerId", source = "trainer.id")
  @Mapping(target = "clientId", source = "client.id")
  AppointmentDto toDto(Appointment appointment);

  @Mapping(target = "trainer", ignore = true)
  @Mapping(target = "client", ignore = true)
  @Mapping(
      target = "status",
      defaultExpression = "java(com.thedogs.modules.scheduler.AppointmentStatus.SCHEDULED)")
  Appointment toEntity(AppointmentRequest request);

  @Mapping(target = "trainer", ignore = true)
  @Mapping(target = "client", ignore = true)
  void updateEntity(AppointmentRequest request, @MappingTarget Appointment appointment);
}
