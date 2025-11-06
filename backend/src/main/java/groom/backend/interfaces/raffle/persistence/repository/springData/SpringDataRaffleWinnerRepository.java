package groom.backend.interfaces.raffle.persistence.repository.springData;

import groom.backend.interfaces.raffle.dto.notification.RaffleWinnerNotification;
import groom.backend.interfaces.raffle.dto.request.RaffleDrawCondition;
import groom.backend.interfaces.raffle.persistence.Entity.RaffleWinnerJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;


public interface SpringDataRaffleWinnerRepository extends JpaRepository<RaffleWinnerJpaEntity, Long> {
    @Modifying
    @Query(value = ""
            + "WITH tickets_random AS ( "
            + "  SELECT raffle_ticket_id AS ticket_id, user_id, random() AS rnd "
            + "  FROM raffle_tickets "
            + "  WHERE raffle_id = :#{#cond.raffleId} "
            + "), best_per_user AS ( "  // 각 사용자별로 가장 유리한(최소) rnd 값을 가진 티켓만 남김 -> 티켓 수가 많을수록 최소값이 더 유리할 확률이 높아짐
            + "  SELECT DISTINCT ON (user_id) ticket_id, user_id, rnd "
            + "  FROM tickets_random "
            + "  ORDER BY user_id, rnd "
            + "), picked AS ( " // 사용자별 최소 rnd들 중에서 상위 N명(가장 작은 rnd)을 선택
            + "  SELECT ticket_id, user_id, rnd FROM best_per_user ORDER BY rnd LIMIT :#{#cond.numberOfWinners} "
            + ") "
            + "INSERT INTO raffle_winners (raffle_ticket_id, rank, status, created_at, updated_at) "
            + "SELECT ticket_id, row_number() OVER (ORDER BY rnd), 'RESERVED' ,NOW(), NOW()"
            + "FROM picked",
            nativeQuery = true)
    int pickWinnersNative(@Param("cond") RaffleDrawCondition cond);

    @Query(value = ""
            + "SELECT w.raffle_ticket_id, t.user_id, r.winner_product_id as product_id, concat('축하합니다. [', r.title,'] 추첨에 당첨 되셨습니다. \n 상품은 ', p.name, ' 입니다 ' ) as message "
            + "FROM raffles r "
            + "JOIN raffle_tickets t ON r.raffle_id = t.raffle_id "
            + "JOIN raffle_winners w ON t.raffle_ticket_id = w.raffle_ticket_id "
            + "JOIN product p ON r.winner_product_id = p.id "
            + "WHERE r.raffle_id = :raffleId ",
            nativeQuery = true)
    List<RaffleWinnerNotification> findNotificationsByRaffleId(Long raffleId);

    // raffle_winners 테이블의 행(선정된 당첨자) 총수
    int countByRaffleTicket_Raffle_RaffleId(Long raffleId);
}
