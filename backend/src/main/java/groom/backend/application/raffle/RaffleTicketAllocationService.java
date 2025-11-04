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
     * 주어진 raffleId에 대해 다음 티켓 번호를 할당하고 반환한다.
     *
     * 동작:
     * 1. raffleId로 카운터 엔티티를 조회한다(포르업데이트 조회를 통해 동시성 제어).
     * 2. 카운터가 없으면 초기값 0으로 새 엔티티를 생성한다.
     * 3. 현재 값에 1을 더해 다음 티켓 번호를 계산한다.
     * 4. 증가된 값을 엔티티에 설정하고 저장한다.
     * 5. 계산된 다음 번호를 반환한다.
     *
     * @param raffleId 티켓을 할당할 래플 식별자
     * @return 할당된 다음 티켓 번호
     */
    @Transactional
    public Long allocateNextTicketNumber(Long raffleId) {
        RaffleTicketCounterJpaEntity counter = counterRepo.findFirstByRaffleIdForUpdate(raffleId)
                .orElseGet(() -> new RaffleTicketCounterJpaEntity(raffleId, 0L));
        long next = counter.getCurrentValue() + 1;
        counter.setCurrentValue(next);
        counterRepo.save(counter);
        return next;
    }

}
