package groom.backend.interfaces.raffle.persistence.repository.springData;

import groom.backend.interfaces.raffle.persistence.Entity.RaffleWinnerJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;


public interface SpringDataRaffleWinnerRepository extends JpaRepository<RaffleWinnerJpaEntity, Long> {
}
