"""pg_activity_digest.py 시험 - 고정한 표본(fixtures/pg-activity-sample.tsv)으로 묶음 · 건수 · 최장값을 검사한다"""
import os
import unittest

import pg_activity_digest

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURE = os.path.join(HERE, "fixtures", "pg-activity-sample.tsv")
UPD = "UPDATE product SET stock = stock - $1 WHERE id = $2 AND stock >= $1"
LOCKQ = "SELECT id FROM product WHERE id = $1 FOR UPDATE"
INS = "INSERT INTO payment (order_id, status) VALUES ($1, $2)"
ADV = "SELECT pg_advisory_xact_lock_shared($1)"
ADVX = "SELECT pg_advisory_xact_lock($1)"


def load():
    with open(FIXTURE, encoding="utf-8") as f:
        return pg_activity_digest.digest(f.read().splitlines(), min_idle_ms=2000)


class PgActivityDigestTest(unittest.TestCase):
    def test_표본_시각은_머리줄을_빼고_서로_다른_시각의_수다(self):
        self.assertEqual(load()["samples"], 6)

    def test_잠금_대기는_기다리는_문장과_막는_문장으로_묶는다(self):
        waits = {(w["waiting"], w["blocking"]): w for w in load()["lock_waits"]}
        self.assertEqual(set(waits), {(UPD, LOCKQ), (ADV, ADVX)})

    def test_같은_문장이_이어서_기다린_것은_한_번으로_세고_표본_수는_따로_센다(self):
        w = {(w["waiting"], w["blocking"]): w for w in load()["lock_waits"]}[(UPD, LOCKQ)]
        # pid 201 은 표본 1~3 에서 같은 문장으로 계속 기다렸고 pid 202 는 표본 2 에서 한 번이다
        self.assertEqual(w["count"], 2)
        self.assertEqual(w["samples"], 4)
        self.assertEqual(w["longest_ms"], 3200)

    def test_막는_세션의_상태를_함께_적는다(self):
        w = {(w["waiting"], w["blocking"]): w for w in load()["lock_waits"]}
        self.assertEqual(w[(UPD, LOCKQ)]["blocker_state"], "idle in transaction")
        self.assertEqual(w[(ADV, ADVX)]["blocker_state"], "active")
        self.assertEqual(w[(ADV, ADVX)]["wait_event"], "advisory")

    def test_잠금_대기_묶음이_하나뿐이면_건수_1_최장_400(self):
        w = {(w["waiting"], w["blocking"]): w for w in load()["lock_waits"]}[(ADV, ADVX)]
        self.assertEqual((w["count"], w["samples"], w["longest_ms"]), (1, 1, 400))

    def test_트랜잭션_안에서_논_세션은_2초_이상만_세고_같은_트랜잭션은_한_번이다(self):
        idle = {g["query"]: g for g in load()["idle_in_transaction"]}
        # pid 100: 표본 2 · 3 (2500 · 3500 ms) 이 2 초 이상 - 표본 5 의 새 트랜잭션(300 ms) 과 pid 102(900 ms) 는 세지 않는다
        self.assertEqual((idle[LOCKQ]["count"], idle[LOCKQ]["longest_ms"]), (1, 3500))
        self.assertEqual((idle[INS]["count"], idle[INS]["longest_ms"]), (1, 3100))
        self.assertEqual(set(idle), {LOCKQ, INS})

    def test_보고서_글은_묶음마다_건수와_최장값을_찍는다(self):
        text = pg_activity_digest.format_report(load())
        self.assertIn("samples 6", text)
        self.assertRegex(text, r"2\s+4\s+3200\s+Lock/transactionid\s+idle in transaction")
        self.assertRegex(text, r"1\s+3500\s+" + LOCKQ.replace("$", r"\$"))


if __name__ == "__main__":
    unittest.main()
