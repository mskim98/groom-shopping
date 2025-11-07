package groom.backend.domain.payment.model.vo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionId {

    @Column(name = "transaction_id", length = 200)
    private String value;

    private TransactionId(String value) {
        validateTransactionId(value);
        this.value = value;
    }

    public static TransactionId of(String value) {
        return new TransactionId(value);
    }

    private void validateTransactionId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("TransactionId는 비어있을 수 없습니다.");
        }
        if (value.length() > 200) {
            throw new IllegalArgumentException("TransactionId는 200자를 초과할 수 없습니다.");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionId)) {
            return false;
        }
        TransactionId that = (TransactionId) o;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
