#!/usr/bin/env python3
"""Cross-language benchmark suite for Mini (docs/benchmarks.md, "Cross-language baseline").

    bench/run.py [--reps N] [--only a,b] [--langs x,y] [--out DIR] [--no-build] [--stats] [--update-expected]

For every program in bench/<name>/ and every language/variant it
  1. builds the implementation (C: clang -O2, Rust: rustc -C opt-level=3, Go: go build, Kotlin/JVM: kotlinc,
     Kotlin/Native: kotlinc-native -opt, Mini: `minic build --cflag -O2` with the C backend and with --backend=llvm),
  2. checks that stdout equals bench/<name>/expected.txt,
  3. runs it N times and keeps the best wall time; peak RSS comes from wait4()'s rusage of that very child,
  4. records the binary size (and the stripped size).
Kotlin/JVM additionally gets its cold-start time from bench/startup/hello.kt, like every other language.
Results go to <out>/<date>-<host>.json and <out>/<date>-<host>.md (default out: bench/results).
A tool that is not installed (kotlinc, kotlinc-native, rustc, go) is reported as "not measured", never silently dropped.
"""
import argparse, datetime, json, os, platform, re, shutil, socket, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BENCH = os.path.join(ROOT, "bench")

PROGRAMS = ["binarytrees", "nbody", "fannkuch", "spectralnorm", "mandelbrot", "wordcount", "strbuild", "json"]
# (id, label)
LANGS = [
    ("c", "C (clang -O2)"),
    ("rust", "Rust"),
    ("go", "Go"),
    ("kotlin-jvm", "Kotlin/JVM"),
    ("kotlin-native", "Kotlin/Native"),
    ("mini-managed", "Mini Managed (C)"),
    ("mini-managed-llvm", "Mini Managed (LLVM)"),
    ("mini-arena", "Mini Arena (C)"),
    ("mini-arena-llvm", "Mini Arena (LLVM)"),
    ("mini-strict", "Mini Strict (C)"),
    ("mini-strict-llvm", "Mini Strict (LLVM)"),
]
SHORT = {"c": "C", "rust": "Rust", "go": "Go", "kotlin-jvm": "JVM", "kotlin-native": "K/N", "mini-managed": "Mini-M", "mini-managed-llvm": "Mini-M-ll",
         "mini-arena": "Mini-A", "mini-arena-llvm": "Mini-A-ll", "mini-strict": "Mini-S", "mini-strict-llvm": "Mini-S-ll"}


def sh(cmd, env=None, timeout=900, cwd=None):
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env, timeout=timeout, cwd=cwd)
    return p.returncode, p.stdout


def which(name, env_var=None):
    if env_var and os.environ.get(env_var):
        return os.environ[env_var]
    return shutil.which(name)


def source(prog, lang_id):
    """The source file of a (program, language) pair, or None if that variant does not exist."""
    d = os.path.join(BENCH, prog)
    ext = {"c": "c", "rust": "rs", "go": "go", "kotlin-jvm": "kt", "kotlin-native": "kt", "mini-managed": "kt", "mini-managed-llvm": "kt",
           "mini-arena": "arena.kt", "mini-arena-llvm": "arena.kt", "mini-strict": "strict.kt", "mini-strict-llvm": "strict.kt"}[lang_id]
    f = os.path.join(d, f"{prog}.{ext}")
    return f if os.path.exists(f) else None


