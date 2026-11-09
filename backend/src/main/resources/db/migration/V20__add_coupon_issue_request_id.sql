-- 발급 행에 접수 요청 ID 를 남긴다
--
-- 컨슈머는 쿠폰별로 커밋한 뒤에 Redis 상태 기록 · 결과 토픽 전송을 하므로, 그 단계가 실패하면 같은 배치가
-- 다시 온다. 재전달분의 행이 ON CONFLICT DO NOTHING 으로 "미삽입" 처럼 돌아오면 이미 처리한 요청인지
-- 진짜 중복인지 가를 근거가 없어 재고가 +1 된다. 충돌한 행의 request_id 가 이번 요청의 것과 같으면
-- 우리가 이미 처리한 요청이다
--
-- NULL = 이 컬럼이 생기기 전 행. 어떤 요청과도 같지 않으므로 진짜 중복으로 취급된다
ALTER TABLE coupon_issue ADD COLUMN IF NOT EXISTS request_id VARCHAR(36);

-- 재처리 정리(abandonBatch)가 요청 ID 묶음으로 행의 존재를 확인한다. NULL 행은 색인에서 뺀다
CREATE INDEX IF NOT EXISTS idx_coupon_issue_request_id
    ON coupon_issue (request_id) WHERE request_id IS NOT NULL;

COMMENT ON COLUMN coupon_issue.request_id IS '접수 시 부여한 요청 UUID. 재전달 멱등 판정용. NULL 이면 구 행';
