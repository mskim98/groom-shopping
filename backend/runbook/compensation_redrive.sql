-- ============================================
-- compensation_redrive.sql
-- GIVEN_UP 으로 종결된 결제 보상 건을 운영자 판단 하에 재시도 대상으로 되돌리는 런북
-- ============================================
--
-- ① 관리 API 가 아니라 SQL 인 이유
-- GIVEN_UP 은 재시도 한도를 넘겨 정상 자동화 경로가 포기한 종단이다. 그 종단을 되돌리는 결정은
-- 코드가 자동으로 내릴 수 없고, 운영자가 Toss 콘솔·로그로 원인(예: 일시적 PG 장애인지 결제 자체의
-- 영구 실패인지)을 먼저 확인해야 한다. 관리 API 를 만들면 그 확인 없이도 버튼 한 번으로 되돌릴
-- 통로가 생긴다. SQL 로 남겨 두면 실행 전 ③ 확인 쿼리로 대상을 직접 본 사람만 돌리게 된다
--
-- ② 엔티티 전이표(PaymentCompensation.ALLOWED_TRANSITIONS)에 GIVEN_UP → PENDING 을 넣지 않는 이유
-- 그 표는 애플리케이션 코드 경로(배치·서비스)가 절대로 종단을 되돌리지 않는다는 보장이다.
-- 운영자의 예외적 개입은 그 보장 밖에서 SQL 로 직접 하고 코드에는 흔적을 남기지 않는다.
-- 표에 GIVEN_UP → PENDING 을 추가하면 어떤 코드 호출부든 실수로 종단을 되돌릴 길이 열린다
--
-- ③ 실행 전 확인 쿼리 - 대상이 실제로 GIVEN_UP 이고 몇 건인지, 실패 사유가 무엇인지 먼저 본다
-- SELECT id, payment_key, status, retry_count, last_error
--   FROM payment_compensation
--  WHERE id = ANY (ARRAY[:ids]::uuid[]);
--
-- ④ 실행 후 확인 쿼리 - PENDING 전환과 retry_count 초기화, next_retry_at 갱신을 확인한다
-- SELECT id, status, retry_count, next_retry_at
--   FROM payment_compensation
--  WHERE id = ANY (ARRAY[:ids]::uuid[]);
--
-- 사용법: :ids 를 재구동 대상 UUID 목록으로 치환한 뒤 실행한다
-- 예: :ids → '11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222'
--
-- id 목록에 GIVEN_UP 이 아닌 행이 섞여 있어도 WHERE 의 status 조건이 막는다 - 대상 선별의
-- 최종 방어선은 id 목록이 아니라 이 status 조건이다

UPDATE payment_compensation
   SET status = 'PENDING',
       retry_count = 0,
       next_retry_at = now(),
       updated_at = now()
 WHERE status = 'GIVEN_UP'
   AND id = ANY (ARRAY[:ids]::uuid[]);
