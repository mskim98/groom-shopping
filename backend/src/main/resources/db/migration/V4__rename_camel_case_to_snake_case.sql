-- ============================================
-- V4: Convert CamelCase → snake_case
-- Target DB: PostgreSQL
-- ============================================

------------------------------------------------
-- 1) Order → order
------------------------------------------------
ALTER TABLE "Order" RENAME TO orders;

-- Columns
ALTER TABLE orders RENAME COLUMN "userId" TO user_id;
ALTER TABLE orders RENAME COLUMN "subTotal" TO sub_total;
ALTER TABLE orders RENAME COLUMN "discountAmount" TO discount_amount;
ALTER TABLE orders RENAME COLUMN "totalAmount" TO total_amount;
ALTER TABLE orders RENAME COLUMN "couponId" TO coupon_id;
ALTER TABLE orders RENAME COLUMN "createdAt" TO created_at;
ALTER TABLE orders RENAME COLUMN "updatedAt" TO updated_at;

------------------------------------------------
-- 2) OrderItem → order_item
------------------------------------------------
ALTER TABLE "OrderItem" RENAME TO order_item;

ALTER TABLE order_item RENAME COLUMN "orderId" TO order_id;
ALTER TABLE order_item RENAME COLUMN "productId" TO product_id;
ALTER TABLE order_item RENAME COLUMN "subTotal" TO sub_total;

------------------------------------------------
-- 3) Payment (Camel columns only)
------------------------------------------------
ALTER TABLE payment RENAME COLUMN "orderId" TO order_id;
ALTER TABLE payment RENAME COLUMN "userId" TO user_id;
ALTER TABLE payment RENAME COLUMN "paymentKey" TO payment_key;
ALTER TABLE payment RENAME COLUMN "transactionId" TO transaction_id;

-- Postgres typically allows this rename safely.
-- No quotes needed after rename (snake_case).

------------------------------------------------
-- 4) Coupon → coupon
------------------------------------------------
ALTER TABLE "Coupon" RENAME TO coupon;

ALTER TABLE coupon RENAME COLUMN "maximumDiscount" TO maximum_discount;
ALTER TABLE coupon RENAME COLUMN "minimumCost" TO minimum_cost;
ALTER TABLE coupon RENAME COLUMN "isActive" TO is_active;
ALTER TABLE coupon RENAME COLUMN "expireDate" TO expire_date;

------------------------------------------------
-- 5) CouponIssue → coupon_issue
------------------------------------------------
ALTER TABLE "CouponIssue" RENAME TO coupon_issue;

ALTER TABLE coupon_issue RENAME COLUMN "isActive" TO is_active;
ALTER TABLE coupon_issue RENAME COLUMN "createdAt" TO created_at;
ALTER TABLE coupon_issue RENAME COLUMN "deletedAt" TO deleted_at;
ALTER TABLE coupon_issue RENAME COLUMN "userId" TO user_id;
ALTER TABLE coupon_issue RENAME COLUMN "couponId" TO coupon_id;

------------------------------------------------
-- 6) Raffle 테이블 (raffles) — Camel columns only
------------------------------------------------
ALTER TABLE raffles RENAME COLUMN "raffleId" TO raffle_id;

------------------------------------------------
-- 7) already snake_case tables
-- cart, cart_item, product, users, payment(most), raffle_tickets, raffle_winners, notifications
-- → no table rename required
------------------------------------------------

-- ============================================
-- END OF V2 MIGRATION
-- ============================================
