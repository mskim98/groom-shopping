package groom.backend.domain.raffle.repository;

import groom.backend.domain.raffle.entity.RaffleTicket;

public interface RaffleTicketRepository {
    RaffleTicket save(RaffleTicket raffle);

    // 특정 래플에 대해 총 사용자 수 추출
    int countDistinctUserByRaffleId(Long raffleId);

    // 특정 래플과 사용자에 대한 티켓 수를 반환
    int countByRaffleIdAndUserId(Long raffleId, Long userId);

}
