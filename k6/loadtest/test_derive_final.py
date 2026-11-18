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


SUMMARY_KEYS = ["code", "sync_200", "accepted_202", "sold_out_409", "duplicate", "unavailable_503", "success", "failed", "timeout",
                "time_to_success_ms", "issued_rows", "quantity", "duplicate_rows", "db_connections_peak", "wake", "fallback", "redis_commands",
                "no_token", "db_lock_waits_peak", "coupon_stages"]
SUMMARY_FIXTURES = os.path.join(HERE, "fixtures", "coupon-summary")


def coupon_summary(name, code="base", mutate=None):
    return coupon_outputs(name, code, mutate)[0]


def coupon_outputs(name, code="base", mutate=None):
    """fixtures/coupon-summary/<name> 를 복사해 mutate(폴더) 를 적용한 뒤 coupon 하위 명령을 CODE 환경 변수와 함께 돌려 (summary.json, coupon.json) 을 읽는다"""
    tmp = tempfile.mkdtemp()
    d = os.path.join(tmp, "coupon")
    shutil.copytree(os.path.join(SUMMARY_FIXTURES, name), d)
    if mutate:
        mutate(d)
    env = dict(os.environ, CODE=code)
    r = subprocess.run([sys.executable, "-B", TOOL, "coupon", d, os.path.join(tmp, "coupon.json")], capture_output=True, text=True, cwd=HERE, env=env)
    assert r.returncode == 0, r.stderr[-800:]
    out = (jload(os.path.join(tmp, "summary.json")), jload(os.path.join(tmp, "coupon.json")))
    shutil.rmtree(tmp, True)
    return out


def write(d, name, text):
    with open(os.path.join(d, name), "w") as f:
        f.write(text)


class CouponSummaryTest(unittest.TestCase):
    def test_동기_실행의_요약은_MB_1_키를_전부_싣는다(self):
        s = coupon_summary("sync", code="new")
        self.assertEqual([k for k in SUMMARY_KEYS if k not in s], [])
        self.assertEqual((s["code"], s["sync_200"], s["accepted_202"], s["success"], s["quantity"], s["issued_rows"]), ("new", 1000, 0, 0, 1000, 1000))

    def test_비동기_실행의_요약은_202_와_SUCCESS_수를_센다(self):
        s = coupon_summary("async")
        self.assertEqual((s["code"], s["accepted_202"], s["success"], s["sync_200"], s["sold_out_409"], s["duplicate"]), ("base", 1000, 1000, 0, 1500, 500))

    def test_최종_응답까지의_시간은_p50_p95_p99_이다(self):
        self.assertEqual(coupon_summary("sync")["time_to_success_ms"], {"p50": 18, "p95": 61, "p99": 90})
        self.assertEqual(coupon_summary("async")["time_to_success_ms"], {"p50": 150, "p95": 244, "p99": 380})

    def test_발급_행과_중복_행은_파일에서_읽는다(self):
        s = coupon_summary("sync", mutate=lambda d: (write(d, "issued-db.txt", "998\n"), write(d, "duplicate-rows.txt", "3\n")))
        self.assertEqual((s["issued_rows"], s["duplicate_rows"]), (998, 3))

    def test_DB_연결_최대는_표본_파일의_최댓값이다(self):
        self.assertEqual(coupon_summary("sync")["db_connections_peak"], 47)
        s = coupon_summary("sync", mutate=lambda d: write(d, "db-connections.txt", "5|0\n9|3\n"))
        self.assertEqual((s["db_connections_peak"], s["db_lock_waits_peak"]), (9, 3))

    def test_잠금_대기_최대는_표본_파일_두_번째_열의_최댓값이다(self):
        self.assertEqual(coupon_summary("sync")["db_lock_waits_peak"], 5)

    def test_잠금_대기_열이_없는_옛_표본_파일은_null_이다(self):
        s = coupon_summary("sync", mutate=lambda d: write(d, "db-connections.txt", "5\n9\n"))
        self.assertEqual((s["db_connections_peak"], s["db_lock_waits_peak"]), (9, None))

    def test_토큰이_없어_건너뛴_반복은_no_token_으로_요약에_실린다(self):
        self.assertEqual(coupon_summary("sync")["no_token"], 37)
        self.assertEqual(coupon_summary("async")["no_token"], 0)

    def test_coupon_json_의_path_는_실행의_발급_경로를_따른다(self):
        sync_path = coupon_outputs("sync")[1]["path"]
        self.assertIn("POST /coupon/issue/{id}", sync_path)
        self.assertNotIn("issue-async/{id}", sync_path.split("status")[0])

    def test_단계_타이머_차이를_백엔드_단계별로_싣는다(self):
        c = coupon_summary("sync")["coupon_stages"]
        self.assertEqual(c["backend1"]["consumer_ack"], {"count": 40, "sum_s": 2.0, "mean_ms": 50.0})
        self.assertEqual(c["backend1"]["consumer_status_write"]["count"], 40)
        self.assertEqual(coupon_summary("async")["coupon_stages"], {})

    def test_깨우기_출처별_수와_대체_경로_수를_센다(self):
        s = coupon_summary("sync")
        self.assertEqual((s["wake"], s["fallback"]), ({"pubsub": 990, "key": 8, "fallback": 2}, 2))

    def test_깨우기_줄이_없는_실행은_wake_가_빈_dict_이고_fallback_은_0(self):
        s = coupon_summary("async")
        self.assertEqual((s["wake"], s["fallback"]), ({}, 0))

    def test_Redis_명령_수를_명령별로_싣는다(self):
        self.assertEqual(coupon_summary("async")["redis_commands"], {"evalsha": 3000, "xadd": 1000, "get": 9000})


