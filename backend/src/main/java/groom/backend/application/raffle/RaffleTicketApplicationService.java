package groom.backend.application.raffle;

import groom.backend.application.cart.CartApplicationService;
import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.entity.RaffleTicket;
import groom.backend.domain.raffle.repository.RaffleRepository;
import groom.backend.domain.raffle.repository.RaffleTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RaffleTicketApplicationService {

    private final RaffleTicketAllocationService allocationService;
    private final RaffleRepository raffleRepository;
    private final RaffleTicketRepository raffleTicketRepo;
    private final RaffleValidationService validationService;
    private final CartApplicationService cartApplicationService;

    // 응모 장바구니에 저장
    public void addToEntryCart(Long raffleId, Long userId, int count) {
        Raffle raffle = raffleRepository.findById(raffleId)
                .orElseThrow(() -> new IllegalStateException("해당 ID의 추첨이 존재하지 않습니다."));

        // TODO : 응모 상품 존재 여부 조회

        // 응모 가능 여부 검증 (응모 기간, 상태 )
        validationService.validateRaffleForEntry(raffle);

        // 사용자 응모 한도 검증
        validationService.validateUserEntryLimit(raffle, userId, count);
        // TODO 장바구니 로직 추가 - 실제 티켓은 결제 완료 후 생성
        cartApplicationService.addToCart(userId, raffle.getRaffleProductId(), count);
    }

    // 결제 완료 후 호출 - 티켓 생성
    @Transactional
    public boolean createTickets(Raffle raffle, Long userId, int quantity) {

        // 1) 연속된 티켓 번호 범위를 원자적으로 확보
        // allocateTicketRange 는 DB에서 PESSIMISTIC_WRITE 또는 단일 업데이트로 current 값을 증가시켜
        // 시작(start)과 끝(end) 번호 범위를 반환합니다. 이 호출은 동시성 문제(번호 중복/누락)를 방지합니다.
        TiketRange range = allocationService.allocateTicketRange(raffle.getRaffleId(), quantity);

        if (range == null || range.size() != quantity) {
            return false;
        }

        // 2) 확보한 티켓 번호 범위로 RaffleTicket 엔티티를 생성
        List<RaffleTicket> toSave = new ArrayList<>(quantity);
        for (long i = range.getStart(); i <= range.getEnd(); i++) {
            // RaffleTicket 생성
            RaffleTicket ticket = new RaffleTicket(null, raffle.getRaffleId(), userId, i, null);
            toSave.add(ticket);
        }

        // saveAll로 한 번에 저장 (JPA는 내부적으로 여러 insert 실행)
        List<RaffleTicket> saved = raffleTicketRepo.saveAll(toSave);
        return saved.size() == toSave.size();
    }

}
