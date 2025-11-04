package groom.backend.interfaces.raffle.persistence.Entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "raffle_ticket_counters")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RaffleTicketCounterJpaEntity {
    @Id
    private Long raffleId;
    private Long currentValue;

}
