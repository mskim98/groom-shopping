package groom.backend.interfaces.raffle.persistence.repository.springData;

import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.interfaces.raffle.persistence.Entity.RaffleJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataRaffleRepository extends JpaRepository<RaffleJpaEntity, Long>, JpaSpecificationExecutor<RaffleJpaEntity> {
    boolean existsByRaffleProductId(UUID raffleProductId);

    Optional<Raffle> findByRaffleProductId(UUID raffleProductId);
}
