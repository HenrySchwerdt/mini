#!/usr/bin/env python3
"""Differential fuzzing of mini against kotlinc (JVM). See docs/fuzzing.md.

  fuzz.py run    --seeds A:B  [--variants c,c-asan,llvm,llvm-asan] [--jobs N] [--out DIR] [--root DIR] [--kotlinc PATH]
  fuzz.py reduce --seed S --variant V [--out DIR] ...     minimise the program of a failing seed (statement-level delta debugging)
  fuzz.py check  --expect DIR [--variants ...]            run the saved programs of DIR/*.kt against DIR/*.out (no JVM; the CI smoke test)

Variants: c (C backend), llvm (LLVM backend through textual IR), c-asan / llvm-asan (the same under AddressSanitizer, UBSan, LeakSanitizer).
Every failure is classified (ice, compile-error, cc-error, crash, timeout, sanitizer, leak, mismatch) and saved in DIR/fail/.
"""
import argparse
import concurrent.futures as cf
import glob
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gen  # noqa: E402

MAIN_MARK = "// --- main ---"
GEN_MARK = "// --- generated ---"


# ---------------------------------------------------------------------------------------------------------------------- tool locations
class Tools:
    def __init__(self, a):
        self.root = os.path.abspath(a.root)
        self.minic = os.environ.get("MINIC_NATIVE") or os.path.join(self.root, "build/native/bin/minic-native")
        self.rtlib = os.path.join(self.root, "build/native/lib/libminirt.a")
        self.include = os.path.join(self.root, "runtime/include")
        self.kotlinc = a.kotlinc or os.environ.get("KOTLINC") or shutil.which("kotlinc") or "/tmp/claude-0/kc/kotlinc/bin/kotlinc"
        self.out = os.path.abspath(a.out)
        os.makedirs(self.out, exist_ok=True)
        mlib = os.path.join(self.root, "build/native/lib/stdlib.mlib")
        if os.path.exists(mlib) and os.environ.get("FUZZ_STDLIB", "mlib") == "mlib":
            ver = open(os.path.join(self.root, "build/native/VERSION")).read().strip() if os.path.exists(os.path.join(self.root, "build/native/VERSION")) else "unknown"
            self.stdargs = ["--lib", mlib, "--compiler-id", ver, "--stub-cache", os.path.join(self.out, "stubcache"), "--stub-cache-id", "fuzz"]
        else:
            std = sorted(glob.glob(os.path.join(self.root, "stdlib/**/*.kt"), recursive=True))
            self.stdargs = ["--stdlib"] + std
        self.cc = shutil.which("clang") or "cc"
        self.sancc = None
        probe = os.path.join(self.out, "probe.c")
        with open(probe, "w") as f:
            f.write("int main(void){return 0;}\n")
        for cand in (os.environ.get("CC"), "clang", "gcc"):
            if cand and shutil.which(cand):
                if subprocess.run([cand, "-fsanitize=address,undefined", probe, "-o", probe + ".exe"], capture_output=True).returncode == 0:
                    self.sancc = cand
                    break
        self.rt_asan = os.path.join(self.out, "rt-asan")

    SAN = ["-fsanitize=address,undefined", "-fno-sanitize-recover=undefined", "-g", "-fno-omit-frame-pointer"]

    def build_rt_asan(self):
        stamp = os.path.join(self.rt_asan, ".stamp")
        srcs = sorted(glob.glob(os.path.join(self.root, "runtime/src/*.c")))
        newest = max(os.path.getmtime(s) for s in srcs)
        if os.path.exists(stamp) and os.path.getmtime(stamp) >= newest:
            return
        shutil.rmtree(self.rt_asan, ignore_errors=True)
        os.makedirs(self.rt_asan)
        with cf.ThreadPoolExecutor(4) as ex:
            list(ex.map(lambda s: subprocess.run([self.sancc, "-std=c17", "-O1", "-w", "-I", self.include] + self.SAN + ["-c", s, "-o", os.path.join(self.rt_asan, os.path.basename(s)[:-2] + ".o")], check=True), srcs))
        open(stamp, "w").close()


