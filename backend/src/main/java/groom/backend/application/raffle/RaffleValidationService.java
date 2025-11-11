package groom.backend.application.raffle;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.enums.ProductCategory;
import groom.backend.domain.product.model.enums.ProductStatus;
import groom.backend.domain.product.service.ProductCommonService;
import groom.backend.domain.raffle.criteria.RaffleValidationCriteria;
import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.enums.RaffleStatus;
import groom.backend.domain.raffle.repository.RaffleRepository;
import groom.backend.domain.raffle.repository.RaffleTicketRepository;
import groom.backend.interfaces.raffle.dto.request.RaffleRequest;
import groom.backend.interfaces.raffle.dto.request.RaffleUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 추첨(Raffle) 관련 검증 로직을 처리하는 서비스 클래스
 */
@Service
@RequiredArgsConstructor
public class RaffleValidationService {

    private final RaffleRepository raffleRepository;
    private final RaffleTicketRepository raffleTicketRepo;
    private final ProductCommonService productCommonService;

    public Raffle findById(Long raffleId) {
        return raffleRepository.findById(raffleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RAFFLE_NOT_FOUND));
    }

    // RaffleProductId, WinnerProductId 존재여부 확인
    private void validateProductForRaffle(UUID productId, String fieldName, int winnerCount) {
        if (productId == null) return;
        try {
            Product product = productCommonService.findById(productId);

            // 상품 활성화 상태 및 판매 상태 확인
            if (product.getIsActive() == Boolean.FALSE || product.getStatus() != ProductStatus.AVAILABLE) {
                final ErrorCode errorCode = "winnerProductId".equals(fieldName) ? ErrorCode.WINNER_PRODUCT_NOT_ACTIVE : ErrorCode.RAFFLE_PRODUCT_NOT_ACTIVE;

                throw new BusinessException(errorCode);
            }

            if ("winnerProductId".equals(fieldName)) {
                // 증정 상품의 재고 확인
                if (product.getStock() < winnerCount) {
                    throw new BusinessException(ErrorCode.INSUFFICIENT_WINNER_PRODUCT_STOCK);
                }

                if (product.getCategory() != ProductCategory.RAFFLE) {
                    throw new BusinessException(ErrorCode.INVALID_WINNER_PRODUCT_TYPE);

                }
            } else {
                // 추첨 상품의 재고 확인
                if (product.getStock() < 1) {
                    throw new BusinessException(ErrorCode.INSUFFICIENT_RAFFLE_PRODUCT_STOCK);
                }

                if (product.getCategory() != ProductCategory.TICKET) {
                    throw new BusinessException(ErrorCode.INVALID_RAFFLE_PRODUCT_TYPE);
                }
            }
        } catch (ResponseStatusException ex) { // 상품이 존재하지 않는 경우
            final ErrorCode errorCode = "winnerProductId".equals(fieldName) ? ErrorCode.WINNER_PRODUCT_NOT_FOUND : ErrorCode.RAFFLE_PRODUCT_NOT_FOUND;

            throw new BusinessException(errorCode);
        }
    }

    public void validateProductsForRaffle(RaffleValidationCriteria criteria) {
        if (criteria == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        validateProductForRaffle(criteria.getRaffleProductId(), "raffleProductId", criteria.getWinnerCount());
        validateProductForRaffle(criteria.getWinnerProductId(), "winnerProductId", criteria.getWinnerCount());
    }


    // 관리자 권한 검사(예외 던지기)
    public void ensureAdmin(User user) {
        if (user == null || user.getRole() != Role.ROLE_ADMIN) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    // 응모 가능 여부 검증
    // Order 서비스에서 사용 (변경시 주의요망)
    public void validateRaffleForEntry(Raffle raffle) {

        if (raffle == null) {
            throw new BusinessException(ErrorCode.RAFFLE_NOT_FOUND);
        }
        if (raffle.getStatus() != RaffleStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.INVALID_RAFFLE_STATUS);
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(raffle.getEntryStartAt())) {
            throw new BusinessException(ErrorCode.RAFFLE_ENTRY_NOT_STARTED);
        }
        if (now.isAfter(raffle.getEntryEndAt())) {
            throw new BusinessException(ErrorCode.RAFFLE_ENTRY_ENDED);
        }
    }

    // 추첨 가능 여부 검증
    public void validateRaffleForDraw(Raffle raffle) {
        if (raffle == null) {
            throw new BusinessException(ErrorCode.RAFFLE_NOT_FOUND);
        }
        // TODO : 추후 배치 추가 될 시 조건 엄격하게 CLOSED 만 허용하도록 변경 필요
        if (!(raffle.getStatus() == RaffleStatus.CLOSED
                || raffle.getStatus() == RaffleStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.INVALID_RAFFLE_STATUS);
        }

        // TODO : 추후 배치 추가 될 시 당일 여부 검사로 변경 필요
        if(LocalDateTime.now().isBefore(raffle.getRaffleDrawAt())) {
            throw new BusinessException(ErrorCode.RAFFLE_DRAW_NOT_STARTED);
        }
    }

    // 요청 날짜 관련 검증
    public void validateDateRaffleRequest(RaffleRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.REQUEST_IS_NULL);
        }
        if (request.getEntryStartAt() == null || request.getEntryEndAt() == null || request.getRaffleDrawAt() == null) {
            throw new BusinessException(ErrorCode.RAFFLE_REQUIRED_DATES);
        }
        if (request.getEntryEndAt().isBefore(request.getEntryStartAt())) {
            // 응모 종료일은 응모 시작일 이후여야 합니다.
            throw new BusinessException(ErrorCode.RAFFLE_END_DATE_AFTER_START_DATE);
        }
        if (request.getRaffleDrawAt().isBefore(request.getEntryEndAt())) {
            // 추첨일은 응모 종료일 이후여야 합니다.
            throw new BusinessException(ErrorCode.RAFFLE_DRAW_DATE_AFTER_END_DATE);
        }
    }

    // 요청 날짜 관련 검증 (수정 시)
    public void validateDateRaffleRequestForUpdate(Raffle raffle, RaffleUpdateRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.REQUEST_IS_NULL);
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
            throw new BusinessException(ErrorCode.RAFFLE_END_DATE_AFTER_START_DATE);
        }
        if (draw.isBefore(end)) {
            throw new BusinessException(ErrorCode.RAFFLE_DRAW_DATE_AFTER_END_DATE);
        }
    }

    // 응모 한도 검증
    // Order 서비스에서 사용 (변경시 주의요망)
    public void validateUserEntryLimit(Raffle raffle, Long userId, int additionalCount) {
        int currentCount = getEntryCount(raffle, userId);
        if ((currentCount + additionalCount) > raffle.getMaxEntriesPerUser()) {
            throw new BusinessException(ErrorCode.RAFFLE_ENTRY_LIMIT_EXCEEDED);
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
            throw new BusinessException(ErrorCode.DUPLICATE_RAFFLE_PRODUCT);
        }
    }

    // 수정 시: 동일한 raffleId인 경우는 허용, 다른 엔티티가 이미 사용 중이면 예외
    public void ensureUniqueRaffleProductIdForUpdate(Long currentRaffleId, UUID raffleProductId) {
        if (raffleProductId == null) return;
        raffleRepository.findByRaffleProductId(raffleProductId)
                .ifPresent(existing -> {
                    if (!Objects.equals(existing.getRaffleId(), currentRaffleId)) {
                        throw new BusinessException(ErrorCode.DUPLICATE_RAFFLE_PRODUCT);
                    }
                });
    }


}
