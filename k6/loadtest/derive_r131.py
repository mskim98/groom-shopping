"""r131 측정 요약 키를 원시 파일 내용에서 계산하는 순수 함수 모음 - 스택 없이 돈다(docker · psql · 파일 읽기를 하지 않는다, 입력은 이미 읽은 문자열 · 목록)
r129-summary.py 가 부르고, 시험은 test_derive_final.py 의 DeriveR131Test 다. pct · parse_pgss · HARNESS_SQL 은 derive_r129 의 것을 그대로 쓴다"""
import re

from derive_r129 import HARNESS_SQL, parse_pgss, pct  # noqa: F401  (parse_pgss 는 시험과 요약이 이 모듈로 부른다)

# 결제 컨슈머 스레드 수의 기본값 - 환경 변수가 없으면 앱도 KafkaConfig 의 기본 2 를 쓴다
DEFAULT_CONSUMER_CONCURRENCY = 2
# 재고 노출 지연의 설계 상한(ms) - L2 만료 10 s + L1 1 s (ProductDetailCache 클래스 주석)
STOCK_VIEW_BOUND_MS = 11_000


def _env(text, name):
    """printenv · env 출력(KEY=VALUE 줄)에서 값 하나 - 없으면 None"""
    m = re.search(r"^%s=(.*)$" % re.escape(name), text, re.M)
    return m.group(1).strip() if m else None


def token_redis(env_text):
    """백엔드 환경에서 토큰 저장소 Redis 호스트 - TOKEN_REDIS_HOST 가 없으면 앱이 주 Redis 로 간다. separate = 토큰 호스트가 주 Redis 호스트와 다르다"""
    main = _env(env_text, "SPRING_DATA_REDIS_HOST")
    host = _env(env_text, "TOKEN_REDIS_HOST") or main
    return {"host": host, "main_host": main, "separate": host != main}


def writers(env_text, topic_describe_text):
    """한 상품 행에 쓸 수 있는 결제 컨슈머 쓰기 수 - 스레드 수는 백엔드 환경(없으면 기본 2), 파티션 수는 kafka-topics --describe 의 PartitionCount
    파티션 수를 환경이 아니라 토픽에서 읽는 이유: 환경과 토픽이 어긋난 실행(토픽이 옛 파티션 수 그대로)을 요약이 드러내야 한다"""
    n = _env(env_text, "PAYMENT_CONSUMER_CONCURRENCY")
    m = re.search(r"PartitionCount:\s*(\d+)", topic_describe_text)
    return {"consumer_concurrency": int(n) if n and n.isdigit() else DEFAULT_CONSUMER_CONCURRENCY, "partitions": int(m.group(1)) if m else None}


def group_members(describe_text, topic):
    """kafka-consumer-groups --describe 출력에서 토픽의 파티션을 받은 컨슈머 수(서로 다른 CONSUMER-ID) · 컨슈머가 배정된 파티션 수 · 나열된 파티션 수 - 스레드 수가 파티션 수를 넘으면 남는 스레드는 목록에 나오지 않는다"""
    members, listed, assigned = set(), 0, 0
    for line in describe_text.splitlines():
        f = line.split()
        if len(f) >= 7 and f[1] == topic and f[2].isdigit():
            listed += 1
            if f[6] != "-":
                assigned += 1
                members.add(f[6])
    return {"members": len(members), "partitions_assigned": assigned, "partitions_listed": listed}


def stock_view(pairs):
    """결제 확정 응답 시각과 상품 상세가 낮아진 수량을 처음 보인 시각(ms) 쌍 -> 지연 분포. 폴링 한도 안에 보이지 않은 쌍(None)은 not_seen 으로 세고 분포에서 뺀다
    over_11s = 보인 쌍 중 지연이 11,000 ms 를 넘은 수(보이지 않은 쌍은 한도(15 s)를 넘었지만 여기서 세지 않는다)"""
    delays = [seen - confirmed for confirmed, seen in pairs if seen is not None]
    return {"n": len(pairs), "seen": len(delays), "not_seen": len(pairs) - len(delays),
            "delay_ms": {"p50": pct(delays, 50), "p95": pct(delays, 95), "max": max(delays) if delays else None},
            "over_11s": sum(1 for x in delays if x > STOCK_VIEW_BOUND_MS),
            "definition": "delay = first product-detail answer showing a quantity below the one read before the confirm, minus the time the confirm was answered; a pair not seen within the poll limit is in not_seen and not in the percentiles; over_11s counts seen delays above 11,000 ms"}


def sold_out_db(outcomes, rows, setup_rows=None):
    """매진 요청의 쿠폰 표 접근 - 결과별 건수(answer_ms_by_outcome)와 pg_stat_statements 표에서 쿠폰 표를 건드린 문장의 호출 수(측정 도구 문장 제외)
    coupon_calls = 통계를 리셋한 뒤 실행 끝까지의 합, coupon_calls_excluding_setup = k6 준비 단계(로그인) 끝 시점의 같은 표를 뺀 합 - 문장 번호가 같은 행끼리 뺀다"""
    own = [r for r in rows if not HARNESS_SQL.search(r[2]) and re.search(r"coupon", r[2], re.I)]
    out = {"issued": outcomes["issued"]["count"], "sold_out": outcomes["sold_out"]["count"], "coupon_calls": sum(r[1] for r in own),
           "coupon_calls_excluding_setup": None, "top_coupon_statements": [{"query": r[2][:150], "calls": r[1]} for r in sorted(own, key=lambda r: -r[1])[:5]]}
    if setup_rows is not None:
        before = {r[0]: r[1] for r in setup_rows}
        out["coupon_calls_excluding_setup"] = sum(max(r[1] - before.get(r[0], 0), 0) for r in own)
    return out