# ---------------------------------------------------------------------------------------------------------------------- program text
def split_for_jvm(text, pkg):
    """The program without `main`, in package `pkg` (so that many programs compile in one kotlinc run)."""
    i = text.index(MAIN_MARK)
    return "package %s\n%s" % (pkg, text[:i])


def make_program(seed, size):
    text, _ = gen.generate(seed, size)
    return text


# ---------------------------------------------------------------------------------------------------------------------- the JVM side
def jvm_batch(tools, programs, workdir):
    """programs: {key: source text with MAIN_MARK}. -> ({key: stdout}, {key: error text}); programs that kotlinc rejects are dropped and
    reported in the second result."""
    outputs, errors = {}, {}
    live = dict(programs)
    for _ in range(6):
        if not live:
            break
        d = tempfile.mkdtemp(prefix="jb", dir=workdir)
        try:
            names = {}
            for k, text in live.items():
                pkg = "p%s" % re.sub(r"\W", "_", str(k))
                names[pkg] = k
                with open(os.path.join(d, pkg + ".kt"), "w") as f:
                    f.write(split_for_jvm(text, pkg))
            drv = ["fun main() {"]
            for pkg, k in names.items():
                drv.append('    println("=== %s")' % pkg)
                drv.append("    try { %s.run() } catch (e: Throwable) { println(\"uncaught \" + (e is ArithmeticException) + (e is IndexOutOfBoundsException) + (e is IllegalStateException)) }" % pkg)
            drv.append("}")
            with open(os.path.join(d, "Driver.kt"), "w") as f:
                f.write("\n".join(drv) + "\n")
            jar = os.path.join(d, "o.jar")
            r = subprocess.run([tools.kotlinc, d, "-include-runtime", "-d", jar, "-nowarn"], capture_output=True, text=True, timeout=900)
            if r.returncode != 0:
                bad = {}
                for m in re.finditer(r"^(?:e: )?(?:file://)?\S*?/(p\w+)\.kt:(\d+):(\d+):? (?:error: )?(.*)$", r.stderr, re.M):
                    bad.setdefault(m.group(1), "%s:%s %s" % (m.group(2), m.group(3), m.group(4)))
                if not bad:
                    raise RuntimeError("kotlinc failed without a located error:\n" + r.stderr[-2000:])
                for pkg, msg in bad.items():
                    if pkg in names:
                        errors[names[pkg]] = msg
                        live.pop(names[pkg], None)
                continue
            run = subprocess.run(["java", "-Xss64m", "-jar", jar], capture_output=True, text=True, timeout=900)
            cur = None
            buf = {}
            so = run.stdout[:-1] if run.stdout.endswith("\n") else run.stdout
            for line in so.split("\n"):
                m = re.match(r"^=== (p\w+)$", line)
                if m:
                    cur = m.group(1)
                    buf[cur] = []
                elif cur is not None:
                    buf[cur].append(line)
            for pkg, k in names.items():
                lines = buf.get(pkg, [])
                outputs[k] = "".join(l + "\n" for l in lines)
            return outputs, errors
        finally:
            shutil.rmtree(d, ignore_errors=True)
    return outputs, errors


