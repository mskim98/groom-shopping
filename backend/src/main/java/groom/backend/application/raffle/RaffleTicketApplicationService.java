package groom.backend.application.raffle;

import groom.backend.domain.auth.entity.User;
import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.interfaces.raffle.persistence.Entity.RaffleTicketJpaEntity;
import groom.backend.interfaces.raffle.persistence.repository.springData.SpringDataRaffleTicketCounterRepository;
import groom.backend.interfaces.raffle.persistence.repository.springData.SpringDataRaffleTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RaffleTicketApplicationService {

    private final RaffleTicketAllocationService allocationService;
    private final SpringDataRaffleTicketRepository raffleTicketRepo;
    private final SpringDataRaffleTicketCounterRepository counterRepo;


    // 현재 응모된 수량 구하기
    public int getEntriedCount(Raffle raffle, User user) {
        return raffleTicketRepo.findCountByRaffleIdAndUserId(raffle.getRaffleId(), user.getId());
    }

    // 응모 가능 한지 확인
    public boolean isPossibleToEntry(Raffle raffle, User user, int additionalCount) {
        int currentCount = getEntriedCount(raffle, user);
        return (currentCount + additionalCount)  <= raffle.getMaxEntriesPerUser();
    }

    // 결제 완료 후 호출 - 티켓 생성
    @Transactional
    public RaffleTicketJpaEntity createTicket(Raffle raffle, User user) {
        // allocateNextTicketNumber 내부에서 PESSIMISTIC_WRITE로 카운터를 잠그고 증가시킴
        Long ticketNumber = allocationService.allocateNextTicketNumber(raffle.getRaffleId());

        RaffleTicketJpaEntity ticket = RaffleTicketJpaEntity.builder()
                .raffleId(raffle.getRaffleId())
                .userId(user.getId())
                .ticketNumber(ticketNumber)
                .createdAt(LocalDateTime.now())
                .build();

        return raffleTicketRepo.save(ticket);
    }

}
