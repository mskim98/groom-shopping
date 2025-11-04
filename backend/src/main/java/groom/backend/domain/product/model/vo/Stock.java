package groom.backend.domain.product.model.vo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stock {

    @Column(name = "stock", nullable = false)
    private Integer amount;

    public Stock(Integer amount) {
        validate(amount);
        this.amount = amount;
    }

    private void validate(Integer amount) {
        if (amount == null) {
            throw new IllegalArgumentException("수량은 필수 입니다.");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("수량은 0 이상이어야 합니다");
        }
    }

    public Stock increase(Integer amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("증가할 수량은 양수여야 합니다.");
        }
        return new Stock(this.amount + amount);
    }

    public Stock decrease(Integer amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("감소할 수량은 양수여야 합니다.");
        }
        if (this.amount < amount) {
            throw new IllegalArgumentException("재고가 부족합니다."); // 도메인 의미 명확
        }
        return new Stock(this.amount - amount);
    }

    public boolean isEmpty() {
        return this.amount == 0;
    }

    public Integer getAmount() {
        return this.amount;
    }
}
