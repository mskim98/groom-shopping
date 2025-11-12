package groom.backend.domain.coupon.model.enums;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum CouponType {
  @JsonProperty("PERCENT")  PERCENT,
  @JsonProperty("DISCOUNT")  DISCOUNT,
  @JsonProperty("MIN_COST_AMOUNT") MIN_COST_AMOUNT,
  @JsonProperty("MAX_DISCOUNT_PERCENT") MAX_DISCOUNT_PERCENT
}
