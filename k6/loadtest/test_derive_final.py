"""derive-final.py 시험 - 커밋된 작은 원시 폴더(fixtures/derive-root)로 결과 JSON 네 개를 만든다. 스택 없이 돈다
fixtures/derive-root: flash/tiny 와 coupon-100rps · pgcancel 은 condition.json 이 있고, flash/virtual-2000 · coupon-500rps · detail 은 없다"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "fixtures", "derive-root")
TOOL = os.path.join(HERE, "derive-final.py")
FIELDS = {"threads", "leak_detection", "seed_copy", "diag_samplers", "servers"}


def jload(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def jdump(obj, path):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f)


def run(*args):
    r = subprocess.run([sys.executable, "-B", TOOL] + [str(a) for a in args], capture_output=True, text=True, cwd=HERE)
    assert r.returncode == 0, r.stderr[-800:]
    return r


class DeriveFinalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.out = tempfile.mkdtemp()
        run("all", ROOT, cls.out)
        cls.j = {n: jload(os.path.join(cls.out, n + ".json")) for n in ("result", "coupon", "pg-cancel", "detail")}

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.out, True)

    def test_all_은_네_JSON_을_만든다(self):
        self.assertEqual(sorted(os.listdir(self.out)), ["coupon.json", "detail.json", "pg-cancel.json", "result.json"])

    def test_조건_파일이_있으면_값을_그대로_쓰고_condition_inferred_는_빈_목록(self):
        c = self.j["coupon"]
        self.assertEqual((c["threads"], c["leak_detection"], c["seed_copy"], c["servers"]), ("virtual", False, "2026-11-11T00:00:00Z", 2))
        self.assertEqual(c["condition_inferred"], [])
        self.assertEqual(self.j["pg-cancel"]["servers"], 2)
        self.assertEqual(self.j["pg-cancel"]["condition_inferred"], [])

    def test_조건_파일이_없으면_값은_null_이고_이름이_condition_inferred_에_든다(self):
        d = self.j["detail"]
        for f in FIELDS:
            self.assertIsNone(d[f], f)
        self.assertEqual(set(d["condition_inferred"]), FIELDS)
        self.assertNotIn("condition_note", d)

    def test_조건_파일에_일부_값이_없으면_그_값만_null(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)
        d = os.path.join(tmp, "pgcancel")
        shutil.copytree(os.path.join(ROOT, "pgcancel", "pgcancel"), d)
        cond = jload(os.path.join(d, "condition.json"))
        del cond["servers"]
        del cond["seed_copy"]
        jdump(cond, os.path.join(d, "condition.json"))
        run("pgcancel", d, os.path.join(tmp, "o.json"))
        o = jload(os.path.join(tmp, "o.json"))
        self.assertEqual((o["servers"], o["seed_copy"], o["threads"]), (None, None, "virtual"))
        self.assertEqual(sorted(o["condition_inferred"]), ["seed_copy", "servers"])

    def test_servers_는_모든_JSON_에_있다(self):
        steps = self.j["result"]["steps"]
        self.assertEqual(self.j["result"]["tiny"]["condition"]["servers"], 2)
        self.assertEqual(self.j["coupon"]["servers"], 2)
        for name in ("coupon", "pg-cancel", "detail"):
            self.assertIn("servers", self.j[name], name)
        self.assertIn("servers", steps[0]["condition"])

    def test_조건_없는_플래시_단계는_null_과_condition_inferred(self):
        s = self.j["result"]["steps"][0]
        self.assertEqual(s["run_label"], "virtual-2000")
        self.assertIsNone(s["condition"]["servers"])
        self.assertIsNone(s["threads"])
        self.assertIn("servers", s["condition"]["condition_inferred"])
        self.assertEqual(self.j["result"]["tiny"]["condition"]["condition_inferred"], [])

    def test_백엔드별_요청_수는_backends_tsv_의_이름만_센다(self):
        self.assertEqual(self.j["result"]["tiny"]["backend_requests"], {"backend1": 2, "backend2": 1})
        self.assertEqual(self.j["result"]["steps"][0]["backend_requests"], {"backend1": 0, "backend2": 2})

    def test_쿠폰_500rps_는_other_rates_에_든다(self):
        c = self.j["coupon"]
        self.assertEqual(c["rate"], 100)
        self.assertEqual([o["rate"] for o in c["other_rates"].values()], [500])
        self.assertEqual((c["quantity"], c["issued"], c["coupon_id"]), (1000, 1000, 7))

    def test_상세의_배경_상품_select_는_all_이_쿠폰_실행_표에서_빼고_출처를_적는다(self):
        d = self.j["detail"]
        self.assertEqual((d["db_body_loads_raw_two_statements"], d["db_body_loads_background_excluded"], d["db_body_loads"]), (557, 153, 404))
        self.assertEqual(d["db_body_loads_background_source"], "coupon-100rps/coupon/pgss.tsv")
        self.assertEqual(d["db_quantity_reads"], 15914)

    def test_배경_표가_없으면_빼지_않고_0_이라고_적는다(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)
        run("detail", os.path.join(ROOT, "detail", "detail"), os.path.join(tmp, "d.json"))
        d = jload(os.path.join(tmp, "d.json"))
        self.assertEqual((d["db_body_loads"], d["db_body_loads_background_excluded"]), (557, 0))

    def test_PG_취소_수치는_원시_파일에서_나온다(self):
        p = self.j["pg-cancel"]
        self.assertEqual((p["cancel_calls"], p["cancel_p95_ms"], p["batch_cycles_run"], p["batch_cycles_skipped_open"], p["fault_window_s"]), (2, 314, 1, 1, 180))
        self.assertEqual(p["pg_not_reached_ratio"], 0.5)

    def test_확정_구간_타이머는_유지_구간_시작과_끝_표본의_차이를_백엔드_구간별로_쓴다(self):
        cs = self.j["result"]["steps"][0]["confirm_stages"]
        self.assertEqual(cs["backend1"]["toss_call"], {"count": 100, "sum_s": 20.0, "mean_ms": 200.0})
        self.assertEqual(cs["backend1"]["consumer_receive_to_commit"], {"count": 50, "sum_s": 5.0, "mean_ms": 100.0})
        # 시작 표본에 없는 구간은 0 에서 센다
        self.assertEqual(cs["backend2"]["toss_call"]["count"], 5)

    def test_확정_구간_파일이_없는_단계는_빈_dict(self):
        self.assertEqual(self.j["result"]["tiny"]["confirm_stages"], {})

    def test_쿠폰_단계_타이머와_느린_Redis_줄이_coupon_json_에_든다(self):
        c = self.j["coupon"]
        self.assertEqual(c["coupon_stages"]["backend1"]["accept_lua"], {"count": 1500, "sum_s": 3.0, "mean_ms": 2.0})
        self.assertEqual(c["coupon_stages"]["backend2"]["status_redis_read"]["count"], 100)
        self.assertEqual(c["redis_slow_lines"], {"backend1": 2, "backend2": 0})
        self.assertEqual(c["redis_slow_suppressed"], {"backend1": 4, "backend2": 0})

    def test_쿠폰_파일이_없는_실행은_coupon_stages_가_빈_dict_이고_줄_수는_빈_dict(self):
        o = self.j["coupon"]["other_rates"]["500rps"]
        self.assertEqual((o["coupon_stages"], o["redis_slow_lines"]), ({}, {}))

    def test_nginx_상태는_경로별로도_센다(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)
        d = os.path.join(tmp, "step-300")
        shutil.copytree(os.path.join(ROOT, "flash", "virtual-2000", "step-2000"), d)
        with open(os.path.join(d, "nginx.log"), "a") as f:
            f.write("2026-11-11T00:00:02+00:00 POST /api/v1/cart/add 503 0.201 10.0.0.1:8080 0.201\n")
        sys.path.insert(0, HERE)
        import collect
        by_path = collect.collect_step(d, 300)["nginx_status_by_path"]
        self.assertEqual(by_path, {"/api/v1/order": {"200": 2}, "/api/v1/cart/add": {"503": 1}})

    def test_tiny_폴더가_없어도_단계_폴더가_있으면_result_json_을_만들고_tiny_는_null(self):
        root = tempfile.mkdtemp()
        out = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, root, True)
        self.addCleanup(shutil.rmtree, out, True)
        shutil.copytree(ROOT, os.path.join(root, "raw"))
        shutil.rmtree(os.path.join(root, "raw", "flash", "tiny"))
        run("all", os.path.join(root, "raw"), out)
        res = jload(os.path.join(out, "result.json"))
        self.assertIsNone(res["tiny"])
        self.assertEqual(len(res["steps"]), len(self.j["result"]["steps"]))

    def test_같은_원시_폴더에서_두_번_만들면_같다(self):
        again = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, again, True)
        run("all", ROOT, again)
        for n in ("result", "coupon", "pg-cancel", "detail"):
            self.assertEqual(jload(os.path.join(again, n + ".json")), self.j[n], n)


if __name__ == "__main__":
    unittest.main()
