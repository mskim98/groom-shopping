package groom.backend.interfaces.raffle.persistence.repository.springData;

import groom.backend.interfaces.raffle.persistence.Entity.RaffleTicketJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataRaffleTicketRepository extends JpaRepository<RaffleTicketJpaEntity, Long> {
    int findCountByRaffleIdAndUserId(Long raffleId, Long userId);
}
