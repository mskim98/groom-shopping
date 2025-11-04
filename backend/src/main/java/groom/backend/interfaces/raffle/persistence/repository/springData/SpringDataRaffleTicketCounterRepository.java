package groom.backend.interfaces.raffle.persistence.repository.springData;

import groom.backend.interfaces.raffle.persistence.Entity.RaffleTicketCounterJpaEntity;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SpringDataRaffleTicketCounterRepository extends JpaRepository<RaffleTicketCounterJpaEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RaffleTicketCounterJpaEntity c where c.raffleId = :raffleId order by c.currentValue desc")
    Optional<RaffleTicketCounterJpaEntity> findFirstByRaffleIdForUpdate(@Param("raffleId") Long raffleId);

}
