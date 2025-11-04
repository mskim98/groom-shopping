package groom.backend.interfaces.raffle.persistence.repository.jpa;

import groom.backend.domain.raffle.entity.RaffleTicket;
import groom.backend.domain.raffle.repository.RaffleTicketRepository;
import groom.backend.interfaces.raffle.persistence.Entity.RaffleJpaEntity;
import groom.backend.interfaces.raffle.persistence.Entity.RaffleTicketJpaEntity;
import groom.backend.interfaces.raffle.persistence.repository.springData.SpringDataRaffleTicketRepository;
import org.springframework.stereotype.Repository;

@Repository
public class JpaRaffleTicketRepository implements RaffleTicketRepository {
    private final SpringDataRaffleTicketRepository ticketRepository;

    public JpaRaffleTicketRepository(SpringDataRaffleTicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Override
    public RaffleTicket save(RaffleTicket ticket) {
        RaffleTicketJpaEntity saved = ticketRepository.save(toEntity(ticket));
        return toDomain(saved);
    }

    @Override
    public int countByRaffleIdAndUserId(Long raffleId, Long userId) {
        return ticketRepository.countByRaffle_RaffleIdAndUserId(raffleId, userId);
    }

    private RaffleTicket toDomain(RaffleTicketJpaEntity e) {
        return new RaffleTicket(e.getRaffleTicketId(),
                e.getTicketNumber(),
                e.getRaffle().getRaffleId(),
                e.getUserId(),
                e.getCreatedAt()
        );
    }

    private RaffleTicketJpaEntity toEntity(RaffleTicket raffle) {
        RaffleJpaEntity ref = new RaffleJpaEntity();
        ref.setRaffleId(raffle.getRaffleId());

        return RaffleTicketJpaEntity.builder()
                .raffle(ref)
                .raffleTicketId(raffle.getRaffleTicketId())
                .ticketNumber(raffle.getTicketNumber())
                .userId(raffle.getUserId())
                .createdAt(raffle.getCreatedAt())
                .build();
    }
}
