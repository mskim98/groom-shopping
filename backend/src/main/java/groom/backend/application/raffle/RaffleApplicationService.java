package groom.backend.application.raffle;

import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.enums.Role;
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

        if(isNotAdmin(user)) {
            throw new IllegalStateException("관리자만 추첨을 생성할 수 있습니다.");
        }

        validateDateRaffleRequest(request);

        if(raffleRepository.existsByRaffleProductId(request.getRaffleProductId())) {
            throw new IllegalStateException("해당 상품으로 등록된 추첨이 이미 존재합니다.");
        }
        normalizeStatus(request);

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

        if(isNotAdmin(user)) {
            throw new IllegalStateException("관리자만 추첨을 수정할 수 있습니다.");
        }

        validateDateRaffleRequest(request);

        Raffle raffle = raffleRepository.findById(raffleId)
                .orElseThrow(() -> new IllegalStateException("해당 ID의 추첨이 존재하지 않습니다."));

        if(raffle.getStatus() != RaffleStatus.DRAFT) {
            throw new IllegalStateException("진행중이거나 종료된 추첨은 수정할 수 없습니다.");
        }

        if(!raffle.getRaffleProductId().equals(request.getRaffleProductId())) {
            if(raffleRepository.existsByRaffleProductId(request.getRaffleProductId())) {
                throw new IllegalStateException("해당 상품으로 등록된 추첨이 이미 존재합니다.");
            }
        }

        raffle.updateRaffle(request);

        Raffle saved = raffleRepository.save(raffle);

        return RaffleResponse.from(saved);
    }

    @Transactional
    public void deleteRaffle(User user, Long raffleId) {

        if(isNotAdmin(user)) {
            throw new IllegalStateException("관리자만 추첨을 삭제할 수 있습니다.");
        }

        Raffle raffle = raffleRepository.findById(raffleId)
                .orElseThrow(() -> new IllegalStateException("해당 ID의 추첨이 존재하지 않습니다."));

        if(raffle.getStatus() != RaffleStatus.DRAFT) {
            throw new IllegalStateException("진행중이거나 종료된 추첨은 삭제할 수 없습니다.");
        }

        raffleRepository.deleteById(raffle.getRaffleId());
    }



    private boolean isNotAdmin(User user) {
        return user.getRole() != Role.ROLE_ADMIN;
    }

    private void validateDateRaffleRequest(RaffleRequest request) {
        // TODO : request 검증 로직 추가 필요
        // RaffleProductId, WinnerProductId 존재하는지 등

        if (request == null) {
            throw new IllegalStateException("요청이 null입니다.");
        }

        if (request.getEntryStartAt() == null || request.getEntryEndAt() == null || request.getRaffleDrawAt() == null) {
            throw new IllegalStateException("응모 시작일, 응모 종료일, 추첨일은 반드시 입력해야 합니다.");
        }

        if(request.getEntryEndAt().isBefore(request.getEntryStartAt())) {
            throw new IllegalStateException("응모 종료일은 응모 시작일 이후여야 합니다.");
        }

        if(request.getRaffleDrawAt().isBefore(request.getEntryEndAt())) {
            throw new IllegalStateException("추첨일은 응모 종료일 이후여야 합니다.");
        }
    }

    // 요청의 status가 누락된 경우 서비스에서 명시적으로 기본값을 적용
    private void normalizeStatus(RaffleRequest request) {
        if (request != null && request.getStatus() == null) {
            request.setStatus(RaffleStatus.DRAFT);
        }
    }

}
