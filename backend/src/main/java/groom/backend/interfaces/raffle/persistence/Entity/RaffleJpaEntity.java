package groom.backend.interfaces.raffle.persistence.Entity;

import groom.backend.domain.raffle.enums.RaffleStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "raffles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RaffleJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long raffleId;
    @NotBlank
    private String raffleProductId;
    @NotBlank
    private String winnerProductId;
    @NotBlank
    private String title;
    private String description;
    private int winnersCount;
    private int maxEntriesPerUser;
    private LocalDateTime entryStartAt;
    private LocalDateTime entryEndAt;
    private LocalDateTime raffleDrawAt;
    @Enumerated(EnumType.STRING)
    private RaffleStatus status;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }


}
