package groom.backend.interfaces.raffle.persistence.repository.springData;

import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.enums.RaffleStatus;
import groom.backend.interfaces.raffle.persistence.Entity.RaffleJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataRaffleRepository extends JpaRepository<RaffleJpaEntity, Long>, JpaSpecificationExecutor<RaffleJpaEntity> {
    boolean existsByRaffleProductId(UUID raffleProductId);

    Optional<Raffle> findByRaffleProductId(UUID raffleProductId);

    // Ready -> Active 전환용
    Page<RaffleJpaEntity> findAllByStatusAndEntryStartAtBefore(RaffleStatus status, LocalDateTime now, Pageable pageable);

    // Active -> CLOSED 전환용
    Page<RaffleJpaEntity> findAllByStatusAndEntryEndAtBefore(RaffleStatus status, LocalDateTime now, Pageable pageable);

    // Draw 진행용
    Page<RaffleJpaEntity> findAllByStatusAndRaffleDrawAtBefore(RaffleStatus status, LocalDateTime now, Pageable pageable);



}
