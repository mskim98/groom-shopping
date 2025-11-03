package groom.backend.interfaces.raffle.persistence.Entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "raffle_tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RaffleTicketJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long raffleTicketId;
    private Long ticketNumber;
    private Long raffleId;
    private Long userId;
    private LocalDateTime createdAt;

}