# ---------------------------------------------------------------------------------------------------------------------- the mini side
def run_variant(tools, text, variant, work, timeout=30):
    """-> (kind, stdout, detail): kind is ok, ice, compile-error, cc-error, crash, timeout, sanitizer or leak."""
    os.makedirs(work, exist_ok=True)
    src = os.path.join(work, "p.kt")
    with open(src, "w") as f:
        f.write(text)
    llvm = variant.startswith("llvm")
    asan = variant.endswith("asan")
    ir = os.path.join(work, "p.ll" if llvm else "p.c")
    cmd = [tools.minic] + (["--emit-ll", ir, src] if llvm else ["-o", ir, src]) + tools.stdargs
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    except subprocess.TimeoutExpired:
        return "timeout", "", "compile"
    if r.returncode == 1:
        return "compile-error", "", (r.stdout + r.stderr)[:600]
    if r.returncode != 0:
        return "ice", "", (r.stdout + r.stderr)[-1500:]
    exe = os.path.join(work, "p.exe")
    inc = ["-I", tools.include]
    if asan:
        if llvm:
            o = os.path.join(work, "p.o")
            c = subprocess.run(["clang", "-O1", "-w"] + tools.SAN + ["-Wno-override-module", "-c", ir, "-o", o], capture_output=True, text=True)
            if c.returncode == 0:
                c = subprocess.run([tools.sancc] + tools.SAN + inc + [o] + sorted(glob.glob(tools.rt_asan + "/*.o")) + ["-o", exe, "-lm", "-lpthread"], capture_output=True, text=True)
        else:
            c = subprocess.run([tools.sancc, "-std=c17", "-O1", "-w"] + tools.SAN + inc + [ir] + sorted(glob.glob(tools.rt_asan + "/*.o")) + ["-o", exe, "-lm", "-lpthread"], capture_output=True, text=True)
    elif llvm:
        c = subprocess.run(["clang", "-O1", "-w", "-Wno-override-module"] + inc + [ir, tools.rtlib, "-o", exe, "-lm", "-lpthread"], capture_output=True, text=True)
    else:
        c = subprocess.run([tools.cc, "-std=c17", "-O1", "-w"] + inc + [ir, tools.rtlib, "-o", exe, "-lm", "-lpthread"], capture_output=True, text=True)
    if c.returncode != 0:
        return "cc-error", "", (c.stdout + c.stderr)[:800]
    env = dict(os.environ, ASAN_OPTIONS="detect_leaks=1:exitcode=99", UBSAN_OPTIONS="print_stacktrace=1")
    try:
        p = subprocess.run([exe], capture_output=True, timeout=timeout, cwd=work, env=env)
    except subprocess.TimeoutExpired:
        return "timeout", "", "run"
    out = p.stdout.decode("utf-8", "replace")
    err = p.stderr.decode("utf-8", "replace")
    if p.returncode < 0:
        return "crash", out, "signal %d\n%s" % (-p.returncode, err[-800:])
    if "LeakSanitizer" in err:
        return "leak", out, err[:1500]
    if "AddressSanitizer" in err or "runtime error:" in err:
        return "sanitizer", out, err[:1500]
    if p.returncode != 0:
        return "crash", out, "exit %d\n%s" % (p.returncode, err[-800:])
    return "ok", out, ""


def judge(tools, text, expected, variant, work):
    """-> (kind, detail, stdout): kind 'pass' or a failure class."""
    kind, out, detail = run_variant(tools, text, variant, work)
    if kind != "ok":
        return kind, detail, out
    if out != expected:
        return "mismatch", first_diff(expected, out), out
    return "pass", "", out


def first_diff(a, b):
    la, lb = a.split("\n"), b.split("\n")
    for i in range(max(len(la), len(lb))):
        x = la[i] if i < len(la) else "<missing>"
        y = lb[i] if i < len(lb) else "<missing>"
        if x != y:
            return "line %d: expected %r, got %r" % (i + 1, x, y)
    return ""


