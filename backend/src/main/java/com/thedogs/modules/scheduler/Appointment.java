package com.thedogs.modules.scheduler;

import com.thedogs.common.BaseEntity;
import com.thedogs.modules.clients.Client;
import com.thedogs.modules.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "appointments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class Appointment extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "trainer_id", nullable = false)
  private User trainer;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "client_id", nullable = false)
  private Client client;

  @NotNull
  @Column(nullable = false)
  private Instant startsAt;

  @NotNull
  @Column(nullable = false)
  private Instant endsAt;

  @Column private String location;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private AppointmentStatus status = AppointmentStatus.SCHEDULED;

  @Column(columnDefinition = "TEXT")
  private String notes;

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Appointment other)) return false;
    return getId() != null && getId().equals(other.getId());
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
