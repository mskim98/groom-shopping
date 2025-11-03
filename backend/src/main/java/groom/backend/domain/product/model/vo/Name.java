package groom.backend.domain.product.model.vo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Name {

    @Column(name = "name", nullable = false, length = 100)
    private String value;

    public Name(String value) {
        validate(value);
        this.value = value;
    }

    private void validate(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("상품명은 필수입니다");
        }
        if (value.length() > 100) {
            throw new IllegalArgumentException("상품명은 100자 이하여야 합니다");
        }
    }
}

