package groom.backend.domain.raffle.repository;

import groom.backend.domain.raffle.entity.Participant;
import groom.backend.domain.raffle.entity.RaffleWinner;
import groom.backend.interfaces.raffle.dto.notification.RaffleWinnerNotification;
import groom.backend.interfaces.raffle.dto.request.RaffleDrawCondition;

import java.util.List;

public interface RaffleWinnerRepository {
    RaffleWinner save(RaffleWinner raffleWinner);

    List<RaffleWinnerNotification> findNotificationsByRaffleId(Long raffleId);

    int countWinnerByRaffleId(Long raffleId);
    int pickWinnersNative (RaffleDrawCondition condition);

    // raffle의 당첨자 목록 조회 (도메인 Participant 반환)
    List<Participant> findWinnersByRaffleId(Long raffleId);
}
