package groom.backend.domain.coupon.enums;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum CouponType {
  @JsonProperty("PERCENT")  PERCENT,
  @JsonProperty("DISCOUNT")  DISCOUNT
}
