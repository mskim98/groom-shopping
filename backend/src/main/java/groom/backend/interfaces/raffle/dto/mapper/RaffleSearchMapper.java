package groom.backend.interfaces.raffle.dto.mapper;

import groom.backend.domain.raffle.criteria.RaffleSearchCriteria;
import groom.backend.interfaces.raffle.dto.request.RaffleSearchRequest;

public class RaffleSearchMapper {
    private RaffleSearchMapper() {}

    public static RaffleSearchCriteria toCriteria(RaffleSearchRequest req) {
        if (req == null) {
            return null;
        }
        return RaffleSearchCriteria.builder()
                .title(req.getTitle())
                .raffleProductId(req.getRaffleProductId())
                .status(req.getStatus())
                .entryStartFrom(req.getEntryStartFrom())
                .entryStartTo(req.getEntryStartTo())
                .raffleDrawFrom(req.getRaffleDrawFrom())
                .raffleDrawTo(req.getRaffleDrawTo())
                .build();
    }
}
