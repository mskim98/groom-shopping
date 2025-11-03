package groom.backend.application.raffle;

import groom.backend.interfaces.raffle.persistence.Entity.RaffleTicketCounterJpaEntity;
import groom.backend.interfaces.raffle.persistence.repository.springData.SpringDataRaffleTicketCounterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RaffleTicketAllocationService {
    private final SpringDataRaffleTicketCounterRepository counterRepo;

    @Transactional
    public Long allocateNextTicketNumber(Long raffleId) {
        RaffleTicketCounterJpaEntity counter = counterRepo.findByRaffleIdForUpdate(raffleId)
                .orElseGet(() -> new RaffleTicketCounterJpaEntity(raffleId, 0L));
        long next = counter.getCurrentValue() + 1;
        counter.setCurrentValue(next);
        counterRepo.save(counter);
        return next;
    }

}
