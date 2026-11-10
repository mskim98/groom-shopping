#!/usr/bin/env bash
# 결제·쿠폰 토픽 7개를 지우고 다시 만든다(적체된 메시지를 비우고 파티션 수를 되돌린다)
# 사용: bash db/seed/recreate-topics.sh
# 환경 변수(기본값):
#   SEED_KAFKA_CONTAINER  Kafka 컨테이너 (dev-kafka) — 컨테이너 안의 localhost:9093 으로 kafka-topics 를 실행한다
#   SEED_TOPIC_WAIT_SEC   삭제가 목록에서 사라지기를 기다리는 최대 초 (30)
# 지우는 것: payment-events(12 파티션) · payment-events-dlq · coupon-delay-events · payment-compensation-dlq · stock-threshold-events ·
#   coupon-activate-events · coupon-delay-dlq(각 1 파티션) 이 7개뿐. drawing-execute-topic 과 그 밖의 토픽은 건드리지 않는다
# 삭제가 목록에서 사라진 것을 확인한 뒤에 만든다(삭제 도중에 만들면 같은 이름이 거절되거나 옛 파티션이 남는다)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"

WAIT="${SEED_TOPIC_WAIT_SEC:-30}"
# 이름:파티션 수 — 파티션 수는 KafkaConfig 의 NewTopic 빈과 같다(payment-events 12, 나머지 1)
TOPICS="payment-events:12 payment-events-dlq:1 coupon-delay-events:1 payment-compensation-dlq:1 stock-threshold-events:1 coupon-activate-events:1 coupon-delay-dlq:1"

# 이름 7개를 정규식 하나로 묶어 한 번에 지우고 목록에서 사라질 때까지 기다린다(kafka-topics 호출마다 JVM 이 떠서 한 번에 모은다)
NAMES="$(for spec in $TOPICS; do printf '%s ' "${spec%%:*}"; done)"
REGEX="($(printf '%s' "${NAMES% }" | tr ' ' '|'))"
seed_kafka kafka-topics --delete --if-exists --topic "$REGEX" > /dev/null
waited=0
while seed_kafka kafka-topics --list | grep -Eqx "$REGEX"; do
  [ "$waited" -lt "$WAIT" ] || { echo "topics still listed after ${WAIT}s: $(seed_kafka kafka-topics --list | grep -Ex "$REGEX" | tr '\n' ' ')" >&2; exit 1; }
  sleep 1
  waited=$((waited + 1))
done
for spec in $TOPICS; do
  topic="${spec%%:*}"
  partitions="${spec##*:}"
  seed_kafka kafka-topics --create --topic "$topic" --partitions "$partitions" --replication-factor 1 > /dev/null
  echo "created $topic ($partitions partitions)"
done
