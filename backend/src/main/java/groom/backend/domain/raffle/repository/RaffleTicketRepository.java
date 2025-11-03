package groom.backend.domain.raffle.repository;

import groom.backend.domain.raffle.entity.RaffleTicket;

public interface RaffleTicketRepository {
    RaffleTicket save(RaffleTicket raffle);

    int findCountByRaffleIdAndUserId(Long raffleId, Long userId);

}
