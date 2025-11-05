package groom.backend.application.raffle;

import groom.backend.domain.raffle.criteria.RaffleValidationCriteria;
import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.entity.RaffleTicket;
import groom.backend.domain.raffle.repository.RaffleRepository;
import groom.backend.domain.raffle.repository.RaffleTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RaffleTicketApplicationService {

    private final RaffleTicketAllocationService allocationService;
    private final RaffleRepository raffleRepository;
    private final RaffleTicketRepository raffleTicketRepo;
    private final RaffleValidationService validationService;

    // 응모 장바구니에 저장
    public void addToEntryCart(Long raffleId, Long userId, int count) {
        Raffle raffle = validationService.findById(raffleId);
        // 상품 존재 및 상태 ,재고 검증
        RaffleValidationCriteria criteria = RaffleValidationCriteria.builder()
                .raffleProductId(raffle.getRaffleProductId())
                .build();
        validationService.validateProductsForRaffle(criteria);

        // 응모 가능 여부 검증 (응모 기간, 상태)
        validationService.validateRaffleForEntry(raffle);

        // 사용자 응모 한도 검증
        validationService.validateUserEntryLimit(raffle, userId, count);
        // TODO 장바구니 로직 추가 - 실제 티켓은 결제 완료 후 생성

    }

    // 결제 완료 후 호출 - 티켓 생성
    @Transactional
    public Boolean createTicket(Raffle raffle, Long userId) {
        // allocateNextTicketNumber 내부에서 PESSIMISTIC_WRITE로 카운터를 잠그고 증가시킴
        Long ticketNumber = allocationService.allocateNextTicketNumber(raffle.getRaffleId());

        RaffleTicket ticket = new RaffleTicket(
                null,
                raffle.getRaffleId(),
                userId,
                ticketNumber,
                null
        );

        RaffleTicket saved = raffleTicketRepo.save(ticket);
        return saved != null;
    }

}
