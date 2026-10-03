import os
import struct
import subprocess
import random
import math
import sys

REF = os.environ.get("REF","./dsterf_ref")
CPP = os.environ.get("CPP","./dsterf_cpp_driver")

def write_input(path, d, e):
    n = len(d)
    with open(path, "wb") as f:
        f.write(struct.pack("<i", n))
        f.write(struct.pack("<%dd" % n, *d))
        if n > 1:
            f.write(struct.pack("<%dd" % (n - 1), *e))

def read_output(path, n):
    with open(path, "rb") as f:
        data = f.read()
    info = struct.unpack("<i", data[:4])[0]
    d = struct.unpack("<%dd" % n, data[4:4 + 8 * n])
    return info, d

def run(binpath, infile, outfile):
    r = subprocess.run([binpath, infile, outfile], capture_output=True, timeout=30)
    return r.returncode, r.stdout, r.stderr

def compare_case(name, d, e, tag_idx):
    n = len(d)
    infile = f"/tmp/case_{tag_idx}.bin"
    outref = f"/tmp/case_{tag_idx}_ref.bin"
    outcpp = f"/tmp/case_{tag_idx}_cpp.bin"
    write_input(infile, d, e)

    rc_ref, _, err_ref = run(REF, infile, outref)
    rc_cpp, _, err_cpp = run(CPP, infile, outcpp)

    if rc_ref != 0 or rc_cpp != 0:
        return False, f"{name}: nonzero exit (ref={rc_ref}, cpp={rc_cpp}) err_ref={err_ref[:200]} err_cpp={err_cpp[:200]}"

    info_ref, d_ref = read_output(outref, n)
    info_cpp, d_cpp = read_output(outcpp, n)

    if info_ref != info_cpp:
        return False, f"{name}: INFO mismatch ref={info_ref} cpp={info_cpp}"

    with open(outref, "rb") as f1, open(outcpp, "rb") as f2:
        bytes_ref = f1.read()
        bytes_cpp = f2.read()
    if bytes_ref != bytes_cpp:
        # find first differing double for a useful message
        for i in range(n):
            if d_ref[i] != d_cpp[i]:
                return False, (f"{name}: BYTE MISMATCH at D[{i}] ref={d_ref[i]!r} cpp={d_cpp[i]!r} "
                                f"(n={n}, INFO={info_ref})")
        return False, f"{name}: byte mismatch but all D[] compare equal?! (n={n})"
    return True, f"{name}: OK (n={n}, INFO={info_ref})"


def main():
    random.seed(20260913)
    results = []
    idx = 0

    # --- 1. Randomized tridiagonal matrices, many sizes ---
    for n in [2, 3, 4, 5, 6, 7, 8, 10, 15, 20, 30, 50, 75, 100, 150, 200, 300, 500]:
        for trial in range(6):
            d = [random.uniform(-100, 100) for _ in range(n)]
            e = [random.uniform(-50, 50) for _ in range(n - 1)] if n > 1 else []
            idx += 1
            results.append(compare_case(f"random n={n} t={trial}", d, e, idx))

    # --- 2. Adversarial scaling: very large norms (trigger ISCALE=1) ---
    for n in [5, 10, 30, 80, 200]:
        for scale in [1e150, 1e200, 1e300, 1.5e308]:
            d = [random.uniform(-1, 1) * scale for _ in range(n)]
            e = [random.uniform(-1, 1) * scale for _ in range(n - 1)]
            idx += 1
            results.append(compare_case(f"large-scale n={n} s={scale:g}", d, e, idx))

    # --- 3. Adversarial scaling: very small norms (trigger ISCALE=2) ---
    for n in [5, 10, 30, 80, 200]:
        for scale in [1e-150, 1e-200, 1e-300, 1e-310]:
            d = [random.uniform(-1, 1) * scale for _ in range(n)]
            e = [random.uniform(-1, 1) * scale for _ in range(n - 1)]
            idx += 1
            results.append(compare_case(f"small-scale n={n} s={scale:g}", d, e, idx))

    # --- 4. Multiple deflation blocks (deliberately zero some E entries) ---
    for n in [10, 30, 80, 200]:
        for trial in range(4):
            d = [random.uniform(-50, 50) for _ in range(n)]
            e = [random.uniform(-20, 20) for _ in range(n - 1)]
            # zero out several entries to force multiple independent blocks
            nzeros = max(1, n // 8)
            for _ in range(nzeros):
                e[random.randrange(n - 1)] = 0.0
            idx += 1
            results.append(compare_case(f"multiblock n={n} t={trial}", d, e, idx))

    # --- 5. Force both QL and QR direction selection ---
    # |D(LEND)| < |D(L)| triggers a lend/l swap (QR path); construct D
    # explicitly increasing and explicitly decreasing in magnitude.
    for n in [10, 30, 80, 200]:
        d_incr = [float(i + 1) for i in range(n)]         # |D(N)| > |D(1)| -> QL
        d_decr = [float(n - i) for i in range(n)]          # |D(1)| > |D(N)| -> QR
        e = [random.uniform(-5, 5) for _ in range(n - 1)]
        idx += 1
        results.append(compare_case(f"QL-direction n={n}", d_incr, e[:], idx))
        idx += 1
        results.append(compare_case(f"QR-direction n={n}", d_decr, e[:], idx))

    # --- 6. All-equal diagonal, all-equal off-diagonal (classic test matrix) ---
    for n in [3, 5, 10, 50, 200]:
        d = [2.0] * n
        e = [-1.0] * (n - 1)
        idx += 1
        results.append(compare_case(f"classic-tridiag n={n}", d, e, idx))

    # --- 7. Zero matrix, single element, near-duplicate eigenvalues ---
    idx += 1; results.append(compare_case("all-zero n=5", [0.0]*5, [0.0]*4, idx))
    idx += 1; results.append(compare_case("n=1", [42.0], [], idx))
    idx += 1; results.append(compare_case("n=0", [], [], idx))
    for n in [10, 50]:
        d = [1.0 + i * 1e-13 for i in range(n)]  # tightly clustered eigenvalues
        e = [1e-8] * (n - 1)
        idx += 1
        results.append(compare_case(f"clustered n={n}", d, e, idx))

    # --- 8. Mixed extreme scale within the SAME matrix ---
    for n in [10, 30, 80]:
        d = [random.choice([1e-250, 1e250, 1.0]) * random.uniform(-1, 1) for _ in range(n)]
        e = [random.choice([1e-250, 1e250, 1.0]) * random.uniform(-1, 1) for _ in range(n - 1)]
        idx += 1
        results.append(compare_case(f"mixed-extreme n={n}", d, e, idx))

    n_ok = sum(1 for ok, _ in results if ok)
    n_fail = len(results) - n_ok
    print(f"\n{'='*70}\n{n_ok}/{len(results)} PASSED, {n_fail} FAILED\n{'='*70}")
    if n_fail:
        print("\nFAILURES:")
        for ok, msg in results:
            if not ok:
                print("  " + msg)
    return 0 if n_fail == 0 else 1

if __name__ == "__main__":
    sys.exit(main())
