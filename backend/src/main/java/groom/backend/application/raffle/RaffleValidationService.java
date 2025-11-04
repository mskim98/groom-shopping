package groom.backend.application.raffle;

import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.enums.RaffleStatus;
import groom.backend.interfaces.raffle.dto.request.RaffleRequest;
import groom.backend.interfaces.raffle.persistence.repository.springData.SpringDataRaffleTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RaffleValidationService {

    private final SpringDataRaffleTicketRepository raffleTicketRepo;

    // TODO : request 검증 로직 추가 필요
    // RaffleProductId, WinnerProductId 존재하는지 등

    // 관리자 권한 검사(예외 던지기)
    public void ensureAdmin(User user) {
        if (user == null || user.getRole() != Role.ROLE_ADMIN) {
            throw new IllegalStateException("관리자만 수행할 수 있습니다.");
        }
    }

    // 응모 가능 여부 검증
    public void validateRaffleForEntry(Raffle raffle) {
        if (raffle == null) {
            throw new IllegalStateException("해당 추첨이 존재하지 않습니다.");
        }
        if (raffle.getStatus() != RaffleStatus.ACTIVE) {
            throw new IllegalStateException("현재 진행중인 추첨이 아닙니다.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(raffle.getEntryStartAt())) {
            throw new IllegalStateException("응모 기간이 아직 시작되지 않았습니다.");
        }
        if (now.isAfter(raffle.getEntryEndAt())) {
            throw new IllegalStateException("응모 기간이 종료되었습니다.");
        }
    }

    // 요청 날짜 관련 검증
    public void validateDateRaffleRequest(RaffleRequest request) {
        if (request == null) {
            throw new IllegalStateException("요청이 null입니다.");
        }
        if (request.getEntryStartAt() == null || request.getEntryEndAt() == null || request.getRaffleDrawAt() == null) {
            throw new IllegalStateException("응모 시작일, 응모 종료일, 추첨일은 반드시 입력해야 합니다.");
        }
        if (request.getEntryEndAt().isBefore(request.getEntryStartAt())) {
            throw new IllegalStateException("응모 종료일은 응모 시작일 이후여야 합니다.");
        }
        if (request.getRaffleDrawAt().isBefore(request.getEntryEndAt())) {
            throw new IllegalStateException("추첨일은 응모 종료일 이후여야 합니다.");
        }
    }

    // 요청의 상태 누락 시 기본값 적용
    public void normalizeStatus(RaffleRequest request) {
        if (request != null && request.getStatus() == null) {
            request.setStatus(RaffleStatus.DRAFT);
        }
    }

    // 응모 한도 검증
    public void validateUserEntryLimit(Raffle raffle, User user, int additionalCount) {
        int currentCount = getEntryCount(raffle, user);
        if((currentCount + additionalCount) > raffle.getMaxEntriesPerUser()) {
            throw new IllegalArgumentException("응모 한도를 초과하였습니다.");
        }
    }

    // 현재 응모된 수량 구하기
    public int getEntryCount(Raffle raffle, User user) {
        return raffleTicketRepo.countByRaffleIdAndUserId(raffle.getRaffleId(), user.getId());
    }


}
