package groom.backend.application.payment;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// 결제 확정 한 번이 어디서 시간을 쓰는지 가르는 구간 타이머 - 타이머 이름 하나(payment.confirm.stage)에 구간을 stage 태그로 나눈다
// r120 t7 의 부하 측정에서 Toss 응답 → outbox 발행 사이 중앙값 0.83초가 어느 구간 몫인지 밝히지 못했다
// 부하 측정이 /actuator/prometheus 의 payment_confirm_stage_seconds_{count,sum,bucket}{stage="..."} 로 읽는다
@Component
public class ConfirmStageTimers {

    public static final String NAME = "payment.confirm.stage";

    /** Toss 승인 API 호출(요청 → 응답) */
    public static final String TOSS_CALL = "toss_call";
    /** READY 전이 + outbox 적재 트랜잭션(REQUIRES_NEW)의 시작부터 커밋 끝까지 */
    public static final String READY_OUTBOX_COMMIT = "ready_outbox_commit";
    /** outbox 행 생성 → 폴러가 집어 가기까지(재발행 행 제외) */
    public static final String OUTBOX_WAIT_CLAIM = "outbox_wait_claim";
    /** Kafka 발행 요청 → 브로커 확인 */
    public static final String KAFKA_PUBLISH_ACK = "kafka_publish_ack";
    /** 컨슈머 후처리 진입 → 승인 트랜잭션 커밋 */
    public static final String CONSUMER_RECEIVE_TO_COMMIT = "consumer_receive_to_commit";
    /** 승인 커밋 뒤 완료 신호가 서고 → 기다리던 confirm 이 그것을 볼 때까지(신호 경로, 서버 시계 차이만큼 오차) */
    public static final String COMMIT_TO_CONFIRM_SEEN = "commit_to_confirm_seen";

    private final MeterRegistry registry;

    @Autowired
    public ConfirmStageTimers(MeterRegistry registry) {
        this.registry = registry;
    }

    // 레지스트리 없이 만든 시험용 발행기가 쓰는 빈 기록기 - 아무것도 기록하지 않는다
    public static ConfirmStageTimers noop() {
        return new ConfirmStageTimers(null);
    }

    public void record(String stage, long nanos) {
        if (registry == null) {
            return;
        }
        // 히스토그램을 켜야 prometheus 에서 구간별 p95 를 계산할 수 있다
        Timer.builder(NAME).tag("stage", stage).publishPercentileHistogram().register(registry)
                .record(Math.max(0L, nanos), TimeUnit.NANOSECONDS);
    }
}