class Tools:
    def __init__(self):
        self.cc = which("clang", "CC") or which("gcc")
        self.rustc = which("rustc", "RUSTC")
        self.go = which("go", "GO")
        self.java = which("java", "JAVA")
        self.kotlinc = which("kotlinc", "KOTLINC")
        self.kotlinc_native = which("kotlinc-native", "KOTLINC_NATIVE")
        self.minic = os.path.join(ROOT, "minic")
        self.kotlin_stdlib = None
        if self.kotlinc:
            home = os.path.dirname(os.path.dirname(os.path.realpath(self.kotlinc)))
            cand = os.path.join(home, "lib", "kotlin-stdlib.jar")
            if os.path.exists(cand):
                self.kotlin_stdlib = cand

    def versions(self):
        def first(cmd):
            try:
                rc, out = sh(cmd, timeout=60)
                lines = [l for l in out.splitlines() if l.strip() and not l.startswith("Picked up")]
                return lines[0].strip() if lines else "?"
            except Exception as e:  # noqa
                return f"? ({e})"
        v = {}
        if self.cc: v["cc"] = first([self.cc, "--version"])
        if self.rustc: v["rustc"] = first([self.rustc, "--version"])
        if self.go: v["go"] = first([self.go, "version"])
        if self.java: v["java"] = first([self.java, "-version"])
        if self.kotlinc: v["kotlinc"] = first([self.kotlinc, "-version"])
        if self.kotlinc_native: v["kotlinc-native"] = first([self.kotlinc_native, "-version"])
        return v


def build(tools, prog, lang_id, src, outdir, stats=False):
    """Returns (cmd_to_run list or None, artifact_path, error string or None, build_seconds)."""
    os.makedirs(outdir, exist_ok=True)
    name = prog
    exe = os.path.join(outdir, name + (".stats" if stats else ""))
    t0 = time.perf_counter()
    err = None
    run_cmd = [exe]
    artifact = exe
    if lang_id == "c":
        if not tools.cc: return None, None, "no C compiler", 0
        rc, out = sh([tools.cc, "-O2", "-o", exe, src, "-lm"])
    elif lang_id == "rust":
        if not tools.rustc: return None, None, "rustc not installed", 0
        rc, out = sh([tools.rustc, "-C", "opt-level=3", "-C", "codegen-units=1", "-o", exe, src])
    elif lang_id == "go":
        if not tools.go: return None, None, "go not installed", 0
        env = dict(os.environ, GO111MODULE="off", CGO_ENABLED="0")
        rc, out = sh([tools.go, "build", "-o", exe, src], env=env)
    elif lang_id == "kotlin-jvm":
        if not (tools.kotlinc and tools.java and tools.kotlin_stdlib): return None, None, "kotlinc/java not installed", 0
        cls = os.path.join(outdir, name + ".classes")
        shutil.rmtree(cls, ignore_errors=True)
        rc, out = sh([tools.kotlinc, src, "-d", cls, "-nowarn"])
        main = name[0].upper() + name[1:] + "Kt"
        run_cmd = [tools.java, "-cp", cls + os.pathsep + tools.kotlin_stdlib, main]
        artifact = cls
    elif lang_id == "kotlin-native":
        if not tools.kotlinc_native: return None, None, "kotlinc-native not installed", 0
        rc, out = sh([tools.kotlinc_native, src, "-o", exe, "-opt", "-nowarn"], timeout=1800)
        exe += ".kexe"
        run_cmd = [exe]
        artifact = exe
    else:  # mini-*
        backend = ["--backend=llvm"] if lang_id.endswith("-llvm") else []
        srcs = [src]
        if "kotlin.math.sqrt" in open(src).read():
            srcs.append(os.path.join(BENCH, "mini", "sqrt.kt"))
        cmd = [tools.minic, "build"] + srcs + ["-o", exe, "--cflag", "-O2"] + backend
        if stats: cmd.append("--stats")
        rc, out = sh(cmd, timeout=1800)
    if rc != 0:
        err = "build failed: " + "\n".join(l for l in out.splitlines() if not l.startswith("Picked up"))[-800:]
    return run_cmd, artifact, err, time.perf_counter() - t0


_TIMEIT = None


def timeit_bin(cc):
    """Compiles bench/timeit.c once (the launcher that measures wall time and the peak RSS of a child)."""
    global _TIMEIT
    if _TIMEIT is None:
        out = os.path.join(BENCH, "build", "timeit")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        rc, o = sh([cc, "-O2", "-o", out, os.path.join(BENCH, "timeit.c")])
        if rc != 0: sys.exit("cannot build timeit: " + o)
        _TIMEIT = out
    return _TIMEIT


