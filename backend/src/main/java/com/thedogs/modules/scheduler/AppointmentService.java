package com.thedogs.modules.scheduler;

import com.thedogs.common.PageResponse;
import com.thedogs.modules.clients.Client;
import com.thedogs.modules.clients.ClientRepository;
import com.thedogs.modules.scheduler.dto.AppointmentDto;
import com.thedogs.modules.scheduler.dto.AppointmentRequest;
import com.thedogs.modules.scheduler.mapper.AppointmentMapper;
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
public class AppointmentService {

  private final AppointmentRepository appointmentRepository;
  private final ClientRepository clientRepository;
  private final UserRepository userRepository;
  private final AppointmentMapper appointmentMapper;

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  public PageResponse<AppointmentDto> listAppointments(UUID trainerId, int limit) {
    List<Appointment> appointments =
        appointmentRepository.findByTrainerIdOrderByStartsAtAsc(
            trainerId, PageRequest.of(0, limit + 1));
    boolean hasMore = appointments.size() > limit;
    List<Appointment> page = hasMore ? appointments.subList(0, limit) : appointments;
    String cursor = hasMore ? page.get(page.size() - 1).getId().toString() : null;
    return PageResponse.of(page.stream().map(appointmentMapper::toDto).toList(), cursor);
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  public AppointmentDto getAppointment(UUID appointmentId, UUID trainerId) {
    Appointment appointment =
        appointmentRepository
            .findByIdAndTrainerId(appointmentId, trainerId)
            .orElseThrow(
                () -> new EntityNotFoundException("Appointment not found: " + appointmentId));
    return appointmentMapper.toDto(appointment);
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  @Transactional
  public AppointmentDto createAppointment(AppointmentRequest request, UUID trainerId) {
    User trainer =
        userRepository
            .findById(trainerId)
            .orElseThrow(() -> new EntityNotFoundException("Trainer not found: " + trainerId));
    Client client =
        clientRepository
            .findByIdAndTrainerId(request.clientId(), trainerId)
            .orElseThrow(
                () -> new EntityNotFoundException("Client not found: " + request.clientId()));

    Appointment appointment = appointmentMapper.toEntity(request);
    appointment.setTrainer(trainer);
    appointment.setClient(client);
    return appointmentMapper.toDto(appointmentRepository.save(appointment));
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  @Transactional
  public AppointmentDto updateAppointment(
      UUID appointmentId, AppointmentRequest request, UUID trainerId) {
    Appointment appointment =
        appointmentRepository
            .findByIdAndTrainerId(appointmentId, trainerId)
            .orElseThrow(
                () -> new EntityNotFoundException("Appointment not found: " + appointmentId));
    appointmentMapper.updateEntity(request, appointment);
    return appointmentMapper.toDto(appointmentRepository.save(appointment));
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  @Transactional
  public void deleteAppointment(UUID appointmentId, UUID trainerId) {
    Appointment appointment =
        appointmentRepository
            .findByIdAndTrainerId(appointmentId, trainerId)
            .orElseThrow(
                () -> new EntityNotFoundException("Appointment not found: " + appointmentId));
    appointmentRepository.delete(appointment);
  }
}
