package groom.backend.interfaces.raffle.dto.notification;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@Getter
@AllArgsConstructor
public class RaffleWinnerNotification {
    private Long raffleTicketId;
    private Long userId;
    private UUID productId;
    private String message;
}
