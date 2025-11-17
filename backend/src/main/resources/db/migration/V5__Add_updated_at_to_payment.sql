-- ============================================
-- V5: Add updated_at column to payment table
-- ============================================

ALTER TABLE payment
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
