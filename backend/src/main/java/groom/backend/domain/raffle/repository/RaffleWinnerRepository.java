package groom.backend.domain.raffle.repository;

import groom.backend.domain.raffle.entity.RaffleWinner;
import groom.backend.interfaces.raffle.dto.notification.RaffleWinnerNotification;
import groom.backend.interfaces.raffle.dto.request.RaffleDrawCondition;

import java.util.List;

public interface RaffleWinnerRepository {
    RaffleWinner save(RaffleWinner raffleWinner);

    List<RaffleWinnerNotification> findNotificationsByRaffleId(Long raffleId);

    int countWinnerByRaffleId(Long raffleId);
    int pickWinnersNative (RaffleDrawCondition condition);
}
