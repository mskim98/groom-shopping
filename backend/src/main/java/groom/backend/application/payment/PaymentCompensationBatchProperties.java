package groom.backend.application.payment;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 보상 재시도 배치 설정. 한 회차 = 한 페이지(batch-size)이며, 그 페이지를 워커(workers) 수만큼 병렬로 취소한다.
 * skip-when-open 에 적힌 회로가 하나라도 OPEN 이면 회차를 통째로 쉰다(결정 C - 배치가 죽은 PG 로 페이지를 쏟지 않게).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "payment.compensation")
public class PaymentCompensationBatchProperties {

    // 한 회차가 조회·처리하는 최대 건수. 회차 안 반복은 없다(적재 판정의 단위가 페이지)
    private int batchSize = 100;

    // 한 페이지를 동시에 취소하는 워커 수. 페이지 100 x PG 0.3초 / 5 = 6초로 회차(30초) 안에 비운다
    private int workers = 5;

    // 회차 시작에 상태를 읽어 OPEN 이면 회차를 쉬는 회로 이름들. HALF_OPEN / CLOSED 는 실행한다
    private List<String> skipWhenOpen = List.of("toss", "toss-cancel");
}
