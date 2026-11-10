"""leak_digest.py 시험 - 고정한 로그(fixtures/leak-sample.log)로 묶음 · 건수 · 점유 시간을 검사한다"""
import os
import unittest

import leak_digest

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURE = os.path.join(HERE, "fixtures", "leak-sample.log")
AWAIT = "groom.backend.application.payment.PaymentApplicationService.awaitResult(PaymentApplicationService.java:250)"
APPLY = "groom.backend.application.product.StockLedgerRepository.applyDecrement(StockLedgerRepository.java:104)"


def load():
    with open(FIXTURE, encoding="utf-8") as f:
        return leak_digest.digest(f.read().splitlines(), threshold_ms=2000)


class LeakDigestTest(unittest.TestCase):
    def test_첫_애플리케이션_프레임_묶음은_두_경로로_갈린다(self):
        by = {g["key"]: g for g in load()["by_first_app_frame"]}
        self.assertEqual(set(by), {AWAIT, APPLY})
        self.assertEqual(by[AWAIT]["count"], 3)
        self.assertEqual(by[APPLY]["count"], 1)

    def test_점유_시간은_보고_시각_더하기_문턱값에서_반환_시각까지다(self):
        g = {g["key"]: g for g in load()["by_first_app_frame"]}[AWAIT]
        # 10:00:05.000 → 10:00:06.500 (3500) · 10:00:07.200 → 10:00:10.400 (5200) · 10:00:20.000 → 10:00:26.100 (8100)
        self.assertEqual(sorted(g["held_ms"]), [3500, 5200, 8100])
        self.assertEqual(g["longest_ms"], 8100)
        self.assertEqual(g["returned"], 3)
        self.assertEqual(g["never_returned"], 0)

    def test_돌아오지_않은_연결은_따로_센다(self):
        g = {g["key"]: g for g in load()["by_first_app_frame"]}[APPLY]
        self.assertEqual(g["returned"], 0)
        self.assertEqual(g["never_returned"], 1)
        self.assertEqual(g["held_ms"], [])
        self.assertIsNone(g["longest_ms"])

    def test_같은_연결_번호가_다른_스레드에서_다시_쓰여도_짝이_섞이지_않는다(self):
        # PgConnection@1a2b3c4d 는 exec-7 (3500 ms) 과 exec-9 (8100 ms) 가 차례로 보고했다
        self.assertEqual(load()["totals"], {"reports": 4, "returned": 3, "never_returned": 1})

    def test_기다림_프레임_묶음은_종류와_그_위의_애플리케이션_프레임이다(self):
        by = {g["key"]: g for g in load()["by_waiting_frame"]}
        self.assertEqual(set(by), {"Thread.sleep @ " + AWAIT, "(no waiting frame) @ groom.backend.application.product.StockLedgerApplyJob.run(StockLedgerApplyJob.java:51)"})
        self.assertEqual(by["Thread.sleep @ " + AWAIT]["count"], 3)

    def test_기다림_종류_분류(self):
        k = leak_digest.waiting_kind
        self.assertEqual(k(["java.net.SocketInputStream.socketRead0(Native Method)"]), "JDBC socket read")
        self.assertEqual(k(["sun.nio.ch.SocketDispatcher.read0(Native Method)"]), "JDBC socket read")
        self.assertEqual(k(["java.util.concurrent.locks.LockSupport.park(LockSupport.java:371)"]), "LockSupport.park")
        self.assertEqual(k(["org.apache.hc.client5.http.impl.io.DefaultBHttpClientConnection.receiveResponseHeader(X.java:1)"]), "HTTP client read")
        self.assertIsNone(k(["com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:127)"]))

    def test_보고서_글은_묶음마다_건수와_최장_점유를_찍는다(self):
        text = leak_digest.format_report(load())
        self.assertIn("reports 4 · returned 3 · never returned 1", text)
        self.assertRegex(text, r"3\s+3\s+0\s+8100\s+" + AWAIT.replace("(", r"\(").replace(")", r"\)"))

    def test_스프링_프록시_프레임은_건너뛰고_호출한_쪽_프레임을_함께_적는다(self):
        lines = [
            "2026-11-11 10:00:05.000 [HikariPool-1:housekeeper] WARN  c.zaxxer.hikari.pool.ProxyLeakTask - Connection leak detection triggered for org.postgresql.jdbc.PgConnection@aa on thread tomcat-handler-1, stack trace follows",
            "java.lang.Exception: Apparent connection leak detected",
            "\tat com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:127)",
            "\tat groom.backend.application.cart.CartApplicationService$$SpringCGLIB$$0.addToCart(<generated>)",
            "\tat groom.backend.interfaces.cart.CartController.add(CartController.java:41)",
            "\tat groom.backend.infrastructure.security.JwtAuthenticationFilter.doFilterInternal(JwtAuthenticationFilter.java:137)",
        ]
        r = leak_digest.digest(lines)
        self.assertEqual(r["by_first_app_frame"][0]["key"], "groom.backend.application.cart.CartApplicationService.addToCart <- groom.backend.interfaces.cart.CartController.add(CartController.java:41)")
        # 기다림 프레임이 없으면 요청의 진입점(컨트롤러 · 소비자 · 작업)을 쓴다 - 보안 필터는 모든 요청의 바깥쪽이라 구별하지 못한다
        self.assertEqual(r["by_waiting_frame"][0]["key"], "(no waiting frame) @ groom.backend.interfaces.cart.CartController.add(CartController.java:41)")


if __name__ == "__main__":
    unittest.main()
