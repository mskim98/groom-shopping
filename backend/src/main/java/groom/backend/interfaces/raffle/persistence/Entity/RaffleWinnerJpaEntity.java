package groom.backend.interfaces.raffle.persistence.Entity;

import groom.backend.domain.raffle.enums.RaffleWinnerStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "raffle_winners")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RaffleWinnerJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long raffleWinnerId;
    @Enumerated(EnumType.STRING)
    private RaffleWinnerStatus status;
    private Long raffleTicketId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
