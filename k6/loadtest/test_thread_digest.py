"""thread_digest.py 시험 - 고정한 덤프(fixtures/thread-dump-sample.txt, 덤프 2개)로 트랜잭션 안 요청 스레드의 상태 · 기다림 종류 묶음을 검사한다"""
import os
import unittest

import thread_digest

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURE = os.path.join(HERE, "fixtures", "thread-dump-sample.txt")


def load():
    with open(FIXTURE, encoding="utf-8") as f:
        return thread_digest.digest(f.read().splitlines())


class ThreadDigestTest(unittest.TestCase):
    def test_덤프는_두_개이고_덤프마다_트랜잭션_안_요청_스레드만_센다(self):
        # 1번: exec-1 · exec-2 · exec-3 (exec-4 는 트랜잭션 밖, Poller 는 트랜잭션 프레임이 없음, scheduling-1 은 http-nio 가 아님)
        # 2번: exec-1 · exec-2 · exec-3 · exec-5 · exec-6
        r = load()
        self.assertEqual(r["dumps"], 2)
        self.assertEqual([d["tx_threads"] for d in r["per_dump"]], [3, 5])
        self.assertEqual(r["tx_threads_total"], 8)

    def test_상태별_합계는_첫_낱말로_센다(self):
        self.assertEqual(load()["by_state"], {"RUNNABLE": 4, "WAITING": 2, "BLOCKED": 1, "TIMED_WAITING": 1})

    def test_기다림_종류별_합계는_여섯_가지로_갈린다(self):
        self.assertEqual(load()["by_kind"], {
            "JDBC socket read": 2, "LockSupport.park": 2, "logging": 1,
            "monitor wait": 1, "application frame": 1, "Hikari pool wait": 1,
        })

    def test_덤프마다_기다림_종류를_따로_센다(self):
        d1, d2 = load()["per_dump"]
        self.assertEqual(d1["by_kind"], {"JDBC socket read": 1, "LockSupport.park": 1, "logging": 1})
        self.assertEqual(d2["by_kind"], {"JDBC socket read": 1, "monitor wait": 1, "application frame": 1, "LockSupport.park": 1, "Hikari pool wait": 1})

    def test_가장_안쪽_프레임_묶음은_종류와_안쪽_프레임과_처음_만나는_애플리케이션_프레임으로_갈린다(self):
        rows = {g["key"]: g["count"] for g in load()["by_innermost_frame"]}
        app = "groom.backend.application.cart.CartApplicationService.addToCart(CartApplicationService.java:80)"
        self.assertEqual(rows["JDBC socket read | java.base/sun.nio.ch.SocketDispatcher.read0(Native Method) @ " + app], 2)
        self.assertEqual(rows["logging | java.base/java.io.FileOutputStream.writeBytes(Native Method) @ " + app], 1)
        self.assertEqual(rows["application frame | groom.backend.application.cart.CartApplicationService.addToCart(CartApplicationService.java:80) @ " + app], 1)
        self.assertEqual(sum(rows.values()), 8)

    def test_보고서_글은_합계와_종류를_담는다(self):
        text = thread_digest.format_report(load())
        self.assertIn("dumps 2", text)
        self.assertIn("JDBC socket read", text)


if __name__ == "__main__":
    unittest.main()
