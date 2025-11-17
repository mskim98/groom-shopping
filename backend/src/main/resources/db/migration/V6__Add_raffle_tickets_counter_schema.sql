-- ============================================
-- Create table: raffle_ticket_counters
-- ============================================

CREATE TABLE IF NOT EXISTS raffle_ticket_counters (
  raffle_id BIGINT PRIMARY KEY,
  current_value BIGINT NOT NULL DEFAULT 0
);
