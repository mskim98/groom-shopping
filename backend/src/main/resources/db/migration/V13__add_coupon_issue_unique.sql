-- 중복 발급의 최후 방어선
-- Redis 발급자 SET 은 TTL 도 복원 보장도 없으므로, 애플리케이션 로직이 틀려도 여기서 막히게 한다
-- 제약 이름은 V1 의 uq_cart_item_unique 컨벤션을 따른다

-- 제약을 걸기 전에 기존 위반 데이터를 정리한다
-- 제약이 없던 동안 동시 요청 둘이 Redis SET 검사를 함께 통과해 실제로 중복 발급이 발생했다
-- 남기는 쪽은 id 가 작은 행 -> 선착순 의미상 먼저 발급된 것이 유효하다
DELETE FROM coupon_issue a
      USING coupon_issue b
      WHERE a.coupon_id = b.coupon_id
        AND a.user_id = b.user_id
        AND a.id > b.id;

ALTER TABLE coupon_issue
    ADD CONSTRAINT uq_coupon_issue_user UNIQUE (coupon_id, user_id);
