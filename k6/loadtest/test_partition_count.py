"""collect 의 파티션별 오프셋 · 랙 읽기 시험 - payment-events 의 파티션 수(12 → 2)에 상관없이 읽은 파티션 수만큼 값을 돌려준다
fixtures/partitions-2: 파티션 2개의 offsets_before.txt · offsets_after.txt 와 두 묶음의 lag.log(파티션 0 의 랙 3 → 1, 파티션 1 의 랙 0 → 7)"""
import os
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import collect  # noqa: E402

FIXTURE = os.path.join(HERE, "fixtures", "partitions-2")


class PartitionCountTest(unittest.TestCase):
    def test_파티션_2개의_끝_오프셋은_2개_값이다(self):
        self.assertEqual(collect.offsets(os.path.join(FIXTURE, "offsets_before.txt")), [10, 20])
        self.assertEqual(collect.offsets(os.path.join(FIXTURE, "offsets_after.txt")), [15, 31])

    def test_파티션_2개의_랙_최댓값은_2개_값이다(self):
        self.assertEqual(collect.lag_max(os.path.join(FIXTURE, "lag.log")), [3, 7])

    def test_파티션_12개도_12개_값으로_읽는다(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)
        path = os.path.join(tmp, "offsets.txt")
        with open(path, "w") as f:
            f.write("".join("%d %d\n" % (p, 100 + p) for p in range(12)))
        self.assertEqual(collect.offsets(path), [100 + p for p in range(12)])

    def test_파일이_없으면_빈_목록이다(self):
        self.assertEqual(collect.offsets("/nonexistent/offsets.txt"), [])
        self.assertEqual(collect.lag_max("/nonexistent/lag.log"), [])


if __name__ == "__main__":
    unittest.main()
