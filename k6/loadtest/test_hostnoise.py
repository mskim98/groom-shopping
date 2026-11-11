"""hostnoise.py · collect.py 의 host 필드 시험 - 판정 규칙은 Ruling R123-18
fixtures/host-noise: top-spike(한 표본만 초과) · top-consecutive(연속 두 표본 초과) · top-services(서비스 · Docker VM · k6 · Orca · Claude 가 90 %) ·
step-quiet · step-noisy(단계 폴더의 host-top.txt). 첫 표본(부팅 이후 평균)은 건너뛴다"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import collect  # noqa: E402
import hostnoise  # noqa: E402

FX = os.path.join(HERE, "fixtures", "host-noise")


class HostNoiseTest(unittest.TestCase):
    def test_사용자_앱이_한_표본만_치솟으면_조용하다(self):
        # 첫 표본(95 %)은 건너뛰고, 둘째 표본(90 %)만 문턱을 넘는다
        self.assertEqual(hostnoise.noisy(os.path.join(FX, "top-spike.txt")), {})

    def test_사용자_앱이_연속_두_표본_문턱을_넘으면_시끄럽다(self):
        r = hostnoise.noisy(os.path.join(FX, "top-consecutive.txt"))
        self.assertEqual(list(r), ["Google Chrome Helper (Renderer)"])
        self.assertEqual(r["Google Chrome Helper (Renderer)"], {"max": 60.0, "consecutive": 2, "samples_above": 2})

    def test_맥_서비스와_Docker_VM_k6_Orca_Claude_는_90퍼센트여도_조용하다(self):
        self.assertEqual(hostnoise.noisy(os.path.join(FX, "top-services.txt")), {})

    def test_시작_전_한_표본_검사는_한_번만_넘어도_시끄럽다(self):
        # --any: 첫 표본도 보고 한 표본이면 충분하다
        r = hostnoise.noisy(os.path.join(FX, "top-spike.txt"), any_one=True, skip_first=False)
        self.assertEqual(r["Google Chrome Helper (Renderer)"]["max"], 95.0)

    def test_명령줄은_끝_표본으로_JSON_을_찍고_조용하면_빈_객체다(self):
        out = subprocess.run([sys.executable, "-B", os.path.join(HERE, "hostnoise.py"), os.path.join(FX, "top-spike.txt")],
                             capture_output=True, text=True, check=True).stdout
        self.assertEqual(json.loads(out), {})


class CollectHostTest(unittest.TestCase):
    def test_단계_폴더의_host_는_noise_offenders_samples_를_쓴다(self):
        noisy = collect.collect_step(os.path.join(FX, "step-noisy"), 50)["host"]
        self.assertEqual(set(noisy), {"noise", "offenders", "samples"})
        self.assertTrue(noisy["noise"])
        self.assertEqual(list(noisy["offenders"]), ["Google Chrome Helper (Renderer)"])
        self.assertEqual(noisy["samples"], 3)  # 첫 표본을 뺀 표본 수
        quiet = collect.collect_step(os.path.join(FX, "step-quiet"), 50)["host"]
        self.assertEqual(quiet, {"noise": False, "offenders": {}, "samples": 3})

    def test_top_표본이_없는_옛_폴더의_host_는_noise_null_이다(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)
        self.assertEqual(collect.collect_step(tmp, 50)["host"], {"noise": None, "offenders": {}, "samples": 0})

    def test_derive_final_flash_가_host_를_result_json_에_싣는다(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)
        shutil.copytree(os.path.join(FX, "step-noisy"), os.path.join(tmp, "flash", "virtual-1", "step-1"))
        out = os.path.join(tmp, "result.json")
        subprocess.run([sys.executable, "-B", os.path.join(HERE, "derive-final.py"), "flash", os.path.join(tmp, "flash"), out],
                       capture_output=True, text=True, check=True, cwd=HERE)
        with open(out, encoding="utf-8") as f:
            step = json.load(f)["steps"][0]
        self.assertTrue(step["host"]["noise"])


if __name__ == "__main__":
    unittest.main()
