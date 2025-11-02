package groom.backend.domain.raffle.enums;


import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RaffleWinnerStatus {
    RESERVED("대기"), 
    PURCHASED("구매"), 
    EXPIRED("기한만료"), 
    FORFEITED("포기");

    private final String description; 
}
