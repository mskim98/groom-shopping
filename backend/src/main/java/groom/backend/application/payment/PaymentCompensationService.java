package groom.backend.application.payment;

import groom.backend.application.payment.event.PaymentCompensationConflictEvent;
import groom.backend.application.payment.event.PaymentCompensationDlqEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.domain.payment.model.PaymentCompensation;
import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.model.enums.CompensationStatus;
import groom.backend.domain.payment.repository.PaymentCompensationRepository;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import groom.backend.domain.payment.repository.PaymentRepository;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import groom.backend.infrastructure.payment.TossAlreadyCanceledException;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.payment.TossPaymentNotFoundException;
import groom.backend.infrastructure.payment.TossRetryLaterException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Toss 결제 승인 후 내부 DB 처리(재고 차감, 주문 상태 변경)가 실패하면 즉시 Toss 취소 API를 호출해 자동 환불을 시도, 즉시 취소마저 실패한 건은
 * {@code payment_compensation} 테이블에 기록되고, {@link #retryPendingCompensations()} 스케줄러가 지수 백오프로 주기적으로 재시도
 */
// @Slf4j : log 객체 생성.
@Slf4j
// @Service : 보상(환불) 유스케이스를 담당하는 응용 서비스 빈.
@Service
// @RequiredArgsConstructor : final 필드 생성자 주입.
@RequiredArgsConstructor
public class PaymentCompensationService {

    // private final : 스프링이 주입하는 협력 객체. 외부에서 못 바꾸게 막아 안전하게 사용.
    private final PaymentCompensationRepository compensationRepository;
    private final TossPaymentClient tossPaymentClient;
    // 최종 실패(GIVEN_UP) 시 DLQ 이벤트를 같은 트랜잭션의 Outbox 행으로 남긴다(폴러가 발행 - 직접 send 없음)
    private final PaymentOutboxRepository outboxRepository;
    // 취소 직전 결제 상태 재확인용
    private final PaymentRepository paymentRepository;
    private final ObjectMapper objectMapper;
    // 자기호출 우회: recordCompensationIntent/recordResult 의 @Transactional 이 프록시를 거쳐 적용되도록 한다
    private final ObjectProvider<PaymentCompensationService> selfProvider;
    // 배치 설정(페이지 크기 · 워커 수 · 정지 신호 회로 이름들)
    private final PaymentCompensationBatchProperties batchProperties;
    // 회차 시작에 회로 상태를 읽어 OPEN 이면 쉬기 위해 주입
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    // 적재·처리 지표 노출용
    private final MeterRegistry meterRegistry;

    // 회차 지표 - 게이지가 가리키는 값. 게이지는 이 AtomicLong 을 읽으므로 @PostConstruct 등록 여부와 무관하게 갱신된다
    private final AtomicLong dueGauge = new AtomicLong(0);      // 회차 시작 시점의 재시도 대기 전체 수
    private final AtomicLong carriedGauge = new AtomicLong(0);  // due - processed. 한 페이지로 못 비운 적재

    /**
     * 기동 시 배치 설정을 검증한다. 어긋나면 기동을 실패시켜 잘못된 값으로 배치가 도는 것을 막는다.
     * skip-when-open 의 회로는 여기서 미리 생성해 회차마다 getState 를 읽을 수 있게 한다
     */
    @PostConstruct
    void validateBatchConfig() {
        if (batchProperties.getBatchSize() < 1) {
            throw new IllegalStateException("payment.compensation.batch-size 는 1 이상이어야 한다: " + batchProperties.getBatchSize());
        }
        if (batchProperties.getWorkers() < 1) {
            throw new IllegalStateException("payment.compensation.workers 는 1 이상이어야 한다: " + batchProperties.getWorkers());
        }
        // 회로는 resilience4j 가 첫 호출에 lazily 생성하므로, 여기서 이름으로 미리 만들어 회차 조회가 항상 성립하게 한다.
        // yml 에 정의가 없으면 기본 설정으로 생성되므로 오타는 잡지 못한다 - 이 한계는 보고서에 명시
        batchProperties.getSkipWhenOpen().forEach(circuitBreakerRegistry::circuitBreaker);

        Gauge.builder("payment_compensation_due", dueGauge, AtomicLong::get)
                .description("회차 시작 시점의 재시도 대기 전체 수").register(meterRegistry);
        Gauge.builder("payment_compensation_carried", carriedGauge, AtomicLong::get)
                .description("carried = due - processed. 한 페이지로 못 비운 적재").register(meterRegistry);
        // 메모리 누적이 아니라 DB 의 GIVEN_UP 행 수를 읽는다 - 재기동·다중 인스턴스에서도 GIVEN_UP 행이 진실이다
        Gauge.builder("payment_compensation_given_up_total", compensationRepository,
                        repo -> repo.countByStatus(CompensationStatus.GIVEN_UP))
                .description("최종 포기(GIVEN_UP)한 보상 행 수(DB 카운트)").register(meterRegistry);
    }

    // 토스가 살아 있다는 신호(429·403·409)를 받았을 때 다음 시도까지 미루는 시간
    // retry-delay-ms(스케줄러 주기, 기본 30초)보다 길게 잡아 같은 회차 안에서 바로 재충돌하지 않게 한다
    @Value("${payment.compensation.retry-later-defer-ms:45000}")
    private long retryLaterDeferMs;

    // 유예(429·403·409)가 이어져도 생성 후 이 시간이 지나면 더 기다리지 않고 GIVEN_UP 으로 끝낸다
    @Value("${payment.compensation.defer-max-age-minutes:60}")
    private long deferMaxAgeMinutes = 60;

    // 배치 주기와 값을 맞춘다(@Scheduled 의 fixedDelayString 과 동일한 프로퍼티·기본값)
    // 죽은 인스턴스 회수 기준(staleThreshold)이 이 값의 3배이므로 항상 같은 값을 참조해야 한다
    @Value("${payment.compensation.retry-delay-ms:30000}")
    private long retryDelayMs = 30000;

    /**
     * 즉시 보상(환불)을 시도한다. 실패 시 재시도 대상으로 기록
     *
     * @param paymentId     내부 결제 ID
     * @param paymentKey    Toss paymentKey (이미 승인된 결제이므로 반드시 존재)
     * @param amount        승인 금액 (기록용)
     * @param failureReason 내부 DB 실패 원인
     */
    // 결정 C(2026-09-06) - 대기 기록만 하고 즉시 취소를 하지 않는다.
    // 여기서 PG 취소를 인라인으로 호출하면 실패를 처리하는 요청 스레드가 취소 응답을 기다리는 동안
    // 요청 커넥션을 취소 지연(최대 30초)만큼 물어 풀이 마른다. PENDING(nextRetryAt=now) 으로 남기면
    // retryPendingCompensations 배치가 다음 회차에 주워 트랜잭션 밖에서 취소한다. 취소 경로가 배치 하나뿐
    public void compensate(UUID paymentId, String paymentKey, Integer amount, String failureReason) {
        try {
            selfProvider.getObject()
                    .recordCompensationIntent(paymentId, paymentKey, amount, failureReason);
        } catch (DataIntegrityViolationException duplicate) {
            // payment_id 유일 제약 - 같은 결제의 보상 행이 이미 있다. 로그만 남기고 넘어가면 그 행이 종단 상태(NOT_APPROVED ·
            // SUPERSEDED …)일 때 새 승인의 환불이 영영 시도되지 않는다. 종단 행은 다시 열고, 진행 중인 행은 그대로 둔다.
            // 다시 열기가 실패하면 삼키지 않고 호출부로 전파한다
            log.warn("[PAYMENT_COMPENSATION_DUPLICATE] 이미 보상 행이 있음 - 다시 열기 확인 PaymentId: {}", paymentId);
            selfProvider.getObject().reopenCompensation(paymentId, paymentKey, amount, failureReason);
        }

        log.warn("[PAYMENT_COMPENSATION_START] PaymentKey: {}, Reason: {}", paymentKey, failureReason);
    }

    /**
     * 재시도할 취소가 아니라 사람이 대사해야 하는 건(수동 취소에서 Toss 는 취소됐는데 DB 갱신이 실패)을 남긴다.
     * 배치에 맡기면 DONE 결제라 SUPERSEDED 로 덮여 버리므로, 처음부터 GIVEN_UP 행 + DLQ 이벤트로 기록한다
     */
    public void recordReconcileNeeded(UUID paymentId, String paymentKey, Integer amount, String reason) {
        try {
            selfProvider.getObject().recordGivenUpIntent(paymentId, paymentKey, amount, reason);
        } catch (DataIntegrityViolationException raced) {
            // 조회와 삽입 사이에 같은 결제의 행이 먼저 생겼다 - 이번에는 조회가 그 행을 찾아 갱신 경로를 탄다.
            // 두 번째 실패는 삼키지 않고 호출부로 전파한다(대사 행도 DLQ 이벤트도 없는 불일치를 조용히 두지 않는다)
            log.warn("[PAYMENT_COMPENSATION_DUPLICATE] 삽입 경합 - 기존 행을 갱신으로 재시도 PaymentId: {}", paymentId);
            selfProvider.getObject().recordGivenUpIntent(paymentId, paymentKey, amount, reason);
        }
    }

    // 결제에 보상 행이 이미 있으면(SUPERSEDED · NOT_APPROVED 등) 그 행을 RECONCILE_NEEDED 로 갱신하고, 없으면 GIVEN_UP 행을 만든다.
    // 어느 쪽이든 같은 트랜잭션에서 DLQ Outbox 이벤트를 남긴다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordGivenUpIntent(UUID paymentId, String paymentKey, Integer amount, String reason) {
        PaymentCompensation existing = compensationRepository.findByPaymentId(paymentId).orElse(null);
        if (existing != null) {
            existing.markReconcileNeeded(reason);
            compensationRepository.save(existing);
            appendGivenUpOutbox(existing, reason);
            return;
        }
        PaymentCompensation compensation = PaymentCompensation.builder()
                .paymentId(paymentId).paymentKey(paymentKey).amount(amount).reason(reason).maxRetryCount(5).build();
        compensation.markGivenUp(reason);
        compensationRepository.save(compensation);
        appendGivenUpOutbox(compensation, reason);
    }

    /**
     * 같은 결제의 보상 행이 이미 있을 때의 처리.
     * - NOT_APPROVED · SUPERSEDED: 새 의도로 다시 연다(시간 초과가 승인 안 됐다고 끝난 뒤 재시도 확정이 승인된 경우 등)
     * - SUCCEEDED · GIVEN_UP · RECONCILE_NEEDED: 행을 건드리지 않는다. 끝난 환불을 되풀이하거나 DLQ 로 나간 건·사람의 표식을
     *   지우면 안 된다. SUCCEEDED 에서 같은 paymentKey 가 다시 승인되는 일은 Toss 에 없으므로 이상 징후로 보고,
     *   error 로그와 DLQ Outbox 이벤트(COMPENSATION_CONFLICT_ON_TERMINAL_ROW)를 남겨 운영자가 보게 한다
     * - PENDING · FAILED · PROCESSING: 이미 환불이 예약돼 있으므로 그대로 둔다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reopenCompensation(UUID paymentId, String paymentKey, Integer amount, String reason) {
        PaymentCompensation existing = compensationRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new IllegalStateException("payment_id 유일 위반인데 보상 행이 없다: " + paymentId));
        if (existing.isReopenable()) {
            CompensationStatus before = existing.getStatus();
            existing.reopen(paymentKey, amount, reason);
            compensationRepository.save(existing);
            log.warn("[PAYMENT_COMPENSATION_REOPENED] 종단 보상 행을 다시 엶 - PaymentId: {}, Before: {}", paymentId, before);
            return;
        }
        if (existing.getStatus() == CompensationStatus.SUCCEEDED || existing.getStatus() == CompensationStatus.GIVEN_UP
                || existing.getStatus() == CompensationStatus.RECONCILE_NEEDED) {
            log.error("[PAYMENT_COMPENSATION_CONFLICT] 종단 보상 행이 있는 결제에 새 보상 의도 - 행 유지, 운영자 확인 필요 "
                            + "- PaymentId: {}, ExistingStatus: {}, ExistingReason: {}, NewReason: {}",
                    paymentId, existing.getStatus(), existing.getReason(), reason);
            appendConflictOutbox(existing, paymentKey, reason);
            return;
        }
        log.info("[PAYMENT_COMPENSATION_ACTIVE] 이미 진행 중인 보상 행 유지 - PaymentId: {}, Status: {}",
                paymentId, existing.getStatus());
    }

    // 같은 트랜잭션(REQUIRES_NEW)에서 DLQ 토픽 Outbox 행으로 남긴다 - 직접 send 하지 않는다
    private void appendConflictOutbox(PaymentCompensation existing, String newPaymentKey, String newReason) {
        PaymentCompensationConflictEvent event = new PaymentCompensationConflictEvent(
                existing.getPaymentId(), existing.getStatus().name(), existing.getReason(),
                newPaymentKey, newReason, LocalDateTime.now());
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("보상 충돌 이벤트 직렬화 실패: " + e.getMessage(), e);
        }
        outboxRepository.save(PaymentOutbox.create(existing.getPaymentId(),
                PaymentCompensationConflictEvent.EVENT_TYPE, payload, PaymentCompensationDlqEvent.TOPIC));
    }

    /** 보상 의도만 독립 커밋한다. 이 커밋이 끝나야 실행이 실패해도 배치가 재시도할 수 있다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentCompensation recordCompensationIntent(UUID paymentId, String paymentKey,
                                                        Integer amount, String failureReason) {
        return compensationRepository.save(PaymentCompensation.builder()
                .paymentId(paymentId)
                .paymentKey(paymentKey)
                .amount(amount)
                .reason(failureReason)
                .maxRetryCount(5)
                .build());
    }

    /**
     * @Scheduled 배치 - 30초마다 실패한 보상 트랜잭션을 재시도
     */
    // @Scheduled(fixedDelayString) : 사용자 요청과 무관하게 스프링이 주기적으로 자동 호출한다.
    // 직전 실행이 끝난 뒤 지정 시간(기본 30초) 뒤 다시 실행 → 일시적 네트워크 오류로 못한 환불을 재시도.
    @Scheduled(fixedDelayString = "${payment.compensation.retry-delay-ms:30000}")
    public void retryPendingCompensations() {
        // 결정 C - 정지 신호 회로(승인 toss · 배치 정지 플래그 toss-cancel)가 하나라도 OPEN 이면 회차를 통째로 쉰다.
        // 죽은 PG 로 한 페이지를 쏟지 않기 위함. HALF_OPEN·CLOSED 는 실행한다
        String open = firstOpenSkipCircuit();
        if (open != null) {
            log.info("[PAYMENT_COMPENSATION_BATCH_SKIP] 회로 {} 가 OPEN 이라 이번 회차를 쉰다", open);
            return;
        }

        // 적재 계수 - 이번 회차가 마주한 전체 대기 수(페이지 상한 없음). 선점이 PROCESSING 으로 바꾸기 전에 센다
        LocalDateTime now = LocalDateTime.now();
        long due = compensationRepository.countRetryable(now, now.minus(Duration.ofMillis(retryDelayMs * 3)));

        // 프록시 경유라야 선점 조회의 @Transactional(REQUIRES_NEW) 가 실제로 적용된다
        List<PaymentCompensation> retryables = selfProvider.getObject().acquireRetryableBatch();

        if (retryables.isEmpty()) {
            dueGauge.set(due);
            carriedGauge.set(Math.max(0, due));
            return;
        }

        log.info("[PAYMENT_COMPENSATION_BATCH] Retrying {} compensations (workers={})",
                retryables.size(), batchProperties.getWorkers());

        // 한 페이지를 워커 수만큼 동시에 취소한다. 가상 스레드 실행기 + Semaphore 로 동시 PG 호출을 workers 로 제한.
        // 건별 결과는 executeCompensation 안의 recordResult(REQUIRES_NEW) 가 독립 커밋한다
        AtomicInteger succeeded = new AtomicInteger(0);
        Semaphore gate = new Semaphore(batchProperties.getWorkers());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (PaymentCompensation compensation : retryables) {
                futures.add(executor.submit(() -> {
                    gate.acquire();
                    try {
                        if (executeCompensation(compensation)) {
                            succeeded.incrementAndGet();
                        }
                    } finally {
                        gate.release();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try {
                    // 상한 없는 get() 금지 - 한 건 취소는 HTTP 읽기 한도(30초)로 끝나므로 그 몇 배면 충분하다
                    f.get(2, TimeUnit.MINUTES);
                } catch (Exception e) {
                    log.error("[PAYMENT_COMPENSATION_BATCH_ERROR] {}", e.getMessage());
                }
            }
        }

        // 적재 판정 - 한 페이지로 대기를 못 비웠으면(carried > 0) PG 장애로 보고 정지 플래그 회로를 강제 OPEN.
        // 다음 회차는 그 회로가 OPEN 이라 쉬고, 30초 뒤 HALF_OPEN 회차의 한 페이지가 PG 회복을 확인한다
        long carried = Math.max(0, due - retryables.size());
        dueGauge.set(due);
        carriedGauge.set(carried);
        boolean overloadedButHealthy = batchProperties.isOpenOnCarriedOnlyWhenNoSuccess() && succeeded.get() > 0;
        if (carried > 0 && !overloadedButHealthy) {
            circuitBreakerRegistry.circuitBreaker(batchProperties.getOpenOnCarried()).transitionToOpenState();
            log.warn("[PAYMENT_COMPENSATION_BATCH_CARRIED] carried={} (due={} processed={} succeeded={}) - {} 강제 OPEN",
                    carried, due, retryables.size(), succeeded.get(), batchProperties.getOpenOnCarried());
        }
    }

    // skip-when-open 회로 중 OPEN 인 첫 이름을 돌려준다. 없으면 null
    private String firstOpenSkipCircuit() {
        for (String name : batchProperties.getSkipWhenOpen()) {
            if (circuitBreakerRegistry.circuitBreaker(name).getState() == CircuitBreaker.State.OPEN) {
                return name;
            }
        }
        return null;
    }

    /**
     * 선점 조회 - SKIP LOCKED 로 이번 배치가 처리할 행을 골라 PROCESSING 으로 갱신·독립 커밋한다.
     * 죽은 인스턴스가 PROCESSING 에 묶어 둔 행은 staleThreshold(배치 주기의 3배) 를 지나면 다시 선점 대상이 된다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<PaymentCompensation> acquireRetryableBatch() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleThreshold = now.minus(Duration.ofMillis(retryDelayMs * 3));
        return compensationRepository.findRetryable(now, staleThreshold, batchProperties.getBatchSize());
    }

    /**
     * 실행 단계 - Toss 취소 API 를 호출하고 그 결과를 {@link #recordResult} 에 값으로 넘긴다. 멱등성 키(paymentKey + 보상 레코드 ID)로 이중 환불을 방지
     */
    // 이 메서드에는 트랜잭션을 두지 않는다.
    // PG 응답을 기다리는 동안 DB 커넥션을 쥐면 실패 1건이 취소 타임아웃(30초)만큼 풀을 점유해
    // 풀 50 이 초당 1.7건의 실패에 마른다. 호출은 밖에서, 기록만 REQUIRES_NEW 로 짧게 한다
    public boolean executeCompensation(PaymentCompensation compensation) {
        // 취소 직전 결제를 다시 읽는다. 동시 중복 확정에서 한쪽이 정상 승인(DONE)을 끝냈다면 이 보상은 그 정상 결제를
        // 같은 paymentKey 로 취소하게 된다 - 호출하지 않고 SUPERSEDED 로 끝낸다
        PaymentStatus current = paymentRepository.findById(compensation.getPaymentId())
                .map(p -> p.getStatus()).orElse(null);
        if (current == PaymentStatus.DONE || current == PaymentStatus.PARTIAL_CANCELED
                || current == PaymentStatus.CANCELED) {
            selfProvider.getObject().recordSuperseded(compensation,
                    "결제가 이미 " + current + " - 정상 승인이거나 이미 취소돼 Toss 취소를 호출하지 않음");
            return false;
        }
        // 멱등성 키 : 보상 레코드 ID 는 재시도 간 불변이라 같은 취소 의도가 항상 같은 키로 도달한다
        // retryCount 를 섞으면 시도마다 키가 달라져, Toss 는 이미 처리했는데 응답만 유실된 경우를
        // 새 요청으로 오인해 이중 환불이 난다 -> 멱등키가 목적을 잃는다
        String idempotencyKey = compensation.getPaymentKey() + ":compensate:" + compensation.getId();
        Exception failure = null;
        try {
            // 배치 전용 미계수 취소 - toss-cancel 회로에 계수하지 않아 대량 실패가 수동 취소 엔드포인트를 막지 않는다
            tossPaymentClient.cancelPaymentUncounted(
                    compensation.getPaymentKey(),
                    "[보상 트랜잭션] " + compensation.getReason(),
                    idempotencyKey
            );
        } catch (Exception e) {
            failure = e;
        }
        // 프록시 경유라야 @Transactional(REQUIRES_NEW) 가 실제로 적용된다. 성공(SUCCEEDED) 여부를 돌려준다
        return selfProvider.getObject().recordResult(compensation, failure);
    }

    // ±20% 지터 - 같은 순간 유예된 행들이 같은 시각에 한꺼번에 돌아와 Toss 의 429 를 다시 맞는 일을 흩는다
    private static Duration jittered(Duration base) {
        double factor = 0.8 + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 0.4;
        return Duration.ofMillis((long) (base.toMillis() * factor));
    }

    /** 취소를 호출하지 않고 SUPERSEDED 로 끝낸 결과를 독립 커밋한다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuperseded(PaymentCompensation compensation, String reason) {
        compensation.markSuperseded(reason);
        compensationRepository.save(compensation);
        log.warn("[PAYMENT_COMPENSATION_SUPERSEDED] PaymentKey: {}, Reason: {}", compensation.getPaymentKey(), reason);
    }

    /**
     * 기록 단계 - 호출 결과를 장부에 독립 커밋한다. {@code failure} 가 null 이면 성공
     */
    // @Transactional(REQUIRES_NEW) : 환불 결과 기록을 항상 독립 커밋한다.
    // 호출부(compensate·배치)의 트랜잭션에 합류하면 결과 기록이 함께 롤백될 수 있다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordResult(PaymentCompensation compensation, Exception failure) {
        if (failure == null) {
            compensation.markSucceeded();
            compensationRepository.save(compensation);

            log.info("[PAYMENT_COMPENSATION_SUCCESS] PaymentKey: {}, RetryCount: {}",
                    compensation.getPaymentKey(), compensation.getRetryCount());
            return true;
        }

        // 토스에 승인된 결제가 없다 - 시간 초과로 보상을 걸어 둔 확정이 실제로는 승인되지 않은 경우. 재시도·DLQ 없이 끝낸다
        if (failure instanceof TossPaymentNotFoundException) {
            compensation.markNotApproved(failure.getMessage());
            compensationRepository.save(compensation);

            log.info("[PAYMENT_COMPENSATION_NOT_APPROVED] PaymentKey: {}", compensation.getPaymentKey());
            return false;
        }

        // 토스가 이미 목적을 달성한 응답 - 실패가 아니라 성공으로 기록한다
        if (failure instanceof TossAlreadyCanceledException) {
            compensation.markSucceeded();
            compensationRepository.save(compensation);

            log.info("[PAYMENT_COMPENSATION_ALREADY_CANCELED] PaymentKey: {}",
                    compensation.getPaymentKey());
            return true;
        }

        // 토스가 살아 있다는 신호 - retryCount 는 올리지 않고 다음 시각만 미룬다. 성공은 아니므로 false
        if (failure instanceof TossRetryLaterException) {
            // 유예 상한 - 429 가 계속되는 행이 영원히 PENDING 에 남지 않게 생성 후 60분에서 끝낸다
            if (compensation.getCreatedAt() != null
                    && compensation.getCreatedAt().plusMinutes(deferMaxAgeMinutes).isBefore(LocalDateTime.now())) {
                String reason = "유예 상한(생성 후 " + deferMaxAgeMinutes + "분) 초과: " + failure.getMessage();
                compensation.markGivenUp(reason);
                compensationRepository.save(compensation);
                appendGivenUpOutbox(compensation, failure.getMessage());
                log.error("[PAYMENT_COMPENSATION_DEFER_CAP] PaymentKey: {}, CreatedAt: {} - GIVEN_UP",
                        compensation.getPaymentKey(), compensation.getCreatedAt());
                return false;
            }
            compensation.deferWithoutCounting(jittered(Duration.ofMillis(retryLaterDeferMs)));
            compensationRepository.save(compensation);

            log.warn("[PAYMENT_COMPENSATION_RETRY_LATER] PaymentKey: {}, NextRetryAt: {}",
                    compensation.getPaymentKey(), compensation.getNextRetryAt());
            return false;
        }

        compensation.markFailed(failure.getMessage());
        compensationRepository.save(compensation);

        log.error("[PAYMENT_COMPENSATION_FAILED] PaymentKey: {}, RetryCount: {}, Status: {}, Error: {}",
                compensation.getPaymentKey(), compensation.getRetryCount(),
                compensation.getStatus(), failure.getMessage());

        // 재시도 한도 초과로 최종 실패(GIVEN_UP)면 DLQ 로 이관해 운영팀이 즉시 인지하도록 한다.
        if (compensation.getStatus() == CompensationStatus.GIVEN_UP) {
            appendGivenUpOutbox(compensation, failure.getMessage());
        }
        return false;
    }

    // GIVEN_UP 전환과 같은 트랜잭션(recordResult 의 REQUIRES_NEW)에 DLQ 이벤트를 Outbox 로 적재한다
    // 저장이 실패하면 예외가 그대로 전파돼 GIVEN_UP 전환도 함께 롤백된다 - 행은 PROCESSING 에 남아 stale 회수 뒤 다시 시도된다
    private void appendGivenUpOutbox(PaymentCompensation compensation, String lastError) {
        PaymentCompensationDlqEvent event = new PaymentCompensationDlqEvent(
                compensation.getId(),
                compensation.getPaymentId(),
                compensation.getPaymentKey(),
                compensation.getAmount(),
                compensation.getReason(),
                lastError,
                compensation.getRetryCount(),
                LocalDateTime.now());
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("GIVEN_UP DLQ 이벤트 직렬화 실패: " + e.getMessage(), e);
        }
        outboxRepository.save(PaymentOutbox.create(
                compensation.getPaymentId(), PaymentCompensationDlqEvent.EVENT_TYPE, payload,
                PaymentCompensationDlqEvent.TOPIC));
        log.warn("[COMPENSATION_DLQ_OUTBOX] compensationId={}, paymentKey={}",
                compensation.getId(), compensation.getPaymentKey());
    }
}
