package groom.backend.interfaces.raffle.persistence.repository.springData;

import groom.backend.domain.raffle.entity.Participant;
import groom.backend.interfaces.raffle.persistence.Entity.RaffleTicketJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataRaffleTicketRepository extends JpaRepository<RaffleTicketJpaEntity, Long> {
    int countByRaffle_RaffleIdAndUserId(Long raffleId, Long userId);
    @Query("select count(distinct rt.userId) from RaffleTicketJpaEntity rt where rt.raffle.raffleId = :raffleId")
    int countDistinctUserByRaffleId(Long raffleId);

    @Query("""
      SELECT new groom.backend.domain.raffle.entity.Participant(u.id, u.name, u.email, rt.createdAt)
      FROM RaffleTicketJpaEntity rt, UserJpaEntity u
      WHERE rt.raffle.raffleId = :raffleId
        AND u.id = rt.userId
        AND ((:#{#keyword} IS NULL OR u.name LIKE CONCAT('%', :#{#keyword}, '%'))
        OR (:#{#keyword} IS NULL OR u.email LIKE CONCAT('%', :#{#keyword}, '%')))
      """)
    Page<Participant> searchParticipants(@Param("raffleId") Long raffleId, @Param("keyword") String keyword, Pageable pageable);

}
