package groom.backend.application.raffle;

import groom.backend.domain.auth.entity.User;
import groom.backend.domain.raffle.criteria.RaffleSearchCriteria;
import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.enums.RaffleStatus;
import groom.backend.domain.raffle.repository.RaffleRepository;
import groom.backend.interfaces.raffle.dto.request.RaffleRequest;
import groom.backend.interfaces.raffle.dto.response.RaffleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RaffleApplicationService {

    private final RaffleRepository raffleRepository;
    private final RaffleValidationService raffleValidationService;

    public Page<RaffleResponse> searchRaffles(RaffleSearchCriteria cond, Pageable pageable) {
        Page<Raffle> page = raffleRepository.search(cond, pageable);
        return page.map(RaffleResponse::from);
    }

    public RaffleResponse getRaffleDetails(Long raffleId) {
        Raffle raffle = raffleRepository.findById(raffleId)
                .orElseThrow(() -> new IllegalStateException("해당 ID의 추첨이 존재하지 않습니다."));
        return RaffleResponse.from(raffle);
    }

    @Transactional
    public RaffleResponse createRaffle(User user, RaffleRequest request) {
        // 권한 검증
        raffleValidationService.ensureAdmin(user);
        // 요청 날짜 검증
        raffleValidationService.validateDateRaffleRequest(request);
        // TODO : 추첨 상품, 증정 상품 존재 여부 조회

        // 추첨 상품 중복 검사
        raffleValidationService.ensureUniqueRaffleProductId(request.getRaffleProductId());
        raffleValidationService.normalizeStatus(request);

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
                request.getStatus(),
                null,
                null
        );

        Raffle saved = raffleRepository.save(raffle);

        return RaffleResponse.from(saved);
    }

    @Transactional
    public RaffleResponse updateRaffle(User user, Long raffleId, RaffleRequest request) {

        raffleValidationService.ensureAdmin(user);

        Raffle raffle = raffleRepository.findById(raffleId)
                .orElseThrow(() -> new IllegalStateException("해당 ID의 추첨이 존재하지 않습니다."));

        raffleValidationService.validateDateRaffleRequestForUpdate(raffle, request);

        if(raffle.getStatus() != RaffleStatus.DRAFT) {
            throw new IllegalStateException("진행중이거나 종료된 추첨은 수정할 수 없습니다.");
        }

        raffleValidationService.ensureUniqueRaffleProductIdForUpdate(raffle.getRaffleId(), request.getRaffleProductId());

        raffle.updateRaffle(request);

        Raffle saved = raffleRepository.save(raffle);

        return RaffleResponse.from(saved);
    }

    @Transactional
    public void deleteRaffle(User user, Long raffleId) {

        raffleValidationService.ensureAdmin(user);

        Raffle raffle = raffleRepository.findById(raffleId)
                .orElseThrow(() -> new IllegalStateException("해당 ID의 추첨이 존재하지 않습니다."));

        if(raffle.getStatus() != RaffleStatus.DRAFT) {
            throw new IllegalStateException("진행중이거나 종료된 추첨은 삭제할 수 없습니다.");
        }

        raffleRepository.deleteById(raffle.getRaffleId());
    }

    // 장바구니에서 결제 완료 후, 해당 상품이 속한 추첨 엔티티(Raffle)를 조회
    @Transactional
    public Raffle findByRaffleProductId(String raffleProductId) {
        return raffleRepository.findByRaffleProductId(raffleProductId)
                .orElseThrow(() -> new IllegalStateException("해당 상품으로 등록된 추첨이 존재하지 않습니다."));
    }



}
