package groom.backend.interfaces.raffle.persistence.repository.springData;

import groom.backend.interfaces.raffle.persistence.Entity.RaffleTicketJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SpringDataRaffleTicketRepository extends JpaRepository<RaffleTicketJpaEntity, Long> {
    int countByRaffle_RaffleIdAndUserId(Long raffleId, Long userId);
    @Query("select count(distinct rt.userId) from RaffleTicketJpaEntity rt where rt.raffle.raffleId = :raffleId")
    int countDistinctUserByRaffleId(Long raffleId);

}
