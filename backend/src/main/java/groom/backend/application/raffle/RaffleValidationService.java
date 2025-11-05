package groom.backend.application.raffle;

import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.enums.ProductStatus;
import groom.backend.domain.product.service.ProductCommonService;
import groom.backend.domain.raffle.criteria.RaffleValidationCriteria;
import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.enums.RaffleStatus;
import groom.backend.domain.raffle.repository.RaffleRepository;
import groom.backend.domain.raffle.repository.RaffleTicketRepository;
import groom.backend.interfaces.raffle.dto.request.RaffleRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RaffleValidationService {

    private final RaffleRepository raffleRepository;
    private final RaffleTicketRepository raffleTicketRepo;
    private final ProductCommonService productCommonService;

    public Raffle findById(Long raffleId) {
        return raffleRepository.findById(raffleId)
                .orElseThrow(() -> new IllegalStateException("해당 ID의 추첨이 존재하지 않습니다."));
    }

    // RaffleProductId, WinnerProductId 존재여부 확인
    private void validateProductForRaffle(UUID productId, String fieldName, int winnerCount) {
        if (productId == null) return;
        try {
            Product product = productCommonService.findById(productId);

            // 상품 활성화 상태 및 판매 상태 확인
            if(product.getIsActive() == Boolean.FALSE || product.getStatus() != ProductStatus.AVAILABLE) {
                throw new IllegalStateException(fieldName + "에 해당하는 상품이 비활성화 상태입니다.");
            }

            // 증정 상품의 재고 확인
            if("winnerProductId".equals(fieldName)) {
                if (product.getStock() < winnerCount) {
                    throw new IllegalStateException("증정 상품의 재고가 부족합니다.");
                }
            } else {
                // 추첨 상품의 재고 확인
                if (product.getStock() < 1) {
                    throw new IllegalStateException("추첨 상품의 재고가 부족합니다.");
                }
            }
        } catch (ResponseStatusException ex) { // 상품이 존재하지 않는 경우
            throw new IllegalStateException(fieldName + "에 해당하는 상품이 존재하지 않습니다.");
        }
    }

    public void validateProductsForRaffle(RaffleValidationCriteria criteria) {
        if (criteria == null) {
            throw new IllegalStateException("상품이 존재하지 않습니다.");
        }
        validateProductForRaffle(criteria.getRaffleProductId(), "raffleProductId", criteria.getWinnerCount());
        validateProductForRaffle(criteria.getWinnerProductId(), "winnerProductId", criteria.getWinnerCount());
    }


    // 관리자 권한 검사(예외 던지기)
    public void ensureAdmin(User user) {
        if (user == null || user.getRole() != Role.ROLE_ADMIN) {
            throw new IllegalStateException("관리자만 수행할 수 있습니다.");
        }
    }

    // 응모 가능 여부 검증
    // Order 서비스에서 사용 (변경시 주의요망)
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

    // 추첨 가능 여부 검증
    public void validateRaffleForDraw(Raffle raffle) {
        if (raffle == null) {
            throw new IllegalStateException("해당 추첨이 존재하지 않습니다.");
        }
        // TODO : 추후 배치 추가 될 시 조건 엄격하게 CLOSED 만 허용하도록 변경 필요
        if (!(raffle.getStatus() == RaffleStatus.CLOSED
                || raffle.getStatus() == RaffleStatus.ACTIVE)) {
            throw new IllegalStateException("현재 진행중인 추첨이 아닙니다.");
        }

        // TODO : 추후 배치 추가 될 시 당일 여부 검사로 변경 필요
        if(LocalDateTime.now().isBefore(raffle.getRaffleDrawAt())) {
            throw new IllegalStateException("아직 추첨일이 되지 않았습니다.");
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

    // 요청 날짜 관련 검증 (수정 시)
    public void validateDateRaffleRequestForUpdate(Raffle raffle, RaffleRequest request) {
        if (request == null) {
            throw new IllegalStateException("요청이 null입니다.");
        }

        // 기존 값과 요청 값을 비교하여 최종 날짜 결정
        LocalDateTime start = raffle.getEntryStartAt();
        LocalDateTime end = raffle.getEntryEndAt();
        LocalDateTime draw = raffle.getRaffleDrawAt();

        if (request.getEntryStartAt() != null) {
            start = request.getEntryStartAt();
        }
        if (request.getEntryEndAt() != null) {
            end = request.getEntryEndAt();
        }
        if (request.getRaffleDrawAt() != null) {
            draw = request.getRaffleDrawAt();
        }

        if (end.isBefore(start)) {
            throw new IllegalStateException("응모 종료일은 응모 시작일 이후여야 합니다.");
        }
        if (draw.isBefore(end)) {
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
    // Order 서비스에서 사용 (변경시 주의요망)
    public void validateUserEntryLimit(Raffle raffle, Long userId, int additionalCount) {
        int currentCount = getEntryCount(raffle, userId);
        if((currentCount + additionalCount) > raffle.getMaxEntriesPerUser()) {
            throw new IllegalArgumentException("응모 한도를 초과하였습니다.");
        }
    }

    // 현재 응모된 수량 구하기
    // Order 서비스에서 사용 (변경시 주의요망)
    public int getEntryCount(Raffle raffle, Long userId) {
        return raffleTicketRepo.countByRaffleIdAndUserId(raffle.getRaffleId(), userId);
    }

    // 생성 시: 같은 raffleProductId가 이미 존재하면 예외
    public void ensureUniqueRaffleProductId(UUID raffleProductId) {
        if (raffleProductId == null) return;
        if (raffleRepository.existsByRaffleProductId(raffleProductId)) {
            throw new IllegalStateException("해당 상품으로 등록된 추첨이 이미 존재합니다.");
        }
    }

    // 수정 시: 동일한 raffleId인 경우는 허용, 다른 엔티티가 이미 사용 중이면 예외
    public void ensureUniqueRaffleProductIdForUpdate(Long currentRaffleId, UUID raffleProductId) {
        if (raffleProductId == null) return;
        raffleRepository.findByRaffleProductId(raffleProductId)
                .ifPresent(existing -> {
                    if (!Objects.equals(existing.getRaffleId(), currentRaffleId)) {
                        throw new IllegalStateException("해당 상품으로 등록된 추첨이 이미 존재합니다.");
                    }
                });
    }


}
