#!/usr/bin/env python3
"""Runs a command and reports its peak resident set size (from wait4's rusage of that very child).

    scripts/peak-rss.py [--timeout S] [--stdout FILE] -- cmd args...

Prints `<peak KiB>` on stdout (the child's own stdout goes to --stdout, default /dev/null); the exit status is the child's
(124 on timeout). Linux reports ru_maxrss in KiB, macOS in bytes. `/usr/bin/time -v` is not installed everywhere."""
import os, resource, subprocess, sys, time


def main():
    args = sys.argv[1:]
    timeout = 300.0
    out = os.devnull
    while args and args[0] != "--":
        if args[0] == "--timeout":
            timeout = float(args[1]); args = args[2:]
        elif args[0] == "--stdout":
            out = args[1]; args = args[2:]
        else:
            sys.exit("peak-rss.py: unknown option " + args[0])
    cmd = args[1:]
    if not cmd:
        sys.exit("usage: peak-rss.py [--timeout S] [--stdout FILE] -- cmd args...")
    with open(out, "wb") as o:
        p = subprocess.Popen(cmd, stdout=o)
        start = time.time()
        status = None
        while True:
            pid, st, ru = os.wait4(p.pid, os.WNOHANG)
            if pid != 0:
                status, usage = st, ru
                break
            if time.time() - start > timeout:
                p.kill()
                pid, st, usage = os.wait4(p.pid, 0)
                print(usage.ru_maxrss // (1024 if sys.platform == "darwin" else 1))
                sys.exit(124)
            time.sleep(0.01)
    kib = usage.ru_maxrss // (1024 if sys.platform == "darwin" else 1)
    print(kib)
    sys.exit(os.waitstatus_to_exitcode(status))


main()
