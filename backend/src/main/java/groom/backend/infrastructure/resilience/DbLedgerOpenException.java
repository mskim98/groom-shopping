package groom.backend.infrastructure.resilience;

/** db-ledger 회로가 열려 후처리를 시작하지 않았다는 신호 - 레코드를 승인하지 않고 되감아 재개 뒤에 다시 읽게 한다 */
public class DbLedgerOpenException extends RuntimeException {

    public DbLedgerOpenException() {
        super("db-ledger 회로가 열려 있어 결제 후처리를 보류한다");
    }
}
