#!/usr/bin/env python3
"""Hillclimb metrics harness: builds every benchmark with Rust and with Mini (C backend and LLVM-API backend), checks the output,
measures speed / peak RSS / stripped size, and folds them into ONE scalar score (lower is better).

    bench/hillclimb/score.py [--runs 7] [--only a,b] [--no-guard] [--cpu N] [--json-out F] [--quick]

Toolchains (build them first: scripts/build-mini.sh and bench/hillclimb/build-api.sh):
    C backend    build/native/bin/minic   (env MINIC_C)     `minic build -O2 --backend=c`
    LLVM API     build/api/bin/minic      (env MINIC_API)   `minic build -O2 --backend=llvm-api`
    Rust         rustc -C opt-level=3 -C codegen-units=1    (both: default target-cpu = x86-64 baseline, no -march=native)

Per benchmark (bench/<name>/<name>.kt vs .rs, plus bench/startup/hello for the size metric of a hello world):
    speed  = median CPU time (user+sys, from wait4) of N runs, taken round-robin over the three binaries so load drift hits all
             of them equally; the process is pinned to one CPU (sched_setaffinity); one untimed warm-up run first. Wall time and
             the min are recorded too. CPU time is used (not wall) because the box is shared with other jobs.
    rss    = median ru_maxrss of the runs
    size   = size of the `strip`ped executable
    ratio  = mini / rust for each of them.

Score (documented in bench/hillclimb/LOG.md):
    S = geomean over (benchmark x backend) of the speed ratio
    R = geomean over (benchmark x backend) of the peak-RSS ratio
    Z = geomean over (benchmark + hello x backend) of the stripped-size ratio
    score = S^0.5 * R^0.25 * Z^0.25          (1.0 == Rust parity on all three; lower is better)

Correctness gate: stdout must equal bench/<name>/expected.txt on every run and the exit status must be 0; otherwise `ok` is false
and the score is null. (The validation list - scripts/test-e2e.sh etc. - is separate: see LOG.md.)
Guard metrics: compile wall time per benchmark and backend; with --guard (default) also the self-compile time of the compiler
(frontend to C, and the LLVM-API object build of the sources), stdlib.mlib bytes, and the browser stdlib.wasm.o size if built.
Output: a markdown table on stderr, then ONE JSON line on stdout.
"""
import argparse, hashlib, json, math, os, resource, shutil, statistics, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BENCH = os.path.join(ROOT, "bench")
PROGRAMS = ["binarytrees", "nbody", "fannkuch", "spectralnorm", "mandelbrot", "wordcount", "strbuild", "json", "graph"]
HELLO = ("startup", "hello")
WEIGHTS = (0.5, 0.25, 0.25)
MINIC_C = os.environ.get("MINIC_C", os.path.join(ROOT, "build/native/bin/minic"))
MINIC_API = os.environ.get("MINIC_API", os.path.join(ROOT, "build/api/bin/minic"))
BACKENDS = {"c": (MINIC_C, "c"), "api": (MINIC_API, "llvm-api")}
RUSTFLAGS = ["-C", "opt-level=3", "-C", "codegen-units=1"]
MINI_OPT = os.environ.get("MINI_OPT", "-O2")


def sh(cmd, timeout=1800, env=None):
    t0 = time.perf_counter()
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout, env=env)
    return p.returncode, p.stdout, time.perf_counter() - t0


def run_once(cmd, timeout=600):
    """-> (cpu seconds, wall seconds, maxrss kB, stdout bytes, returncode)"""
    t0 = time.perf_counter()
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    out = p.stdout.read()
    _, status, ru = os.wait4(p.pid, 0)
    p.returncode = 0  # reaped by wait4
    wall = time.perf_counter() - t0
    rc = os.waitstatus_to_exitcode(status)
    return ru.ru_utime + ru.ru_stime, wall, ru.ru_maxrss, out, rc


