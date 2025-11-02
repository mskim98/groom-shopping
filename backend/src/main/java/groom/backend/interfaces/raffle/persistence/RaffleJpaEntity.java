package groom.backend.interfaces.raffle.persistence;

import groom.backend.domain.raffle.enums.RaffleStatus;
import jakarta.persistence.*;
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
    private String raffleProductId;
    private String winnerProductId;
    private String title;
    private String description;
    private int winnersCount;
    private int maxEntriesPerUser;
    private LocalDateTime entryStartAt;
    private LocalDateTime entryEndAt;
    private LocalDateTime raffleDrawAt;
    @Enumerated(EnumType.STRING)
    private RaffleStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;


}
