package groom.backend.interfaces.raffle.persistence.Entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "raffle_tickets",
        uniqueConstraints = @UniqueConstraint(columnNames = {"raffle_id", "ticket_number"}))
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
    private Long userId;
    private LocalDateTime createdAt;

    @ManyToOne
    @JoinColumn(name = "raffle_id")
    private RaffleJpaEntity raffle;

}
