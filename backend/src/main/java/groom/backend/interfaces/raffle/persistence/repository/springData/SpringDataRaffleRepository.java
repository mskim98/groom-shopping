package groom.backend.interfaces.raffle.persistence.repository.springData;

import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.interfaces.raffle.persistence.Entity.RaffleJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SpringDataRaffleRepository extends JpaRepository<RaffleJpaEntity, Long>, JpaSpecificationExecutor<RaffleJpaEntity> {
    boolean existsByRaffleProductId(String raffleProductId);
}