def run_once(cmd, timeout=300):
    """Runs cmd once; returns (wall seconds, peak RSS kB, stdout, stderr, returncode)."""
    import tempfile
    with tempfile.TemporaryFile() as fo, tempfile.TemporaryFile() as fe, tempfile.NamedTemporaryFile("r") as fr:
        try:
            p = subprocess.run([_TIMEIT, fr.name] + cmd, stdout=fo, stderr=fe, timeout=timeout)
        except subprocess.TimeoutExpired:
            return timeout, 0, "", "timeout", -9
        fo.seek(0); fe.seek(0)
        out = fo.read().decode("utf-8", "replace")
        errt = fe.read().decode("utf-8", "replace")
        wall, kb, rc = fr.read().split()
        return float(wall), int(kb), out, errt, int(rc)


def dir_size(path):
    total = 0
    for dp, _, fns in os.walk(path):
        for f in fns:
            total += os.path.getsize(os.path.join(dp, f))
    return total


def sizes(artifact):
    if os.path.isdir(artifact):
        return dir_size(artifact), None
    sz = os.path.getsize(artifact)
    stripped = None
    if shutil.which("strip"):
        tmp = artifact + ".stripped"
        shutil.copy(artifact, tmp)
        rc, _ = sh(["strip", tmp])
        if rc == 0: stripped = os.path.getsize(tmp)
        os.remove(tmp)
    return sz, stripped


def parse_stats(text):
    """mk-stats lines of a --stats build -> dict."""
    d = {}
    for line in text.splitlines():
        if not line.startswith("mk-stats"): continue
        for k, v in re.findall(r"([A-Za-z_ ]+?)[=:]\s*([0-9]+)", line):
            d[k.strip().replace(" ", "_")] = int(v)
    d["raw"] = [l for l in text.splitlines() if l.startswith("mk-stats")]
    return d


def measure(tools, prog, lang_id, src, build_dir, expected, reps, do_stats, no_build):
    r = {"bench": prog, "lang": lang_id, "source": os.path.relpath(src, ROOT)}
    outdir = os.path.join(build_dir, lang_id)
    if no_build and os.path.exists(os.path.join(outdir, prog)) or no_build and os.path.isdir(os.path.join(outdir, prog + ".classes")):
        run_cmd, artifact, err, bsec = None, None, None, 0
        # reconstruct the command exactly like build() would, without compiling
        probe = {"kotlin-jvm": lambda: [tools.java, "-cp", os.path.join(outdir, prog + ".classes") + os.pathsep + tools.kotlin_stdlib, prog[0].upper() + prog[1:] + "Kt"]}
        if lang_id == "kotlin-jvm":
            artifact = os.path.join(outdir, prog + ".classes"); run_cmd = probe[lang_id]()
        elif lang_id == "kotlin-native":
            artifact = os.path.join(outdir, prog + ".kexe"); run_cmd = [artifact]
        else:
            artifact = os.path.join(outdir, prog); run_cmd = [artifact]
    else:
        run_cmd, artifact, err, bsec = build(tools, prog, lang_id, src, outdir)
    r["build_seconds"] = round(bsec, 2)
    if err:
        r["status"] = "not measured" if ("not installed" in err or "no C compiler" in err) else "build failed"
        r["note"] = err
        return r
    times, rss = [], []
    ok = True
    for i in range(reps):
        wall, kb, out, errt, rc = run_once(run_cmd)
        if rc != 0 or out != expected:
            ok = False
            r["note"] = f"wrong output or exit code {rc}: got {out[:200]!r} expected {expected[:200]!r} {errt[-200:]!r}"
            break
        times.append(wall); rss.append(kb)
    if not ok:
        r["status"] = "wrong output"
        return r
    r["status"] = "ok"
    r["time_best_s"] = round(min(times), 4)
    r["times_s"] = [round(t, 4) for t in times]
    r["rss_kb"] = max(rss)  # peak over the runs (they are almost identical)
    r["size_bytes"], r["size_stripped_bytes"] = sizes(artifact)
    if lang_id == "mini-managed" and do_stats and not no_build:
        run_cmd2, art2, err2, _ = build(tools, prog, lang_id, src, outdir, stats=True)
        if not err2:
            _, _, _, errt, _ = run_once(run_cmd2)
            r["stats"] = parse_stats(errt)
    return r


