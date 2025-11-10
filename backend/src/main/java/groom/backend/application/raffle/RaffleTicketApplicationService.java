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
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RaffleTicketApplicationService {

    private final RaffleTicketAllocationService allocationService;
    private final RaffleTicketRepository raffleTicketRepo;
    private final RaffleValidationService validationService;
    private final CartApplicationService cartApplicationService;

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
    @Transactional
    public boolean createTickets(Raffle raffle, Long userId, int quantity) {

        // 1) 연속된 티켓 번호 범위를 원자적으로 확보
        // allocateTicketRange 는 DB에서 PESSIMISTIC_WRITE 또는 단일 업데이트로 current 값을 증가시켜
        // 시작(start)과 끝(end) 번호 범위를 반환합니다. 이 호출은 동시성 문제(번호 중복/누락)를 방지합니다.
        TicketRange range = allocationService.allocateTicketRange(raffle.getRaffleId(), quantity);

        if (range == null || range.size() != quantity) {
            return false;
        }

        // 2) 확보한 티켓 번호 범위로 RaffleTicket 엔티티를 생성
        List<RaffleTicket> toSave = new ArrayList<>(quantity);
        for (long i = range.start(); i <= range.end(); i++) {
            // RaffleTicket 생성
            RaffleTicket ticket = new RaffleTicket(null, raffle.getRaffleId(), userId, i, null);
            toSave.add(ticket);
        }

        // saveAll로 한 번에 저장 (JPA는 내부적으로 여러 insert 실행)
        List<RaffleTicket> saved = raffleTicketRepo.saveAll(toSave);
        return saved.size() == toSave.size();
    }

}