if __name__ == "__main__":
    unittest.main()


R129 = os.path.join(HERE, "fixtures", "r129")


def r129_text(name):
    with open(os.path.join(R129, name), encoding="utf-8") as f:
        return f.read()


class DeriveR129Test(unittest.TestCase):
    """r129 측정 요약 키 - 원시 파일 한 줄씩을 요약 숫자로 바꾸는 순수 함수(derive_r129.py)를 작은 입력 파일로 시험한다"""

    @classmethod
    def setUpClass(cls):
        sys.path.insert(0, HERE)
        import derive_r129  # 모듈이 없을 때 이 클래스의 시험만 실패하게 여기서 불러온다
        cls.d = derive_r129

    def test_r129_answer_time_per_outcome_fills_all_four_outcomes(self):
        a = self.d.answer_ms_by_outcome(jload(os.path.join(R129, "k6-coupon.json")))
        self.assertEqual(list(a), ["issued", "sold_out", "duplicate", "other"])
        self.assertEqual((a["issued"]["count"], a["issued"]["p50"], a["issued"]["p95"], a["issued"]["p99"]), (1000, 420.5, 980.1, 1210.7))
        self.assertEqual(a["duplicate"], {"count": 0, "p50": None, "p95": None, "p99": None})
        self.assertEqual(a["other"]["p95"], 2100)

    def test_r129_db_calls_total_per_request_top_and_harness_statements_excluded(self):
        rows = self.d.parse_pgss(r129_text("pgss-end.tsv"))
        c = self.d.db_calls(rows, 3000)
        # 표본 문장(pg_stat_activity) 600 건은 측정 도구의 호출이라 합계에 들지 않는다
        self.assertEqual((c["total"], c["harness_excluded"]), (3250, 600))
        self.assertAlmostEqual(c["per_request"], 3250 / 3000, places=4)
        self.assertEqual([t["calls"] for t in c["top"]], [3000, 100, 50, 40, 30])
        self.assertEqual(c["coupon_calls"], 190)

    def test_r129_db_calls_excluding_setup_subtracts_the_snapshot_by_query_id(self):
        end, setup = (self.d.parse_pgss(r129_text(n)) for n in ("pgss-end.tsv", "pgss-setup.tsv"))
        c = self.d.db_calls(end, 3000, setup)
        self.assertEqual(c["total_excluding_setup"], 245)
        self.assertAlmostEqual(c["per_request_excluding_setup"], 245 / 3000, places=4)
        self.assertEqual(c["total"], 3250)

    def test_r129_hotrow_path_check_flash_needs_one_sale_row_per_confirmed_payment(self):
        f = self.d.hotrow_path_check
        self.assertIs(f(True, 500, 500, 10000, 10000), True)
        self.assertIs(f(True, 499, 500, 10000, 10000), False)

    def test_r129_hotrow_path_check_conditional_needs_no_ledger_and_stock_drop_equal(self):
        f = self.d.hotrow_path_check
        self.assertIs(f(False, 0, 500, 10000, 9500), True)
        self.assertIs(f(False, 3, 500, 10000, 9500), False)
        self.assertIs(f(False, 0, 500, 10000, 9501), False)

    def test_r129_toss_concurrent_peak_counts_overlapping_confirm_calls_only(self):
        j = jload(os.path.join(R129, "journal-bulkhead.json"))
        # 호출 구간 [1000,6000) [2000,7000) [6000,11000) [7000,7100) - 끝나는 순간에 시작한 호출은 겹치지 않는다. 취소 호출은 세지 않는다
        self.assertEqual(self.d.toss_concurrent_peak(j), 2)

    def test_r129_ratelimit_counts_split_passed_429_other_and_sent(self):
        c = self.d.ratelimit_counts({"200": 600, "204": 60, "429": 1330, "500": 2, "0": 8})
        self.assertEqual(c, {"sent": 2000, "passed": 660, "rejected_429": 1330, "other": 10})

    def test_r129_ratelimit_limit_is_read_from_the_nginx_lines(self):
        self.assertEqual(self.d.parse_limit(r129_text("limit-lines.txt")), {"rate": "30r/s", "burst": 60, "nodelay": True})

    def test_r129_ratelimit_expected_pass_follows_the_nginx_bucket(self):
        e = self.d.expected_pass(30, 60, 100, 20)
        self.assertTrue(650 <= e <= 670, e)
        self.assertEqual(self.d.expected_pass(30, 60, 20, 10), 200)

    def test_r129_outbox_dup_side_effects_unchanged_and_duplicated(self):
        before = {"payments_done": 100, "orders_confirmed": 100, "ledger_rows": 100, "product_stock": 9900}
        same = self.d.side_effect_delta(before, dict(before))
        self.assertEqual((same["delta"], same["duplicated"]), ({k: 0 for k in before}, False))
        after = dict(before, ledger_rows=200)
        dup = self.d.side_effect_delta(before, after)
        self.assertEqual((dup["delta"]["ledger_rows"], dup["duplicated"]), (100, True))
        with self.assertRaises(ValueError):
            self.d.side_effect_delta(before, {"payments_done": 100})

    def test_r129_outbox_dup_consumed_after_republish_is_the_offset_increase_of_the_topic(self):
        self.assertEqual(self.d.consumed_after_republish(r129_text("cg-before.txt"), r129_text("cg-after.txt")), 100)

    def test_r129_outbox_dup_log_marker_counts(self):
        text = "a [PAYMENT_EVENT_CONSUMED] x\nb [PAYMENT_APPROVED_SKIP] y\nc [PAYMENT_EVENT_CONSUMED] z\n"
        self.assertEqual(self.d.log_marker_counts(text, ["[PAYMENT_EVENT_CONSUMED]", "[PAYMENT_APPROVED_SKIP]"]),
                         {"[PAYMENT_EVENT_CONSUMED]": 2, "[PAYMENT_APPROVED_SKIP]": 1})

    def test_r129_compensation_money_without_order_is_approvals_minus_successful_cancels(self):
        c = self.d.compensation(jload(os.path.join(R129, "journal-comp.json")))
        self.assertEqual((c["toss_approved"], c["toss_canceled"], c["money_without_order"]), (3, 1, 2))
        # 같은 결제 키의 취소가 두 번 성공해도 한 번으로 센다. 500 응답 취소는 센 적 없다
        self.assertEqual(c["toss_cancel_calls"], 3)
        self.assertEqual(c["cancel_after_approval_ms"], {"p50": 30000, "p95": 30000, "count": 1})

    def test_r129_compensation_ignores_cancels_of_payments_this_run_never_approved(self):
        # 시드에 남은 보상 행이 부팅 뒤에 부르는 취소(pkOLD)는 이 실행의 결제가 아니다
        c = self.d.compensation(jload(os.path.join(R129, "journal-comp-foreign.json")))
        self.assertEqual((c["toss_approved"], c["toss_canceled"], c["money_without_order"]), (3, 1, 2))
        self.assertEqual((c["toss_cancel_calls"], c["foreign_cancel_calls"]), (3, 1))

    def test_r129_hikari_peaks_from_prom_samples(self):
        self.assertEqual(self.d.hikari_peaks(r129_text("prom.tsv")), {"active_peak": 50, "pending_peak": 31, "max": 50})

    def test_r129_lock_wait_samples_count_lock_rows_per_sample_time(self):
        self.assertEqual(self.d.lock_wait_samples(r129_text("pg-activity.tsv")), {"peak": 2, "mean": 1.0, "samples": 3})

    def test_r129_connection_peak_is_the_largest_session_count_of_one_sample_inside_the_hold_window(self):
        # 표본 시각 05:00:00.1 = 세션 2 · 05:00:01.1 = 0(빈 행) · 05:00:02.1 = 세션 3 (pid 가 있는 행만 센다 - 표본 문장이 보는 non-idle 세션)
        c = self.d.connection_peak(r129_text("pg-activity.tsv"), "2026-11-18T05:00:00Z", "2026-11-18T05:00:03Z")
        self.assertEqual(c, {"peak": 3, "samples": 3})
        self.assertEqual(self.d.connection_peak(r129_text("pg-activity.tsv"), "2026-11-18T05:00:00Z", "2026-11-18T05:00:02Z"), {"peak": 2, "samples": 2})

    def test_r129_connection_peak_without_samples_in_the_window_is_none(self):
        self.assertEqual(self.d.connection_peak(r129_text("pg-activity.tsv"), "2026-11-18T06:00:00Z", "2026-11-18T06:00:30Z"), {"peak": None, "samples": 0})

    def test_r129_hold_window_is_the_first_timestamps_of_the_two_stage_files(self):
        start = "2026-11-18T06:34:08Z\tbackend1\tpayment_confirm_stage_seconds_count{stage=\"x\"} 1\n"
        end = "2026-11-18T06:34:38Z\tbackend1\tpayment_confirm_stage_seconds_count{stage=\"x\"} 9\n"
        self.assertEqual(self.d.hold_window(start, end), ("2026-11-18T06:34:08Z", "2026-11-18T06:34:38Z"))
        self.assertIsNone(self.d.hold_window("", end))

    def test_r129_host_idle_skips_the_first_top_sample(self):
        self.assertEqual(self.d.host_idle(r129_text("host-top.txt")), {"min": 20.0, "median": 60.0, "samples": 3})

    def test_r129_breaker_transitions_are_seconds_from_the_pause_and_only_the_named_breaker(self):
        t = self.d.breaker_transitions(r129_text("backend-cb.log"), "redis-coupon", "2026-11-18T05:00:00Z")
        self.assertEqual([(x["from"], x["to"], x["at_s"]) for x in t],
                         [("CLOSED", "OPEN", 4.0), ("OPEN", "HALF_OPEN", 9.5), ("HALF_OPEN", "CLOSED", 20.0)])

    def test_r129_answers_in_window_counts_statuses_of_the_path_inside_the_window(self):
        a = self.d.answers_in_window(r129_text("nginx-fault.log"), "/coupon/issue/", "2026-11-18T05:00:05Z", "2026-11-18T05:00:15Z")
        self.assertEqual(a, {"200": 1, "409": 1, "503": 1})


