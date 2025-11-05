package groom.backend.interfaces.raffle.persistence.repository.jpa;

import groom.backend.domain.raffle.entity.RaffleWinner;
import groom.backend.interfaces.raffle.dto.notification.RaffleWinnerNotification;
import groom.backend.domain.raffle.repository.RaffleWinnerRepository;
import groom.backend.interfaces.raffle.dto.request.RaffleDrawCondition;
import groom.backend.interfaces.raffle.persistence.Entity.RaffleTicketJpaEntity;
import groom.backend.interfaces.raffle.persistence.Entity.RaffleWinnerJpaEntity;
import groom.backend.interfaces.raffle.persistence.repository.springData.SpringDataRaffleWinnerRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JpaRaffleWinnerRepository implements RaffleWinnerRepository {

    private final SpringDataRaffleWinnerRepository winnerRepository;

    public JpaRaffleWinnerRepository(SpringDataRaffleWinnerRepository winnerRepository) {
        this.winnerRepository = winnerRepository;
    }

    @Override
    public int pickWinnersNative(RaffleDrawCondition condition) {
        return winnerRepository.pickWinnersNative(condition);
    }

    @Override
    public List<RaffleWinnerNotification> findNotificationsByRaffleId(Long raffleId) {
        return winnerRepository.findNotificationsByRaffleId(raffleId);
    }

    @Override
    public int countWinnerByRaffleId(Long raffleId) {
        return winnerRepository.countByRaffleTicket_Raffle_RaffleId(raffleId);
    }

    @Override
    public RaffleWinner save(RaffleWinner raffleWinner) {
        RaffleWinnerJpaEntity saved = winnerRepository.save(toEntity(raffleWinner));
        return toDomain(saved);
    }

    private RaffleWinner toDomain(RaffleWinnerJpaEntity e) {
        return new RaffleWinner(
                e.getRaffleWinnerId(),
                e.getRaffleTicket().getRaffleTicketId(),
                e.getStatus(),
                e.getRank(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    private RaffleWinnerJpaEntity toEntity(RaffleWinner raffleWinner) {
        RaffleTicketJpaEntity ref = new RaffleTicketJpaEntity();
        ref.setRaffleTicketId(raffleWinner.getRaffleTicketId());

        return RaffleWinnerJpaEntity.builder()
                .raffleWinnerId(raffleWinner.getRaffleWinnerId())
                .raffleTicket(ref)
                .status(raffleWinner.getStatus())
                .rank(raffleWinner.getRank())
                .createdAt(raffleWinner.getCreatedAt())
                .updatedAt(raffleWinner.getUpdatedAt())
                .build();
    }
}
