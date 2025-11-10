package groom.backend.application.raffle;

import groom.backend.interfaces.raffle.persistence.Entity.RaffleTicketCounterJpaEntity;
import groom.backend.interfaces.raffle.persistence.repository.springData.SpringDataRaffleTicketCounterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 각 Raffle(Entity) 별로 티켓 번호를 순차적으로 할당하는 서비스.
 *
 * - 데이터베이스에 저장된 현재 카운터 값을 읽어 다음 티켓 번호를 계산하고 증가시켜 저장한다.
 * - 동시성 문제를 방지하기 위해 카운터를 조회할 때 데이터베이스 락(예: SELECT ... FOR UPDATE)을 사용해야 한다.
 */
@Service
@RequiredArgsConstructor
public class RaffleTicketAllocationService {
    private final SpringDataRaffleTicketCounterRepository counterRepo;


    /**
     * 지정된 추첨 ID에 대해 연속된 티켓 번호 범위를 할당합니다.
     *
     * @param raffleId  티켓을 할당할 추첨의 ID
     * @param quantity  할당할 티켓 수
     * @return 할당된 티켓 번호의 시작과 끝을 포함하는 TiketRange 객체
     */
    @Transactional
    public TicketRange allocateTicketRange(Long raffleId, int quantity) {
        RaffleTicketCounterJpaEntity counter = counterRepo.findFirstByRaffleIdForUpdate(raffleId)
                .orElseGet(() -> new RaffleTicketCounterJpaEntity(raffleId, 0L));
        long start = counter.getCurrentValue() + 1;
        long end = start + quantity - 1;
        counter.setCurrentValue(end);
        counterRepo.save(counter);
        return new TicketRange(start, end);
    }

}
