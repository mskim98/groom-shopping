package groom.backend.application.raffle;

import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.enums.RaffleStatus;
import groom.backend.domain.raffle.repository.RaffleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class RaffleScheduler {

    private final RaffleRepository raffleRepository;
    private final RaffleApplicationService raffleService; // 상태 변경 + 이벤트 발행을 담당 (트랜잭션 내부 처리)

    private static final int PAGE_SIZE = 100; // 적절한 청크 크기로 조정

    @Scheduled(cron = "30 0 0 * * *") // 매일 자정 1분에 실행
    //@Scheduled(cron = "0 14 17 * * *")
    public void changeRaffleStatusToActive() {
        LocalDateTime now = LocalDateTime.now();
        int page = 0;

        Page<Raffle> chunk;
        do {
            PageRequest pr = PageRequest.of(page, PAGE_SIZE);

            chunk = raffleRepository.findByStatusAndEntryStartAtBefore(RaffleStatus.READY, now, pr);
            chunk.getContent().forEach(raffle -> {
                try {
                    raffleService.updateRaffleStatus(raffle, RaffleStatus.ACTIVE);
                } catch (Exception e) {
                    // 로깅 및 예외 처리
                    log.error("Error activating raffle ID {}: {}", raffle.getRaffleId(), e.getMessage());
                }
            });

            page++;
        } while (!chunk.isLast());
    }

     @Scheduled(cron = "0 1 0 * * *") // 매일 자정 1분에 실행
    //@Scheduled(cron = "0 14 17 * * *")
    public void changeRaffleStatusToClosed() {
        LocalDateTime now = LocalDateTime.now();
        int page = 0;


        Page<Raffle> chunk;
        do {
            PageRequest pr = PageRequest.of(page, PAGE_SIZE);

            chunk = raffleRepository.findAllByStatusAndEntryEndAtBefore(RaffleStatus.ACTIVE, now, pr);
            chunk.getContent().forEach(raffle -> {
                try {
                    raffleService.updateRaffleStatus(raffle, RaffleStatus.CLOSED);
                } catch (Exception e) {
                    // 로깅 및 예외 처리
                    log.error("Error closed raffle ID {}: {}", raffle.getRaffleId(), e.getMessage());
                }
            });

            page++;
        } while (!chunk.isLast());
    }

}
