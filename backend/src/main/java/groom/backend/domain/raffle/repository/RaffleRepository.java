package groom.backend.domain.raffle.repository;

import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.interfaces.raffle.dto.request.RaffleSearchRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface RaffleRepository {
    Optional<Raffle> findById(Long id);
    Raffle save(Raffle raffle);
    boolean existsByRaffleProductId(String raffleProductId);
    void deleteById(Long id);

    Page<Raffle> search(RaffleSearchRequest cond, Pageable pageable);
}
