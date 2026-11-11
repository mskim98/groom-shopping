"""collect.wake_delta 시험 - 확정 대기가 무엇으로 끝났는지(payment_confirm_wake_total, 태그 source) 단계 시작과 끝 표본의 차이를 백엔드별로 낸다
fixtures/confirm-wake: 시작 표본에는 backend1 의 pubsub 10 · key 1, 끝 표본에는 backend1 의 pubsub 110 · key 4 · timeout 2(시작에 없던 source 는 0 에서 센다)와 backend2 의 pubsub 90"""
import os
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import collect  # noqa: E402

FX = os.path.join(HERE, "fixtures", "confirm-wake")


class ConfirmWakeTest(unittest.TestCase):
    def test_백엔드별_source_별_끝_빼기_시작을_센다(self):
        r = collect.wake_delta(os.path.join(FX, "confirm-stages-start.tsv"), os.path.join(FX, "confirm-stages-end.tsv"))
        self.assertEqual(r, {"backend1": {"pubsub": 100, "key": 3, "timeout": 2}, "backend2": {"pubsub": 90}})

    def test_표본_파일이_없으면_빈_객체다(self):
        self.assertEqual(collect.wake_delta("/nonexistent/a.tsv", "/nonexistent/b.tsv"), {})

    def test_collect_step_이_confirm_wake_를_단계_결과에_쓴다(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)
        shutil.copytree(FX, os.path.join(tmp, "step"))
        step = collect.collect_step(os.path.join(tmp, "step"), 50)
        self.assertEqual(step["confirm_wake"]["backend1"]["pubsub"], 100)


if __name__ == "__main__":
    unittest.main()
