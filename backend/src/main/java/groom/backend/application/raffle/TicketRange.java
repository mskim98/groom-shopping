package groom.backend.application.raffle;

/**
 * 연속된 티켓 번호의 시작(start)과 끝(end)을 포함하는 불변 레코드입니다.
 * start와 end는 포함(inclusive)됩니다.
 */
public record TicketRange(long start, long end) {

    public long size() {
        return end - start + 1;
    }
}