def build_rust(src, exe):
    if os.path.exists(exe) and os.path.getmtime(exe) > os.path.getmtime(src):
        return 0.0, None
    rc, out, t = sh(["rustc"] + RUSTFLAGS + ["-o", exe, src])
    return t, (None if rc == 0 else out[-600:])


def build_mini(backend, src, exe):
    minic, name = BACKENDS[backend]
    rc, out, t = sh([minic, "build", src, "-o", exe, MINI_OPT, "--backend", name])
    return t, (None if rc == 0 else out[-600:])


def stripped_size(path):
    tmp = path + ".stripped"
    shutil.copy(path, tmp)
    rc, _, _ = sh(["strip", tmp])
    sz = os.path.getsize(tmp) if rc == 0 else os.path.getsize(path)
    os.remove(tmp)
    return sz


def geomean(xs):
    xs = [x for x in xs if x is not None]
    return math.exp(sum(math.log(x) for x in xs) / len(xs)) if xs else None


def measure_one(d, name, runs, bd, backends):
    """Builds and measures one program in Rust and the Mini backends. -> dict lang -> metrics"""
    src_kt = os.path.join(BENCH, d, name + ".kt")
    src_rs = os.path.join(BENCH, d, name + ".rs")
    exp_path = os.path.join(BENCH, d, "expected.txt")
    expected = open(exp_path, "rb").read()
    res = {}
    exes = {}
    rt, err = build_rust(src_rs, os.path.join(bd, name + ".rust"))
    res["rust"] = {"build_s": round(rt, 2), "err": err}
    exes["rust"] = os.path.join(bd, name + ".rust")
    for b in backends:
        exe = os.path.join(bd, f"{name}.{b}")
        t, err = build_mini(b, src_kt, exe)
        res[b] = {"build_s": round(t, 2), "err": err}
        exes[b] = exe
    live = [l for l in res if not res[l]["err"]]
    samples = {l: {"cpu": [], "wall": [], "rss": []} for l in live}
    bad = {}
    for l in live:  # warm-up (also the first correctness check)
        cpu, wall, rss, out, rc = run_once([exes[l]])
        if rc != 0 or out != expected:
            bad[l] = f"exit {rc}, output {out[:120]!r}"
    for _ in range(runs):
        for l in live:
            if l in bad: continue
            cpu, wall, rss, out, rc = run_once([exes[l]])
            if rc != 0 or out != expected:
                bad[l] = f"exit {rc}, output {out[:120]!r}"
                continue
            samples[l]["cpu"].append(cpu); samples[l]["wall"].append(wall); samples[l]["rss"].append(rss)
    for l in live:
        r = res[l]
        if l in bad:
            r["err"] = "wrong output: " + bad[l]
            continue
        s = samples[l]
        r.update(cpu_median=statistics.median(s["cpu"]), cpu_min=min(s["cpu"]), cpu_max=max(s["cpu"]),
                 wall_median=statistics.median(s["wall"]), rss_kb=int(statistics.median(s["rss"])), size=stripped_size(exes[l]))
        r["cpu_cv"] = (statistics.pstdev(s["cpu"]) / r["cpu_median"]) if r["cpu_median"] > 0 else 0.0
    return res


def hello_sizes(bd, backends):
    res = {}
    src_kt = os.path.join(BENCH, HELLO[0], "hello.kt"); src_rs = os.path.join(BENCH, HELLO[0], "hello.rs")
    t, err = build_rust(src_rs, os.path.join(bd, "hello.rust"))
    res["rust"] = {"err": err, "size": None if err else stripped_size(os.path.join(bd, "hello.rust"))}
    for b in backends:
        exe = os.path.join(bd, f"hello.{b}")
        t, err = build_mini(b, src_kt, exe)
        ok = False
        if not err:
            _, _, _, out, rc = run_once([exe])
            ok = rc == 0 and out.strip() == b"hello"
        res[b] = {"build_s": round(t, 2), "err": err or (None if ok else "wrong output"), "size": stripped_size(exe) if not err else None}
    return res