# ---------------------------------------------------------------------------------------------------------------------- run
def cmd_run(a):
    tools = Tools(a)
    variants = a.variants.split(",")
    if any(v.endswith("asan") for v in variants):
        if not tools.sancc:
            print("no C compiler links the sanitizers; dropping the asan variants")
            variants = [v for v in variants if not v.endswith("asan")]
        else:
            tools.build_rt_asan()
    lo, hi = [int(x) for x in a.seeds.split(":")]
    seeds = list(range(lo, hi))
    work = os.path.join(tools.out, "work")
    os.makedirs(work, exist_ok=True)
    expdir = os.path.join(tools.out, "expected")
    faildir = os.path.join(tools.out, "fail")
    os.makedirs(expdir, exist_ok=True)
    os.makedirs(faildir, exist_ok=True)
    progs = {s: make_program(s, a.size) for s in seeds}
    key = {s: "%d.%s" % (s, hashlib.sha1(progs[s].encode()).hexdigest()[:10]) for s in seeds}  # the cache follows the program text
    # JVM expectations in batches (cached by seed and size)
    need = [s for s in seeds if not os.path.exists(os.path.join(expdir, key[s] + ".out"))]
    for i in range(0, len(need), a.batch):
        chunk = need[i : i + a.batch]
        outs, errs = jvm_batch(tools, {s: progs[s] for s in chunk}, work)
        for s, o in outs.items():
            with open(os.path.join(expdir, key[s] + ".out"), "w") as f:
                f.write(o)
        for s, msg in errs.items():
            with open(os.path.join(expdir, key[s] + ".out"), "w") as f:
                f.write("<<kotlinc rejected>> " + msg + "\n")
        print("jvm: %d/%d seeds done (%d rejected by kotlinc)" % (min(i + a.batch, len(need)), len(need), len(errs)), flush=True)
    stats = {}
    rejected = []
    t0 = time.time()

    def one(sv):
        s, v = sv
        exp = open(os.path.join(expdir, key[s] + ".out")).read()
        if exp.startswith("<<kotlinc rejected>>"):
            return s, v, "gen-invalid", exp
        w = os.path.join(work, "r%d.%s" % (s, v))
        try:
            kind, detail, out = judge(tools, progs[s], exp, v, w)
        finally:
            shutil.rmtree(w, ignore_errors=True)
        if kind != "pass":
            base = os.path.join(faildir, "%d.%s.%s" % (s, v, kind))
            with open(base + ".kt", "w") as f:
                f.write(progs[s])
            with open(base + ".expected", "w") as f:
                f.write(exp)
            with open(base + ".txt", "w") as f:
                f.write(detail + "\n")
        return s, v, kind, detail

    jobs = [(s, v) for s in seeds for v in variants]
    with cf.ThreadPoolExecutor(a.jobs) as ex:
        for n, (s, v, kind, detail) in enumerate(ex.map(one, jobs)):
            stats[(v, kind)] = stats.get((v, kind), 0) + 1
            if kind == "gen-invalid":
                if v == variants[0]:
                    rejected.append((s, detail))
            elif kind != "pass":
                print("seed %d %s: %s %s" % (s, v, kind, detail.split("\n")[0][:160]), flush=True)
            if (n + 1) % 100 == 0:
                print("... %d/%d runs, %.0fs" % (n + 1, len(jobs), time.time() - t0), flush=True)
    print("\nsummary (%d seeds):" % len(seeds))
    for (v, kind), n in sorted(stats.items()):
        print("  %-10s %-14s %d" % (v, kind, n))
    if rejected:
        print("generator produced %d programs that kotlinc rejects (see %s):" % (len(rejected), expdir))
        for s, d in rejected[:20]:
            print("  seed %d: %s" % (s, d.strip()[:200]))
    return 0


# ---------------------------------------------------------------------------------------------------------------------- reduce
def balanced_spans(lines, lo, hi):
    """All line spans [i, j] of lines[lo:hi] that can be removed without unbalancing braces: a statement or a whole block."""
    spans = []
    for i in range(lo, hi):
        depth = 0
        for j in range(i, hi):
            line = lines[j]
            depth += line.count("{") - line.count("}")
            depth_paren = 0
            if depth < 0:
                break
            if depth == 0:
                spans.append((i, j))
                break
    return spans


def protected(lines):
    """Indexes of lines the reducer must not remove: the prelude, `fun run() {` and its closing brace, main."""
    gen_start = next((k for k, l in enumerate(lines) if l.strip() == GEN_MARK), 0)
    main_start = next((k for k, l in enumerate(lines) if l.strip() == MAIN_MARK), len(lines))
    prot = set(range(0, gen_start + 1)) | set(range(main_start, len(lines)))
    for k, l in enumerate(lines):
        if l.startswith("fun run()"):
            prot.add(k)
            m = k + 1
            while m < len(lines) and lines[m] != "}":
                m += 1
            prot.add(m)
    return prot


