package groom.backend.interfaces.raffle.persistence.repository.jpa;

import groom.backend.domain.raffle.entity.Participant;
import groom.backend.domain.raffle.entity.RaffleTicket;
import groom.backend.domain.raffle.repository.RaffleTicketRepository;
import groom.backend.interfaces.raffle.persistence.Entity.RaffleJpaEntity;
import groom.backend.interfaces.raffle.persistence.Entity.RaffleTicketJpaEntity;
import groom.backend.interfaces.raffle.persistence.repository.springData.SpringDataRaffleTicketRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

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
    public List<RaffleTicket> saveAll(List<RaffleTicket> tickets) {
        List<RaffleTicketJpaEntity> saved = ticketRepository.saveAll(toEntityList(tickets));
        return toDomainList(saved);
    }

    @Override
    public Page<Participant> searchParticipants(Long raffleId, String keyword, Pageable pageable) {
        return ticketRepository.searchParticipants(raffleId, keyword, pageable);
    }


    @Override
    public int countDistinctUserByRaffleId(Long raffleId) {
        return ticketRepository.countDistinctUserByRaffleId(raffleId);
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

    private List<RaffleTicketJpaEntity> toEntityList(List<RaffleTicket> raffleTickets) {
        return raffleTickets.stream()
                .map(this::toEntity)
                .toList();
    }

    private List<RaffleTicket> toDomainList(List<RaffleTicketJpaEntity> entities) {
        return entities.stream()
                .map(this::toDomain)
                .toList();
    }
}
