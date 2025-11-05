package groom.backend.interfaces.raffle.dto.notification;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RaffleWinnerNotification {
    private Long raffleTicketId;
    private Long userId;
    private Long productId;
    private String message;
}
