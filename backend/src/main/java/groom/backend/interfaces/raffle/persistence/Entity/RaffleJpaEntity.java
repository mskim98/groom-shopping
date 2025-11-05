package groom.backend.interfaces.raffle.persistence.Entity;

import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.enums.RaffleStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

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
    private UUID raffleProductId;
    private UUID winnerProductId;
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

    public static RaffleJpaEntity from(Raffle raffle) {

        RaffleJpaEntity.RaffleJpaEntityBuilder builder = RaffleJpaEntity.builder()
                .raffleProductId(raffle.getRaffleProductId())
                .winnerProductId(raffle.getWinnerProductId())
                .title(raffle.getTitle())
                .description(raffle.getDescription())
                .winnersCount(raffle.getWinnersCount())
                .maxEntriesPerUser(raffle.getMaxEntriesPerUser())
                .entryStartAt(raffle.getEntryStartAt())
                .entryEndAt(raffle.getEntryEndAt())
                .raffleDrawAt(raffle.getRaffleDrawAt())
                .status(raffle.getStatus());

        if (raffle.getRaffleId() != null){
            builder.raffleId(raffle.getRaffleId());
        }

        return builder.build();
    }


}