def cmd_reduce(a):
    tools = Tools(a)
    if a.variant.endswith("asan"):
        tools.build_rt_asan()
    work = os.path.join(tools.out, "work")
    os.makedirs(work, exist_ok=True)
    text = open(a.file).read() if a.file else make_program(a.seed, a.size)
    # the failure class to preserve
    exp = None
    outs, errs = jvm_batch(tools, {"orig": text}, work)
    if "orig" not in outs:
        print("kotlinc rejects the program: %s" % errs)
        return 1
    exp = outs["orig"]
    kind0, detail0, _ = judge(tools, text, exp, a.variant, os.path.join(work, "red0"))
    shutil.rmtree(os.path.join(work, "red0"), ignore_errors=True)
    if kind0 == "pass":
        print("the program passes on %s" % a.variant)
        return 1
    print("reducing a %s failure on %s: %s" % (kind0, a.variant, detail0.split("\n")[0][:200]))
    sig = re.sub(r"\d+", "N", detail0.split("\n")[0][:60]) if kind0 in ("ice", "sanitizer", "leak", "compile-error", "cc-error") else ""
    lines = text.rstrip("\n").split("\n")
    rounds = 0
    while True:
        rounds += 1
        prot = protected(lines)
        cands = []
        for (i, j) in balanced_spans(lines, 0, len(lines)):
            if any(k in prot for k in range(i, j + 1)):
                continue
            cands.append((i, j))
        cands.sort(key=lambda s: -(s[1] - s[0]))
        def still_fails(text):
            """-> True when kotlinc accepts the program and mini fails on it in the same way."""
            outs, _ = jvm_batch(tools, {"x": text}, work)
            if "x" not in outs:
                return False
            w = os.path.join(work, "red.x")
            try:
                kind, detail, _ = judge(tools, text, outs["x"], a.variant, w)
            finally:
                shutil.rmtree(w, ignore_errors=True)
            return kind == kind0 and (not sig or re.sub(r"\d+", "N", detail.split("\n")[0][:60]) == sig)

        wins = []
        for bi in range(0, len(cands), a.batch):
            chunk = cands[bi : bi + a.batch]
            progs = {}
            for n, (i, j) in enumerate(chunk):
                progs["c%d" % n] = "\n".join(lines[:i] + lines[j + 1 :]) + "\n"
            outs, errs = jvm_batch(tools, progs, work)
            valid = [(n, outs["c%d" % n]) for n in range(len(chunk)) if "c%d" % n in outs]

            def test(nv):
                n, e = nv
                w = os.path.join(work, "red.%d" % n)
                try:
                    kind, detail, _ = judge(tools, progs["c%d" % n], e, a.variant, w)
                finally:
                    shutil.rmtree(w, ignore_errors=True)
                ok = kind == kind0 and (not sig or re.sub(r"\d+", "N", detail.split("\n")[0][:60]) == sig)
                return n, ok

            with cf.ThreadPoolExecutor(a.jobs) as ex:
                res = dict(ex.map(test, valid))
            wins = [chunk[n] for n, _ in valid if res.get(n)]
            if wins:
                break
        if not wins:
            break
        # several independent deletions at once (one check of the combination; when it fails, only the biggest one)
        picked = []
        for (i, j) in wins:
            if all(j < pi or i > pj for (pi, pj) in picked):
                picked.append((i, j))

        def without(spans):
            drop = set()
            for (i, j) in spans:
                drop.update(range(i, j + 1))
            return [l for k, l in enumerate(lines) if k not in drop]

        if len(picked) > 1 and still_fails("\n".join(without(picked)) + "\n"):
            new_lines = without(picked)
        else:
            new_lines = without(picked[:1])
        print("round %d: removed %d line(s), %d left" % (rounds, len(lines) - len(new_lines), len(new_lines)), flush=True)
        lines = new_lines
    # tidy: drop the prelude when nothing in the program uses it
    result = "\n".join(lines) + "\n"
    dest = os.path.join(tools.out, "reduced.%s.%s.kt" % (a.seed if not a.file else "file", a.variant))
    with open(dest, "w") as f:
        f.write(result)
    print("reduced program: %s (%d lines)" % (dest, len(lines)))
    return 0


