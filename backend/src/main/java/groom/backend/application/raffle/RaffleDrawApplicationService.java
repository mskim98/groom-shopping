package groom.backend.application.raffle;

import groom.backend.domain.auth.entity.User;
import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.interfaces.raffle.dto.notification.RaffleWinnerNotification;
import groom.backend.domain.raffle.enums.RaffleStatus;
import groom.backend.domain.raffle.repository.RaffleRepository;
import groom.backend.domain.raffle.repository.RaffleTicketRepository;
import groom.backend.domain.raffle.repository.RaffleWinnerRepository;
import groom.backend.interfaces.raffle.dto.request.RaffleDrawCondition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RaffleDrawApplicationService {

    private final RaffleValidationService validationService;
    private final RaffleRepository raffleRepository;
    private final RaffleWinnerRepository raffleWinnerRepo;
    private final RaffleTicketRepository ticketRepository;

    @Transactional
    public void drawRaffleWinners(User user, Long raffleId) {
        // 관리자 권한 검증
        validationService.ensureAdmin(user);

        // 추첨 존재 여부 및 상태 검증
        Raffle raffle = raffleRepository.findById(raffleId)
                            .orElseThrow(() -> new IllegalArgumentException("해당 ID의 추첨이 존재하지 않습니다."));

        // 추첨 가능 상태 검증
        validationService.validateRaffleForDraw(raffle);

        // 실제 참가자 수 조회
        int entryCount = ticketRepository.countDistinctUserByRaffleId(raffle.getRaffleId());

        if (entryCount == 0) {
            throw new IllegalStateException("응모자가 없어 당첨자 추첨을 진행할 수 없습니다.");
        }

        // 이미 추첨된 당첨자 수 조회
        int currentWinnerCount = raffleWinnerRepo.countWinnerByRaffleId(raffle.getRaffleId());
        int numberOfWinnersToDraw = (raffle.getWinnersCount() - currentWinnerCount);

        if (numberOfWinnersToDraw <= 0) {
            throw new IllegalStateException("이미 모든 당첨자가 추첨되었습니다.");
        }

        // 당첨자 추첨
        RaffleDrawCondition condition = new RaffleDrawCondition();
        condition.setRaffleId(raffle.getRaffleId());
        condition.setNumberOfWinners(numberOfWinnersToDraw);
        // Native Query를 사용한 당첨자 추첨
        int result = raffleWinnerRepo.pickWinnersNative(condition);

        // 추첨 결과 검증:
        // 당첨자가 실제 참가자 수 보다 적거나 당첨자 수 보다 많으면 오류 처리
        if(result < entryCount || result > raffle.getWinnersCount()) {
            throw new IllegalStateException("당첨자 추첨에 실패했습니다. 다시 시도해주세요.");
        }

        // 추첨 결과 검증
        // 당첨자 수가 설정된 당첨자 수보다 적으면 경고 로그 출력
        if (result < raffle.getWinnersCount()) {
            log.warn("Not enough winners were picked: expected {}, but got {}",
                    raffle.getWinnersCount(), result);
        }

        // TODO : 상품 수량 차감

        // 추첨 완료 후 추첨 상태 업데이트
        raffle.updateStatus(RaffleStatus.DRAWN);
        raffleRepository.save(raffle);
    }

    @Transactional
    public void sendRaffleWinnersNotification(Long raffleId) {

        Raffle raffle = raffleRepository.findById(raffleId)
                .orElseThrow(() -> new IllegalArgumentException("해당 ID의 추첨이 존재하지 않습니다."));

        List<RaffleWinnerNotification> notifications = raffleWinnerRepo.findNotificationsByRaffleId(raffleId);

        // TODO : 알림 전송 로직 구현
        for (RaffleWinnerNotification notification : notifications) {
            log.info("Sending notification to User ID {}: {}", notification.getUserId(), notification.getMessage());
        }

    }

}
