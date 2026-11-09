package groom.backend.domain.payment.model;

/**
 * 컬럼 길이(varchar) 를 넘는 문자열을 말줄임표로 잘라 DB 의 value too long 예외를 막는다
 * 외부(Toss) 오류 문구는 길이를 보장할 수 없어, 잘라 저장하지 않으면 실패 기록 자체가 실패한다
 */
final class TextLimit {

    private static final String ELLIPSIS = "…";

    private TextLimit() {
    }

    static String clip(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - ELLIPSIS.length()) + ELLIPSIS;
    }
}
