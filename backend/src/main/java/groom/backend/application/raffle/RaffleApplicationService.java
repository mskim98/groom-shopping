package groom.backend.application.raffle;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.repository.UserRepository;
import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.repository.ProductRepository;
import groom.backend.domain.raffle.criteria.RaffleSearchCriteria;
import groom.backend.domain.raffle.criteria.RaffleValidationCriteria;
import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.entity.RaffleMyEntry;
import groom.backend.domain.raffle.enums.RaffleStatus;
import groom.backend.domain.raffle.repository.RaffleRepository;
import groom.backend.domain.raffle.repository.RaffleTicketRepository;
import groom.backend.interfaces.raffle.dto.request.RaffleRequest;
import groom.backend.interfaces.raffle.dto.request.RaffleStatusUpdateRequest;
import groom.backend.interfaces.raffle.dto.request.RaffleUpdateRequest;
import groom.backend.interfaces.raffle.dto.response.MyRaffleEntryResponse;
import groom.backend.interfaces.raffle.dto.response.RaffleDetailResponse;
import groom.backend.interfaces.raffle.dto.response.RaffleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 추첨(Raffle) 관련 비즈니스 로직을 처리하는 서비스 클래스
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RaffleApplicationService {

    private final RaffleRepository raffleRepository;
    private final RaffleValidationService raffleValidationService;
    private final ProductRepository productRepository;
    private final UserRepository UserRepository;
    private final RaffleTicketRepository raffleTicketRepository;

    public Page<RaffleResponse> searchRaffles(RaffleSearchCriteria cond, Pageable pageable) {
        Page<Raffle> page = raffleRepository.search(cond, pageable);
        return page.map(RaffleResponse::from);
    }

    public RaffleDetailResponse getRaffleDetails(Long raffleId) {
        Raffle raffle = raffleValidationService.findById(raffleId);

        Product raffleProduct = productRepository.findById(raffle.getRaffleProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        Product winnerProduct = productRepository.findById(raffle.getWinnerProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));


        return RaffleDetailResponse.from(raffle, raffleProduct, winnerProduct);
    }

    @Transactional
    public RaffleResponse createRaffle(User user, RaffleRequest request) {
        // 권한 검증
        raffleValidationService.ensureAdmin(user);

        // 요청 날짜 검증 (응모일, 추첨일)
        raffleValidationService.validateDateRaffleRequest(request);

        // 상품 존재 및 상태 ,재고 검증
        RaffleValidationCriteria criteria = RaffleValidationCriteria.builder()
                .winnerProductId(request.getWinnerProductId())
                .raffleProductId(request.getRaffleProductId())
                .winnerCount(request.getWinnersCount())
                .build();
        raffleValidationService.validateProductsForRaffle(criteria);

        // 추첨 상품 중복 검사
        raffleValidationService.ensureUniqueRaffleProductId(request.getRaffleProductId());

        Raffle raffle = new Raffle(
                null,
                request.getRaffleProductId(),
                request.getWinnerProductId(),
                request.getTitle(),
                request.getDescription(),
                request.getWinnersCount(),
                request.getMaxEntriesPerUser(),
                request.getEntryStartAt(),
                request.getEntryEndAt(),
                request.getRaffleDrawAt(),
                RaffleStatus.DRAFT,
                null,
                null
        );

        Raffle saved = raffleRepository.save(raffle);

        return RaffleResponse.from(saved);
    }

    @Transactional
    public RaffleResponse updateRaffle(User user, Long raffleId, RaffleUpdateRequest request) {
        // 권한 검증
        raffleValidationService.ensureAdmin(user);

        Raffle raffle = raffleValidationService.findById(raffleId);

        // 상태 검증 - 진행중이거나 종료된 추첨은 수정 불가
        if(raffle.getStatus() != RaffleStatus.DRAFT) {
            throw new BusinessException(ErrorCode.RAFFLE_NOT_EDITABLE);
        }

        // 요청 날짜 검증 (응모일, 추첨일)
        raffleValidationService.validateDateRaffleRequestForUpdate(raffle, request);

        raffleValidationService.ensureUniqueRaffleProductIdForUpdate(raffle.getRaffleId(), request.getRaffleProductId());

        // 상품 존재 및 상태 ,재고 검증
        if(isAnyProductOrCountChanged(raffle, request)) {
            RaffleValidationCriteria criteria = RaffleValidationCriteria.builder()
                    .winnerProductId(request.getWinnerProductId() != null ? request.getWinnerProductId() : raffle.getWinnerProductId())
                    .raffleProductId(request.getRaffleProductId() != null ? request.getRaffleProductId() : raffle.getRaffleProductId())
                    .winnerCount(request.getWinnersCount() != null ? request.getWinnersCount() : raffle.getWinnersCount())
                    .build();
            raffleValidationService.validateProductsForRaffle(criteria);
        }

        raffle.updateRaffle(request);

        Raffle saved = raffleRepository.save(raffle);

        return RaffleResponse.from(saved);
    }

    @Transactional
    public RaffleResponse updateRaffleStatus(User user,  Long raffleId, RaffleStatusUpdateRequest request) {
        // 권한 검증
        raffleValidationService.ensureAdmin(user);

        Raffle raffle = raffleValidationService.findById(raffleId);

        // 상태 전환 가능 여부 검증
        // TODO : 상태 전환 검증 로직 추가 필요
        //raffleValidationService.validateRaffleStatusTransition(raffle.getStatus(), newStatus);

        raffle.updateStatus(request.getStatus());

        Raffle saved = raffleRepository.save(raffle);

        return RaffleResponse.from(saved);
    }


    public Page<MyRaffleEntryResponse> getMyEntries(Long userId, Pageable pageable) {
        User user = UserRepository.findById(userId).orElse(null);
        if (user == null) {
            return Page.empty(pageable);
        }

        Page<RaffleMyEntry> page = raffleTicketRepository.getMyEntries(user.getId(), pageable);

        return page.map(e -> MyRaffleEntryResponse.builder()
                .raffleTicketId(e.getRaffleTicketId())
                .raffleId(e.getRaffleId())
                .status(e.getStatus())
                .raffleTitle(e.getRaffleTitle())
                .entryAt(e.getEntryAt())
                .isWinner(e.getIsWinner())
                .build()
        );
    }

    // 상품 아이디 또는 당첨자 수 변경 여부 확인
    private boolean isAnyProductOrCountChanged(Raffle raffle, RaffleUpdateRequest request) {
        return isRaffleProductChanged(raffle, request)
                || isWinnerProductChanged(raffle, request)
                || isWinnersCountChanged(raffle, request);
    }

    // 개별 변경 여부 확인 메서드
    private boolean isRaffleProductChanged(Raffle raffle, RaffleUpdateRequest request) {
        return request.getRaffleProductId() != null
                && !raffle.getRaffleProductId().equals(request.getRaffleProductId());
    }

    // 개별 변경 여부 확인 메서드
    private boolean isWinnerProductChanged(Raffle raffle, RaffleUpdateRequest request) {
        return request.getWinnerProductId() != null
                && !raffle.getWinnerProductId().equals(request.getWinnerProductId());
    }

    // 개별 변경 여부 확인 메서드
    private boolean isWinnersCountChanged(Raffle raffle, RaffleUpdateRequest request) {
        return request.getWinnersCount() != null
                && raffle.getWinnersCount().equals(request.getWinnersCount());
    }

    @Transactional
    public void deleteRaffle(User user, Long raffleId) {

        raffleValidationService.ensureAdmin(user);

        Raffle raffle = raffleValidationService.findById(raffleId);

        if(raffle.getStatus() != RaffleStatus.DRAFT) {
            throw new BusinessException(ErrorCode.RAFFLE_CANNOT_BE_DELETED);
        }

        raffleRepository.deleteById(raffle.getRaffleId());
    }

    // 장바구니에서 결제 완료 후, 해당 상품이 속한 추첨 엔티티(Raffle)를 조회
    // ORDER 에 API 제공
    @Transactional
    public Raffle findByRaffleProductId(UUID raffleProductId) {
        return raffleRepository.findByRaffleProductId(raffleProductId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RAFFLE_NOT_FOUND_FOR_PRODUCT));
    }

    // 추첨 상태 업데이트 (내부용)
    @Transactional
    public void updateRaffleStatus(Raffle raffle, RaffleStatus newStatus) {
        // 최신 상태로 재조회하여 동시성 문제 완화
        Raffle current = raffleRepository.findById(raffle.getRaffleId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RAFFLE_NOT_FOUND));

        if (current.getStatus() == newStatus) {
            return; // 이미 변경된 상태면 무시
        }

        current.updateStatus(newStatus);
        raffleRepository.save(current);
    }



}
