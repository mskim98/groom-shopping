package groom.backend.interfaces.raffle.dto.request;

public class RaffleEntryRequest {
    private Integer count = 1;

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }
}
