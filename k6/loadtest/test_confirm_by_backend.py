"""collect.confirm_by_backend 시험 - 유지 구간(확정 구간 타이머 표본 두 개의 시각)에 끝난 결제 확정 응답을 백엔드별 200 · 202 · 그 밖으로 센다
fixtures/confirm-by-backend: 유지 구간은 두 백엔드의 시작 표본(00:00:10 · 00:00:11)과 끝 표본(00:00:40 · 00:00:41)이 겹치는 00:00:11 ~ 00:00:40(29 초)"""
import os
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import collect  # noqa: E402

FIXTURE = os.path.join(HERE, "fixtures", "confirm-by-backend")


class ConfirmByBackendTest(unittest.TestCase):
    def test_유지_구간에_끝난_확정_응답을_백엔드별_200_202_그_밖으로_센다(self):
        r = collect.confirm_by_backend(FIXTURE)
        self.assertEqual(r["window"], {"start": "2026-11-11T00:00:11", "end": "2026-11-11T00:00:40", "seconds": 29})
        # 구간 밖 줄(00:00:09 · 00:00:10 · 00:00:41)과 장바구니 줄은 세지 않고, 재시도 줄은 마지막 주소(backend2)에 센다
        self.assertEqual(r["by_backend"]["backend1"], {"200": 2, "202": 1, "other": 0})
        self.assertEqual(r["by_backend"]["backend2"], {"200": 4, "202": 0, "other": 1})

    def test_표본_파일이_없으면_전체_로그를_세고_구간은_null(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)
        d = os.path.join(tmp, "step")
        shutil.copytree(FIXTURE, d)
        os.remove(os.path.join(d, "confirm-stages-start.tsv"))
        os.remove(os.path.join(d, "confirm-stages-end.tsv"))
        r = collect.confirm_by_backend(d)
        self.assertIsNone(r["window"])
        self.assertEqual(r["by_backend"]["backend1"], {"200": 4, "202": 1, "other": 0})
        self.assertEqual(r["by_backend"]["backend2"], {"200": 5, "202": 0, "other": 1})


if __name__ == "__main__":
    unittest.main()
