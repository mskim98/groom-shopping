"""서버 대수(SERVERS) 시험 - 스택 없이 돈다. 백엔드 집합 · nginx upstream · compose 의존 · 중지 도우미 · 앱 기동 · 조건 기록의 servers 를 검사한다
docker 는 PATH 앞에 둔 가짜 docker(호출을 FAKE_DOCKER_LOG 에 적는다)로 바꾸고, compose 시험만 진짜 `docker compose config` 를 쓴다"""
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
REAL_DOCKER = shutil.which("docker")
FAKE_DOCKER = r"""#!/usr/bin/env bash
echo "$*" >> "$FAKE_DOCKER_LOG"
if [ "$1" = ps ]; then printf '%s\n' $FAKE_RUNNING; fi
if [ "$1" = exec ]; then
  case "$*" in
    *printenv\ SPRING_THREADS_VIRTUAL_ENABLED*) echo true ;;
    *printenv\ SPRING_DATASOURCE_HIKARI_LEAK_DETECTION_THRESHOLD*) echo 0 ;;
    *curl*) echo '{"status":"UP"}' ;;
  esac
fi
exit 0
"""
SCRIPTS = ("env.sh", "run.sh", "final-rest.sh", "final-flash.sh", "final-pg-cancel.sh", "check-env.sh")


def sh(script, servers=None, extra=None, fake=None):
    env = dict(os.environ)
    env.pop("SERVERS", None)
    if servers is not None:
        env["SERVERS"] = str(servers)
    env.update(extra or {})
    if fake:
        env["PATH"] = fake + os.pathsep + env["PATH"]
    return subprocess.run(["bash", "-c", script], cwd=HERE, env=env, capture_output=True, text=True)


def code_lines(name):
    with open(os.path.join(HERE, name), encoding="utf-8") as f:
        return [l for l in f.read().splitlines() if not l.lstrip().startswith("#")]


class ServersTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.log = os.path.join(self.tmp, "docker.log")
        open(self.log, "w").close()
        with open(os.path.join(self.tmp, "docker"), "w") as f:
            f.write(FAKE_DOCKER)
        os.chmod(os.path.join(self.tmp, "docker"), 0o755)
        self.fake_env = {"FAKE_DOCKER_LOG": self.log, "SEED_STAMP": os.path.join(self.tmp, "stamp"), "APP_SETTLE_S": "0"}

    def calls(self):
        with open(self.log) as f:
            return f.read().splitlines()

    def backends(self, servers):
        r = sh('. ./env.sh && echo "$BACKENDS"', servers)
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.split()

    def test_SERVERS_없으면_세_대(self):
        self.assertEqual(self.backends(None), ["backend1", "backend2", "backend3"])

    def test_SERVERS_2_면_두_대(self):
        self.assertEqual(self.backends(2), ["backend1", "backend2"])

    def test_범위_밖_SERVERS_는_거부(self):
        for bad in ("0", "4", "abc", ""):
            r = sh('. ./env.sh && echo "$BACKENDS"', bad)
            self.assertNotEqual(r.returncode, 0, "SERVERS=%r accepted: %s" % (bad, r.stdout))

    def upstream(self, servers):
        r = sh('. ./env.sh && echo "$NGINX_CONF"', servers)
        self.assertEqual(r.returncode, 0, r.stderr)
        text = open(r.stdout.strip()).read()
        block = re.search(r"upstream\s+backend\s*\{(.*?)\}", text, re.S).group(1)
        return re.findall(r"^\s*server\s+(\S+?):8080;", block, re.M)

    def test_nginx_upstream_은_백엔드_집합과_같다(self):
        self.assertEqual(self.upstream(2), ["backend1", "backend2"])
        self.assertEqual(self.upstream(3), ["backend1", "backend2", "backend3"])

    def compose_graph(self, servers):
        r = sh('. ./env.sh && $DC config --format json', servers, {"PATH": os.path.dirname(REAL_DOCKER) + os.pathsep + os.environ["PATH"]})
        self.assertEqual(r.returncode, 0, r.stderr[-500:])
        return json.loads(r.stdout)["services"]

    def reached_from_nginx(self, servers):
        svcs, seen, todo = self.compose_graph(servers), set(), ["nginx"]
        while todo:
            s = todo.pop()
            if s not in seen:
                seen.add(s)
                todo += list(svcs[s].get("depends_on") or {})
        return {s for s in seen if s.startswith("backend")}, svcs

    def test_compose_2대는_세_번째_백엔드를_끌어오지_않는다(self):
        got, svcs = self.reached_from_nginx(2)
        self.assertEqual(got, {"backend1", "backend2"})
        self.assertNotIn("frontend", set(svcs["nginx"].get("depends_on") or {}))

    def test_compose_3대는_세_백엔드를_모두_끌어온다(self):
        self.assertEqual(self.reached_from_nginx(3)[0], {"backend1", "backend2", "backend3"})

    def test_compose_nginx_는_집합에_맞는_설정을_마운트한다(self):
        svcs = self.compose_graph(2)
        targets = {v["target"]: v["source"] for v in svcs["nginx"]["volumes"]}
        conf = sh('. ./env.sh && echo "$NGINX_CONF"', 2).stdout.strip()
        self.assertEqual(targets["/etc/nginx/nginx.conf"], conf)

    def test_중지_도우미는_집합의_백엔드만_멈춘다(self):
        r = sh('. ./env.sh && stop_backends', 2, self.fake_env, self.tmp)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.calls(), ["stop nginx r119-measure-backend1-1 r119-measure-backend2-1"])
        open(self.log, "w").close()
        sh('. ./env.sh && stop_backends', None, self.fake_env, self.tmp)
        self.assertEqual(self.calls(), ["stop nginx r119-measure-backend1-1 r119-measure-backend2-1 r119-measure-backend3-1"])

    def test_final_스크립트는_중지_도우미를_쓰고_백엔드_이름을_적지_않는다(self):
        for name in ("final-rest.sh", "final-flash.sh"):
            text = "\n".join(code_lines(name))
            self.assertIn("stop_backends", text, name)
            self.assertNotIn("docker stop", text, name)
        for name in SCRIPTS:
            hits = [l for l in code_lines(name) if re.search(r"backend[0-9]", l)]
            self.assertEqual(hits, [], name)

    def test_앱_기동은_집합의_백엔드만_띄우고_건강_확인한다(self):
        r = sh('bash run.sh app', 2, self.fake_env, self.tmp)
        self.assertEqual(r.returncode, 0, r.stderr)
        calls = self.calls()
        ups = [c for c in calls if " up -d --no-build " in c]
        self.assertTrue(ups and ups[0].endswith("up -d --no-build backend1 backend2 nginx"), ups)
        self.assertEqual([c for c in calls if "backend3" in c], [])
        health = {m.group(1) for c in calls for m in [re.search(r"http://(backend\d):8080", c)] if m}
        self.assertEqual(health, {"backend1", "backend2"})

    def condition(self, running, *args):
        out = os.path.join(self.tmp, "cond")
        os.makedirs(out, exist_ok=True)
        env = dict(self.fake_env, FAKE_RUNNING=running)
        r = sh('. ./env.sh && write_condition "%s" %s' % (out, " ".join(args)), None, env, self.tmp)
        self.assertEqual(r.returncode, 0, r.stderr)
        return json.load(open(os.path.join(out, "condition.json")))

    def test_조건_기록의_servers_는_떠_있는_백엔드_컨테이너_수(self):
        names = "nginx db r119-measure-backend1-1 r119-measure-backend2-1"
        self.assertEqual(self.condition(names)["servers"], 2)
        self.assertEqual(self.condition(names + " r119-measure-backend3-1")["servers"], 3)
        self.assertEqual(self.condition("nginx db")["servers"], 0)

    def test_조건_기록은_스레드_누수감지_진단표본을_읽어_쓴다(self):
        c = self.condition("r119-measure-backend1-1")
        self.assertEqual((c["threads"], c["leak_detection"], c["diag_samplers"]), ("virtual", False, False))
        self.assertNotIn("hold_s", c)
        c = self.condition("r119-measure-backend1-1", "30", "10")
        self.assertEqual((c["hold_s"], c["ramp_s"]), (30, 10))


if __name__ == "__main__":
    unittest.main()
