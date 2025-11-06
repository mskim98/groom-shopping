package groom.backend.application.raffle;

import groom.backend.application.cart.CartApplicationService;
import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.entity.RaffleTicket;
import groom.backend.domain.raffle.repository.RaffleTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추첨 티켓(Raffle Ticket) 관련 비즈니스 로직을 처리하는 서비스 클래스
 *
 * 1. 사용자가 응모 하면 장바구니에 응모 상품을 담는다.
 * 2. 결제 완료 후 티켓을 생성한다.
 */
@Service
@RequiredArgsConstructor
public class RaffleTicketApplicationService {

    private final RaffleTicketAllocationService allocationService;
    private final RaffleTicketRepository raffleTicketRepo;
    private final RaffleValidationService validationService;
    private final CartApplicationService cartApplicationService;;

    // 응모 장바구니에 저장
    @Transactional
    public void addToEntryCart(Long raffleId, Long userId, int count) {
        Raffle raffle = validationService.findById(raffleId);
        // 상품 존재 및 상태 ,재고 검증 (add To Cart 내부에서 처리함)
        /*
        RaffleValidationCriteria criteria = RaffleValidationCriteria.builder()
                .raffleProductId(raffle.getRaffleProductId())
                .build();
        validationService.validateProductsForRaffle(criteria);
        */

        // 응모 가능 여부 검증 (응모 기간, 상태)
        validationService.validateRaffleForEntry(raffle);

        // 사용자 응모 한도 검증
        validationService.validateUserEntryLimit(raffle, userId, count);

        // 장바구니에 응모 상품 추가
        // TODO : 예외 발생 시 처리 방식 확인 필요
        cartApplicationService.addToCart(userId, raffle.getRaffleProductId(), count);

    }

    // 결제 완료 후 호출 - 티켓 생성
    // Order 서비스에서 사용 (변경시 주의요망)
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
