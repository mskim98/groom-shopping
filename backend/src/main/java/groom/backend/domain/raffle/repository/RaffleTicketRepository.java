package groom.backend.domain.raffle.repository;

import groom.backend.domain.raffle.entity.Participant;
import groom.backend.domain.raffle.entity.RaffleTicket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface RaffleTicketRepository {
    RaffleTicket save(RaffleTicket raffle);

    List<RaffleTicket> saveAll(List<RaffleTicket> raffleTickets);

    // 특정 래플에 대해 총 사용자 수 추출
    int countDistinctUserByRaffleId(Long raffleId);

    // 특정 래플과 사용자에 대한 티켓 수를 반환
    int countByRaffleIdAndUserId(Long raffleId, Long userId);

    // 도메인 수준의 검색 기준을 사용하도록 변경
    Page<Participant> searchParticipants(Long raffleId, String keyword, Pageable pageable);
}