class SummaryRequiredFilesR129Test(unittest.TestCase):
    """r129-summary.py 는 필수 원시 파일이 없으면 오류로 끝나고, 선택 파일(재고 UPDATE 문장 읽기 등)은 없어도 된다"""

    @classmethod
    def setUpClass(cls):
        import importlib.util
        spec = importlib.util.spec_from_file_location("r129_summary", os.path.join(HERE, "r129-summary.py"))
        cls.m = importlib.util.module_from_spec(spec)
        sys.path.insert(0, HERE)
        spec.loader.exec_module(cls.m)

    def raw_with_all_required(self, kind="hotrow"):
        raw = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, raw, True)
        os.makedirs(os.path.join(raw, "step-25"))
        for p in self.m.required_files(kind, raw):
            open(p, "w").close()
        return raw

    def run_main(self, kind, raw):
        return subprocess.run([sys.executable, "-B", os.path.join(HERE, "r129-summary.py"), kind, raw, os.path.join(raw, "out.json")],
                              capture_output=True, text=True)

    def test_r129_summary_required_files_include_pg_activity_but_not_the_optional_stock_update_read(self):
        raw = self.raw_with_all_required()
        names = [os.path.relpath(p, raw) for p in self.m.required_files("hotrow", raw)]
        self.assertIn("pg-activity.tsv", names)
        self.assertIn(os.path.join("step-25", "host-top.txt"), names)
        self.assertNotIn("pgss-stock-update.tsv", names)

    def test_r129_summary_all_required_present_and_optional_absent_reports_nothing_missing(self):
        raw = self.raw_with_all_required()
        self.assertEqual(self.m.required_missing("hotrow", raw), [])

    def test_r129_summary_missing_required_raw_file_exits_non_zero_and_names_the_file(self):
        raw = self.raw_with_all_required()
        os.remove(os.path.join(raw, "step-25", "host-top.txt"))
        r = self.run_main("hotrow", raw)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("host-top.txt", r.stderr)

    def test_r129_summary_empty_raw_dir_names_meta_json_instead_of_failing_with_a_traceback(self):
        raw = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, raw, True)
        r = self.run_main("bulkhead", raw)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("meta.json", r.stderr)
        self.assertNotIn("Traceback", r.stderr)
