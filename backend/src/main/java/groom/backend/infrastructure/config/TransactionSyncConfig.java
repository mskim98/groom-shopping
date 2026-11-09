package groom.backend.infrastructure.config;

import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

// 트랜잭션 동기화를 실제 트랜잭션이 있을 때만 켠다.
// 기본값(SYNCHRONIZATION_ALWAYS)은 NOT_SUPPORTED 메서드(confirmPayment · applyApprovedEvent · cancelPayment · issueCoupon)에서도
// 동기화를 켜고, 그러면 Spring Data 가 EntityManager 하나를 스레드에 묶어(EntityManagerFactoryUtils.doGetTransactionalEntityManager)
// Hibernate(DELAYED_ACQUISITION_AND_HOLD)가 첫 조회부터 메서드 끝까지 DB 커넥션을 쥔다. OSIV 를 꺼도 이 경로는 남는다.
// confirmPayment 는 Toss 응답(최대 60초)과 컨슈머 결과(최대 10초)를 기다리므로 VU 100 에서 풀 50 이 말라 TX1 이 커넥션을 못 얻었다
// (2026-11-10 측정: 성공 300/1,000 · 커넥션 대기 최대 47). 실제 트랜잭션에서만 동기화하면 조회마다 EntityManager 를 열고 닫아 커넥션을 바로 돌려준다
@Configuration
public class TransactionSyncConfig {

    @Bean
    public TransactionManagerCustomizer<JpaTransactionManager> synchronizeOnActualTransactionOnly() {
        return tm -> tm.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_ON_ACTUAL_TRANSACTION);
    }
}
