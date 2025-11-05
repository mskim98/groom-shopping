package groom.backend.domain.raffle.repository;

import groom.backend.domain.raffle.criteria.RaffleSearchCriteria;
import groom.backend.domain.raffle.entity.Raffle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface RaffleRepository {
    Optional<Raffle> findById(Long id);
    Raffle save(Raffle raffle);
    boolean existsByRaffleProductId(Long raffleProductId);
    void deleteById(Long id);

    // 도메인 수준의 검색 기준을 사용하도록 변경
    Page<Raffle> search(RaffleSearchCriteria cond, Pageable pageable);

    // 추첨용 상품으로 RaffleId 조회
    Optional<Raffle> findByRaffleProductId(Long raffleProductId);
}
