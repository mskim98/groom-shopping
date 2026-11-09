package groom.backend.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

/**
 * NOT_SUPPORTED 메서드(confirmPayment · applyApprovedEvent · cancelPayment)가 트랜잭션 없이도 동기화를 켜면 Spring Data 가
 * EntityManager 하나를 스레드에 묶고 Hibernate(DELAYED_ACQUISITION_AND_HOLD)가 첫 조회부터 메서드 끝까지 DB 커넥션을 쥔다 -
 * Toss 대기와 컨슈머 결과 대기 동안 커넥션이 묶여 VU 100 에서 풀 50 이 말랐다(2026-11-10 측정). 실제 트랜잭션에서만 동기화하는지 본다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.kafka.streams.auto-startup=false"})
class TransactionSyncConfigTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 트랜잭션관리자_동기화모드_실제트랜잭션에서만() {
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        assertThat(((JpaTransactionManager) transactionManager).getTransactionSynchronization())
                .isEqualTo(AbstractPlatformTransactionManager.SYNCHRONIZATION_ON_ACTUAL_TRANSACTION);
    }
}