def startup(tools, build_dir, reps):
    """Cold start: `hello` in every language (best of reps*4)."""
    res = {}
    for lang_id, _ in LANGS:
        base = "hello"
        d = os.path.join(BENCH, "startup")
        if lang_id.startswith("mini-"):
            if lang_id != "mini-managed" and lang_id != "mini-managed-llvm": continue
        f = os.path.join(d, base + {"c": ".c", "rust": ".rs", "go": ".go"}.get(lang_id, ".kt"))
        run_cmd, artifact, err, _ = build(tools, "hello", lang_id, f, os.path.join(build_dir, "startup", lang_id))
        if err:
            res[lang_id] = {"status": "not measured" if "not installed" in err else "build failed", "note": err}
            continue
        times = []
        for _ in range(max(reps * 4, 10)):
            wall, kb, out, _, rc = run_once(run_cmd)
            if rc != 0 or out.strip() != "hello":
                res[lang_id] = {"status": "wrong output"}
                break
            times.append(wall)
        else:
            res[lang_id] = {"status": "ok", "startup_best_ms": round(min(times) * 1000, 2), "startup_median_ms": round(sorted(times)[len(times) // 2] * 1000, 2)}
    return res


def fmt_time(r):
    return "n/a" if not r or r.get("status") != "ok" else f"{r['time_best_s']:.2f}"


def markdown(data):
    langs = [l for l, _ in LANGS if any(r["lang"] == l and r.get("status") == "ok" for r in data["results"])]
    byk = {(r["bench"], r["lang"]): r for r in data["results"]}
    L = []
    L.append(f"Host: {data['host']['cpu']} ({data['host']['cores']} cores), {data['host']['os']}; date {data['date']}; best of {data['reps']}.\n")
    def table(title, cell, unit):
        L.append(f"**{title}** ({unit})\n")
        L.append("| benchmark | " + " | ".join(SHORT[l] for l in langs) + " |")
        L.append("|---|" + "---:|" * len(langs))
        for p in PROGRAMS:
            L.append(f"| {p} | " + " | ".join(cell(byk.get((p, l))) for l in langs) + " |")
        L.append("")
    table("Wall time", fmt_time, "seconds, best of N")
    def rel(r):
        c = None
        return ""
    # relative to C
    L.append("**Time relative to C** (1.00 = C at -O2; higher is slower)\n")
    L.append("| benchmark | " + " | ".join(SHORT[l] for l in langs) + " |")
    L.append("|---|" + "---:|" * len(langs))
    for p in PROGRAMS:
        c = byk.get((p, "c"))
        cells = []
        for l in langs:
            r = byk.get((p, l))
            cells.append("n/a" if not r or r.get("status") != "ok" or not c or c.get("status") != "ok" else f"{r['time_best_s'] / c['time_best_s']:.2f}")
        L.append(f"| {p} | " + " | ".join(cells) + " |")
    L.append("")
    table("Peak RSS", lambda r: "n/a" if not r or r.get("status") != "ok" else f"{r['rss_kb'] / 1024:.0f}", "MiB")
    table("Binary size", lambda r: "n/a" if not r or r.get("status") != "ok" else f"{r['size_bytes'] / 1024:.0f}", "KiB; JVM = class files only, without the JVM and the Kotlin stdlib jar")
    st = data.get("startup", {})
    if st:
        L.append("**Cold start** (`hello`, ms, best of N)\n")
        L.append("| " + " | ".join(SHORT[l] for l in st) + " |")
        L.append("|" + "---:|" * len(st))
        L.append("| " + " | ".join(f"{v['startup_best_ms']:.1f}" if v.get("status") == "ok" else "n/a" for v in st.values()) + " |")
        L.append("")
    missing = sorted({(r["lang"], r.get("status")) for r in data["results"] if r.get("status") not in ("ok",)})
    if missing:
        L.append("Not measured / failed: " + ", ".join(f"{SHORT.get(l, l)} ({s})" for l, s in missing) + "\n")
    stats = [r for r in data["results"] if r.get("stats")]
    if stats:
        L.append("**Mini Managed counters** (`--stats` build)\n")
        L.append("| benchmark | raw mk-stats lines |")
        L.append("|---|---|")
        for r in stats:
            L.append(f"| {r['bench']} | " + "<br>".join(r["stats"].get("raw", [])) + " |")
        L.append("")
    return "\n".join(L)


def cpu_model():
    try:
        for line in open("/proc/cpuinfo"):
            if line.startswith("model name"): return line.split(":", 1)[1].strip()
    except OSError:
        pass
    return platform.processor() or platform.machine()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--reps", type=int, default=5)
    ap.add_argument("--only", default="")
    ap.add_argument("--langs", default="")
    ap.add_argument("--out", default=os.path.join(BENCH, "results"))
    ap.add_argument("--build-dir", default=os.path.join(BENCH, "build"))
    ap.add_argument("--no-build", action="store_true", help="reuse the binaries of an earlier run")
    ap.add_argument("--stats", action="store_true", help="also run a --stats build of Mini Managed for the RC/alloc counters")
    ap.add_argument("--no-startup", action="store_true")
    ap.add_argument("--update-expected", action="store_true", help="rewrite bench/<name>/expected.txt from the C implementation")
    a = ap.parse_args()

    tools = Tools()
    timeit_bin(tools.cc)
    only = [x for x in a.only.split(",") if x] or PROGRAMS
    langs = [x for x in a.langs.split(",") if x] or [l for l, _ in LANGS]
    os.makedirs(a.out, exist_ok=True)

    if a.update_expected:
        for p in only:
            run_cmd, _, err, _ = build(tools, p, "c", source(p, "c"), os.path.join(a.build_dir, "c"))
            if err: sys.exit(err)
            _, _, out, _, rc = run_once(run_cmd)
            open(os.path.join(BENCH, p, "expected.txt"), "w").write(out)
            print(p, "->", out.strip().replace("\n", " | "))
        return

    results = []
    for p in only:
        expected = open(os.path.join(BENCH, p, "expected.txt")).read()
        for lang_id in langs:
            src = source(p, lang_id)
            if not src: continue
            print(f"[{p:13s}] {lang_id:18s} ", end="", flush=True)
            r = measure(tools, p, lang_id, src, a.build_dir, expected, a.reps, a.stats, a.no_build)
            results.append(r)
            print(r["status"] + (f"  {r['time_best_s']:.3f}s  {r['rss_kb'] // 1024} MiB" if r["status"] == "ok" else "  " + r.get("note", "")[:160].replace("\n", " ")), flush=True)
    startup_res = {} if a.no_startup else startup(tools, a.build_dir, a.reps)
    for k, v in startup_res.items():
        print(f"[startup] {k:18s} {v}")
    date = datetime.date.today().isoformat()
    host = re.sub(r"[^A-Za-z0-9_.-]", "_", socket.gethostname())
    data = {
        "date": date,
        "host": {"name": host, "cpu": cpu_model(), "cores": os.cpu_count(), "os": f"{platform.system()} {platform.release()}"},
        "reps": a.reps,
        "tools": tools.versions(),
        "minic_commit": sh(["git", "-C", ROOT, "rev-parse", "--short", "HEAD"])[1].strip(),
        "flags": {"c": "clang -O2", "rust": "rustc -C opt-level=3 -C codegen-units=1", "go": "go build (CGO off)", "kotlin-jvm": "kotlinc; java (default JVM flags)",
                  "kotlin-native": "kotlinc-native -opt", "mini": "minic build --cflag -O2 [--backend=llvm]"},
        "results": results,
        "startup": startup_res,
    }
    base = os.path.join(a.out, f"{date}-{host}")
    json.dump(data, open(base + ".json", "w"), indent=1)
    md = markdown(data)
    open(base + ".md", "w").write(md)
    print("\n" + md)
    print(f"\nwrote {base}.json and {base}.md")
    bad = [r for r in results if r["status"] in ("wrong output",)]
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
