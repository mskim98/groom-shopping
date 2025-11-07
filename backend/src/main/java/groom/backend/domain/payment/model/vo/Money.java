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
public class Money {

    @Column(name = "amount", nullable = false)
    private Integer value;

    private Money(Integer value) {
        validateAmount(value);
        this.value = value;
    }

    public static Money of(Integer value) {
        return new Money(value);
    }

    public static Money won(Integer value) {
        return new Money(value);
    }

    private void validateAmount(Integer value) {
        if (value == null) {
            throw new IllegalArgumentException("금액은 null일 수 없습니다.");
        }
        if (value < 0) {
            throw new IllegalArgumentException("금액은 0보다 작을 수 없습니다.");
        }
    }

    public Money add(Money other) {
        return new Money(this.value + other.value);
    }

    public Money subtract(Money other) {
        return new Money(this.value - other.value);
    }

    public Money multiply(int multiplier) {
        return new Money(this.value * multiplier);
    }

    public boolean isGreaterThan(Money other) {
        return this.value > other.value;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        return this.value >= other.value;
    }

    public boolean isLessThan(Money other) {
        return this.value < other.value;
    }

    public boolean isZero() {
        return this.value == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money)) {
            return false;
        }
        Money money = (Money) o;
        return Objects.equals(value, money.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value + "원";
    }
}