def guard_metrics(backends, full):
    g = {}
    srcs = []
    for d in ("minic-native/src", "lower/src", "frontend/src", "backend/src"):
        for dp, _, fns in os.walk(os.path.join(ROOT, "compiler", d)):
            srcs += [os.path.join(dp, f) for f in fns if f.endswith(".kt")]
    srcs.sort()
    std = []
    for dp, _, fns in os.walk(os.path.join(ROOT, "stdlib")):
        std += [os.path.join(dp, f) for f in fns if f.endswith(".kt")]
    std.sort()
    for b in backends:
        native = os.path.join(os.path.dirname(BACKENDS[b][0]), "minic-native")
        if not os.path.exists(native): continue
        ts = []
        if b != "c" and not full: continue
        for _ in range(3 if b == "c" else 1):
            if b == "c":
                cmd = [native, "--backend=c", "-o", "/tmp/hc-self.c"] + srcs + ["--stdlib"] + std
            else:
                cmd = [native, "--backend=llvm-api", "--opt=O1", "-o", "/tmp/hc-self.o"] + srcs + ["--stdlib"] + std
            rc, out, t = sh(cmd)
            if rc != 0: break
            ts.append(t)
        if ts:
            g[f"selfcompile_{b}_s"] = round(statistics.median(ts), 2)
        for f in ("/tmp/hc-self.c", "/tmp/hc-self.o"):
            if os.path.exists(f): os.remove(f)
    for b in backends:
        mlib = os.path.join(os.path.dirname(os.path.dirname(BACKENDS[b][0])), "lib", "stdlib.mlib")
        if os.path.exists(mlib): g[f"stdlib_mlib_bytes_{b}"] = os.path.getsize(mlib)
    for cand in ("build/wasm/stdlib.wasm.o", "build/wasm-toolchain/stdlib.wasm.o"):
        p = os.path.join(ROOT, cand)
        if os.path.exists(p): g["stdlib_wasm_o_bytes"] = os.path.getsize(p)
    return g


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=7)
    ap.add_argument("--only", default="")
    ap.add_argument("--backends", default="c,api")
    ap.add_argument("--no-guard", action="store_true")
    ap.add_argument("--guard-full", action="store_true", help="also time the LLVM-API self-compile (about 100 s)")
    ap.add_argument("--cpu", type=int, default=-1, help="CPU to pin to (default: the last allowed one)")
    ap.add_argument("--json-out", default="")
    ap.add_argument("--build-dir", default=os.path.join(ROOT, "build", "hc"))
    ap.add_argument("--label", default="")
    a = ap.parse_args()
    backends = [b for b in a.backends.split(",") if b]
    for b in backends:
        if not os.path.exists(BACKENDS[b][0]): sys.exit(f"missing toolchain for backend {b}: {BACKENDS[b][0]}")
    cpus = sorted(os.sched_getaffinity(0))
    cpu = a.cpu if a.cpu >= 0 else cpus[-1]
    os.sched_setaffinity(0, {cpu})
    bd = a.build_dir
    os.makedirs(bd, exist_ok=True)
    only = [x for x in a.only.split(",") if x] or PROGRAMS
    only = [p for p in only if os.path.exists(os.path.join(BENCH, p, p + ".rs"))]
    out = {"label": a.label, "commit": sh(["git", "-C", ROOT, "rev-parse", "--short", "HEAD"])[1].strip(), "runs": a.runs, "cpu": cpu,
           "rustc": sh(["rustc", "--version"])[1].strip(), "benchmarks": {}}
    ok = True
    for p in only:
        print(f"[{p}]", file=sys.stderr, flush=True)
        out["benchmarks"][p] = measure_one(p, p, a.runs, bd, backends)
    out["hello"] = hello_sizes(bd, backends)
    # ratios
    sp, rs, sz = [], [], []
    table = []
    for p, r in out["benchmarks"].items():
        ru = r["rust"]
        for l in ("rust", *backends):
            if r[l].get("err"):
                ok = False
                print(f"  {p}/{l}: {r[l]['err']}", file=sys.stderr)
        if ru.get("err"): continue
        for b in backends:
            m = r[b]
            if m.get("err"): continue
            m["speed_ratio"] = m["cpu_median"] / ru["cpu_median"]
            m["rss_ratio"] = m["rss_kb"] / ru["rss_kb"]
            m["size_ratio"] = m["size"] / ru["size"]
            sp.append(m["speed_ratio"]); rs.append(m["rss_ratio"]); sz.append(m["size_ratio"])
    h = out["hello"]
    for b in backends:
        if h[b].get("err") or h["rust"].get("err"):
            ok = False; continue
        h[b]["size_ratio"] = h[b]["size"] / h["rust"]["size"]
        sz.append(h[b]["size_ratio"])
    out["ok"] = ok
    S, R, Z = geomean(sp), geomean(rs), geomean(sz)
    out["S"], out["R"], out["Z"] = S, R, Z
    out["score"] = (S ** WEIGHTS[0] * R ** WEIGHTS[1] * Z ** WEIGHTS[2]) if ok and S and R and Z else None
    for b in backends:
        sb = [r[b]["speed_ratio"] for r in out["benchmarks"].values() if "speed_ratio" in r.get(b, {})]
        rb = [r[b]["rss_ratio"] for r in out["benchmarks"].values() if "rss_ratio" in r.get(b, {})]
        zb = [r[b]["size_ratio"] for r in out["benchmarks"].values() if "size_ratio" in r.get(b, {})] + ([h[b]["size_ratio"]] if "size_ratio" in h.get(b, {}) else [])
        out[f"S_{b}"], out[f"R_{b}"], out[f"Z_{b}"] = geomean(sb), geomean(rb), geomean(zb)
    if not a.no_guard:
        out["guard"] = guard_metrics(backends, a.guard_full)
    # markdown
    L = []
    L.append(f"commit {out['commit']} {a.label} | runs={a.runs} cpu={cpu} | {out['rustc']}")
    L.append("")
    L.append("| bench | rust cpu s | " + " | ".join(f"{b} speed (x rust) | {b} rss | {b} size | {b} build s" for b in backends) + " |")
    L.append("|---|---:|" + "---:|---:|---:|---:|" * len(backends))
    for p, r in out["benchmarks"].items():
        ru = r["rust"]
        cells = []
        for b in backends:
            m = r[b]
            if "speed_ratio" in m:
                cells.append(f"{m['speed_ratio']:.2f} ({m['cpu_median']:.2f}s cv{m['cpu_cv']*100:.0f}%) | {m['rss_ratio']:.2f} ({m['rss_kb']//1024}M) | {m['size_ratio']:.2f} | {m['build_s']}")
            else:
                cells.append("FAIL | | | ")
        L.append(f"| {p} | {ru.get('cpu_median', float('nan')):.2f} (rss {ru.get('rss_kb', 0)//1024}M, {ru.get('size', 0)//1024}K) | " + " | ".join(cells) + " |")
    L.append("")
    L.append("| hello (stripped) | " + " | ".join(f"{b}: {h[b].get('size')} B ({h[b].get('size_ratio', float('nan')):.2f}x)" for b in backends) + f" | rust: {h['rust'].get('size')} B |")
    L.append("")
    L.append(f"S (speed geomean) = {S:.4f}, R (RSS) = {R:.4f}, Z (size) = {Z:.4f}   per backend: " +
             ", ".join(f"{b}: S={out[f'S_{b}']:.3f} R={out[f'R_{b}']:.3f} Z={out[f'Z_{b}']:.3f}" for b in backends))
    L.append(f"**score = S^0.5 R^0.25 Z^0.25 = {out['score']:.4f}**   correctness gate: {'PASS' if ok else 'FAIL'}" if out["score"] else f"score: n/a (correctness gate FAIL)")
    if "guard" in out: L.append("guard: " + json.dumps(out["guard"]))
    print("\n".join(L), file=sys.stderr)
    line = json.dumps(out, separators=(",", ":"))
    if a.json_out:
        open(a.json_out, "w").write(json.dumps(out, indent=1))
    print(line)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