# ---------------------------------------------------------------------------------------------------------------------- check (no JVM)
def cmd_check(a):
    tools = Tools(a)
    variants = a.variants.split(",")
    if any(v.endswith("asan") for v in variants):
        tools.build_rt_asan()
    work = os.path.join(tools.out, "work")
    os.makedirs(work, exist_ok=True)
    files = sorted(glob.glob(os.path.join(a.expect, "*.kt")))
    bad = 0

    def one(sv):
        f, v = sv
        text = open(f).read()
        exp = open(f[:-3] + ".out").read()
        w = os.path.join(work, "k%s.%s" % (os.path.basename(f)[:-3], v))
        try:
            return f, v, judge(tools, text, exp, v, w)
        finally:
            shutil.rmtree(w, ignore_errors=True)

    with cf.ThreadPoolExecutor(a.jobs) as ex:
        for f, v, (kind, detail, _) in ex.map(one, [(f, v) for f in files for v in variants]):
            if kind != "pass":
                bad += 1
                print("FAIL %s %s: %s %s" % (os.path.basename(f), v, kind, detail.split("\n")[0][:200]))
    print("%d programs x %d variants, %d failures" % (len(files), len(variants), bad))
    return 1 if bad else 0


def cmd_save(a):
    """Writes the programs of the seeds and the kotlinc/JVM output as <dest>/fuzzNNNN.kt and .out (the stored expectations of `check`)."""
    tools = Tools(a)
    lo, hi = [int(x) for x in a.seeds.split(":")]
    work = os.path.join(tools.out, "work")
    os.makedirs(work, exist_ok=True)
    os.makedirs(a.dest, exist_ok=True)
    seeds = list(range(lo, hi))
    progs = {s: make_program(s, a.size) for s in seeds}
    for i in range(0, len(seeds), a.batch):
        chunk = seeds[i : i + a.batch]
        outs, errs = jvm_batch(tools, {s: progs[s] for s in chunk}, work)
        for s, o in outs.items():
            base = os.path.join(a.dest, "fuzz%04d" % s)
            with open(base + ".kt", "w") as f:
                f.write("// generated by tools/fuzz/gen.py, seed %d; the expected output is kotlinc 2.1.20 on the JVM\n" % s + progs[s])
            with open(base + ".out", "w") as f:
                f.write(o)
    return 0


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("run", "reduce", "check", "save"):
        p = sub.add_parser(name)
        p.add_argument("--root", default=os.path.abspath(os.path.join(HERE, "../..")))
        p.add_argument("--out", default=os.path.join(tempfile.gettempdir(), "mini-fuzz"))
        p.add_argument("--kotlinc")
        p.add_argument("--jobs", type=int, default=4)
        p.add_argument("--size", type=int, default=14)
        p.add_argument("--batch", type=int, default=40)
        p.add_argument("--variants", default="c")
        if name in ("run", "save"):
            p.add_argument("--seeds", required=True)
        if name == "save":
            p.add_argument("--dest", required=True)
        if name == "reduce":
            p.add_argument("--seed", type=int)
            p.add_argument("--file")
            p.add_argument("--variant", default="c")
        if name == "check":
            p.add_argument("--expect", required=True)
    a = ap.parse_args()
    sys.exit({"run": cmd_run, "reduce": cmd_reduce, "check": cmd_check, "save": cmd_save}[a.cmd](a))


if __name__ == "__main__":
    main()
