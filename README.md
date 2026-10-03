# Dense Generalized Symmetric-Definite Eigensolver on AArch64: where NEON, SVE and SME actually pay off

Solving **K φ = λ M φ** for dense symmetric K and symmetric positive-definite M — and exactly which of the seven phases the four hot kernels can accelerate with ARMv8-A NEON intrinsics, scalable SVE predication, or the SME ZA tile.

**ISAs** ARMv8-A NEON · SVE · SME (FMOPA, FP64) **Precision** IEEE-754 binary64 throughout **Language** C++23 **Reading time** \~20 min

### The short answer

- **Four hot kernels, three ISA tiers each**, resolved once at `dlopen` from `getauxval` into a function-pointer table. Nothing is re-tested in an inner loop.
- **Only one of the four is a genuine matrix-engine shape.** `panelOuterProduct` computes a real M×N block of A·BT. The other three are BLAS-1/2, and feeding one of those to FMOPA — as the SME dot product does — issues VL² multiplies to keep VL of them.
- **Blocking is what creates the BLAS-3 shape.** Phases 1, 3 and 6 defer their trailing updates into one batched call per panel; those three, and only those three, drive the panel kernel.
- **Phases 4 and 5 get nothing, and phase 4 must not be reordered at all** — it is a bit-identical port of reference LAPACK `DSTERF`, and reassociating it would destroy the property that makes it verifiable.
- **No hardware timing exists for the SVE and SME paths.** The motivating device has neither feature, and no shipping silicon is confirmed to implement `FEAT_SME_F64F64`. Emulator wall-clock measures the emulator.
- **Correctness is established bit-exactly, not within a tolerance:** the NEON and SVE paths produce byte-identical eigenvalues to each other, and the tridiagonal phase matches reference LAPACK compiled with gfortran across 185 cases.

## Contents

01. [The problem being solved](#1-the-problem-being-solved)
02. [The pipeline at a glance](#2-the-pipeline-at-a-glance)
03. [The four hot kernels, three ways](#3-the-four-hot-kernels-three-ways)
04. [Runtime ISA dispatch](#4-runtime-isa-dispatch)
05. [Phase 1 — Cholesky factorization](#5-phase-1--cholesky-factorization)
06. [Phase 2 — Congruence reduction](#6-phase-2--congruence-reduction)
07. [Phase 3 — Householder tridiagonalization](#7-phase-3--householder-tridiagonalization)
08. [Phase 4 — Tridiagonal eigenvalues](#8-phase-4--tridiagonal-eigenvalues)
09. [Phase 5 — Inverse iteration](#9-phase-5--inverse-iteration)
10. [Phase 6 — Back-transformation](#10-phase-6--back-transformation)
11. [Phase 7 — M-orthonormalization](#11-phase-7--m-orthonormalization)
12. [Why SME loses at small n](#12-why-sme-loses-at-small-n)
13. [Verification and reproducibility](#13-verification-and-reproducibility)
14. [Frequently asked questions](#14-frequently-asked-questions)
15. [Further reading](#15-further-reading)

## 1. The problem being solved

A dense generalized symmetric-definite eigenproblem asks for the pairs (λ, φ) satisfying

K φ = λ M φ,    K = KT,   M = MT ≻ 0 (positive definite)

In structural dynamics K is the stiffness matrix and M the mass matrix, λ = ω² gives the natural frequencies, and φ gives the mode shapes. This is LAPACK’s `dsygv` problem type 1. The same formulation appears in electronic structure as the overlap-matrix generalized eigenproblem, in chemometrics, and anywhere a quadratic form has to be diagonalized against a non-identity metric.

The classical dense algorithm does *not* attack the generalized problem directly. It factors M, uses that factor to turn the generalized problem into a standard one, solves the standard problem, and maps the answer back. Every one of those steps has a different memory access pattern — and that, not the FLOP count, is what decides whether a SIMD unit can help.

**The through-line of this page.** A vector unit accelerates a phase when the phase re-reads the same operand many times (arithmetic intensity) and touches memory in contiguous runs. A matrix engine additionally needs the work to be shaped like a matrix product. Three of the seven phases satisfy all of that; two satisfy the first two conditions but not the third; and two satisfy none of it and are simply sequential.

## 2. The pipeline at a glance

Seven phases. The *vector benefit* column is the whole point of this page.

| \# | Phase                              | What it computes       | Cost           | Hot kernel                                           | Vector benefit                               |
|----|------------------------------------|------------------------|----------------|------------------------------------------------------|----------------------------------------------|
| 1  | **Cholesky**                       | M = L LT               | n³/3           | `dotProduct` (panel), `panelOuterProduct` (SYRK)     | High blocked trailing update                 |
| 2  | **Congruence reduction**           | Ã = L−1 K L−T          | n³             | `axpyRow` (blocked TRSM)                             | High row-only, no gather                     |
| 3  | **Householder tridiagonalization** | T = Q1T Ã Q1           | 4n³/3          | `dotProduct`, `axpyRow`, `panelOuterProduct` (SYR2K) | High the dominant term                       |
| 4  | **Tridiagonal eigenvalues**        | eigenvalues of T       | \~O(n²)        | — scalar                                             | None sequential, and reordering is forbidden |
| 5  | **Inverse iteration**              | eigenvectors of T      | O(n) per mode  | norms only                                           | None Thomas recurrence is serial             |
| 6  | **Back-transformation**            | W = Q1Y, then Φ = L−TW | n³             | `panelOuterProduct` (GEMM), `axpyRow` (solve)        | High batched across all modes                |
| 7  | **M-orthonormalization**           | φTMφ = 1               | O(n²) per mode | `matVec`                                             | High dense square GEMV                       |

Phases 1, 2, 3 and 6 are panel-blocked (right-looking: factor or solve a narrow panel, then apply that panel’s whole effect to the trailing region in one batched update) through a single block-size parameter, `MPMR_DEFAULT_NB = 32`. The block size is a parameter of every blocked routine rather than a constant baked into one, because this is shared code that outlives any one caller’s problem size.

## 3. The four hot kernels, three ways

Four kernels are hot enough to justify per-ISA implementations. Everything else in the pipeline is either sequential by construction or O(n) bookkeeping.

| Kernel              | BLAS level | Called from                                                    | NEON                | SVE                       | SME                                |
|---------------------|------------|----------------------------------------------------------------|---------------------|---------------------------|------------------------------------|
| `dotProduct`        | 1          | Cholesky, both triangular solves, every Householder reflection | `vfmaq_f64`         | `svmla_f64_m` + `whilelt` | FMOPA diagonal — poor fit          |
| `matVec`            | 2          | back-transform, re-orthogonalization, residuals                | row-wise dot        | row-wise dot              | row-wise, one streaming region     |
| `axpyRow`           | 1          | blocked TRSM, batched triangular solve, rank-2 update          | `vfmaq_f64` + store | `svmla_f64_m` + `whilelt` | none — no outer-product structure  |
| `panelOuterProduct` | **3**      | Cholesky SYRK, Householder SYR2K, batched GEMM                 | nested dot          | j-unrolled ×2             | **full ZA tile** — the real target |

All three paths sum the same terms in a different order, so they are not bit-identical *to each other*. They agree far inside any residual bound that matters, but no assertion anywhere expects exact equality across tiers.

### 3.1 NEON: `float64x2_t` and `vfmaq_f64`

Two FP64 lanes, a fused multiply-add, a horizontal add at the end, and a scalar cleanup iteration for odd lengths. NEON is mandatory on AArch64, so this tier is always available and is the universal fallback.

```
[[nodiscard]] static double dotNeon(CSpan av_, CSpan bv_) {
    const int n = isize(av_);
    const double *a = av_.data();
    const double *b = bv_.data();
    float64x2_t acc = vdupq_n_f64(0.0);
    int j = 0;
    for (; j + 1 < n; j += 2) {
        float64x2_t av = vld1q_f64(a + j);
        float64x2_t bv = vld1q_f64(b + j);
        acc = vfmaq_f64(acc, av, bv);          // two lanes per iteration
    }
    double sum = vgetq_lane_f64(acc, 0) + vgetq_lane_f64(acc, 1);
    for (; j < n; j++) sum += a[j] * b[j];     // scalar cleanup for odd n
    return sum;
}
```

### 3.2 SVE: the same loop, minus the tail

The vector length is a runtime property, so the loop cannot be written around a compile-time width. `svwhilelt_b64` builds the active-lane mask directly from the loop counter against the length, which means the final partial vector is handled by predication and there is *no scalar cleanup loop at all*.

One detail is load-bearing: `svmla_f64_m` is the *merging* form. The zeroing form would leave inactive accumulator lanes undefined, and those lanes carry partial sums that the final reduction still has to read.

```
MPMR_SVE_TARGET
static double dotSve(CSpan av_, CSpan bv_) {
    const double *a = av_.data();
    const double *b = bv_.data();
    const uint64_t N = av_.size();
    const uint64_t vl = svcntd();              // lanes, not bytes; runtime value
    svfloat64_t acc = svdup_f64(0.0);
    for (uint64_t i = 0; i < N; i += vl) {
        svbool_t pg = svwhilelt_b64(i, N);     // the tail IS the predicate
        acc = svmla_f64_m(pg, acc, svld1_f64(pg, a + i), svld1_f64(pg, b + i));
    }
    return svaddv_f64(svptrue_b64(), acc);
}
```

### 3.3 SME: two very different uses of one instruction

FMOPA computes an outer product into a ZA tile: `ZA[p][q] += zn[p] * zm[q]`, over a full SVL×SVL block in one instruction. How well that serves you depends entirely on whether you want the whole block.

**The dot product is the bad case.** Feed the same slice to both operands and the diagonal `ZA[p][p]` accumulates `a[i+p]*b[i+p]`, so the dot product falls out of `trace(ZA)`. It is correct, and it is genuinely what the instruction can be made to do — but every off-diagonal entry is computed and thrown away.

```
MPMR_SME_TARGET
static double dotSmeStreaming(const double *a, const double *b, int n)
__arm_streaming __arm_inout("za") {
    const uint64_t N = (n <= 0) ? 0 : (uint64_t) n;
    const uint64_t vl = svcntd();              // STREAMING vector length (SVL/64)
    const svbool_t all = svptrue_b64();

    svzero_za();
    for (uint64_t i = 0; i < N; i += vl) {
        svbool_t pg = svwhilelt_b64(i, N);
        // Same slice on both operands, so ZA[p][p] accumulates a[i+p]*b[i+p].
        svmopa_za64_m(0, pg, pg, svld1_f64(pg, a + i), svld1_f64(pg, b + i));
    }

    // The dot product is trace(ZA). Every off-diagonal entry is discarded:
    // VL*VL multiplies were issued to keep VL of them.
    const uint64_t pmax = (N < vl) ? N : vl;
    double sum = 0.0;
    for (uint64_t p = 0; p < pmax; p++) {
        svfloat64_t slice = svread_hor_za64_m(svdup_f64(0.0), all, 0, (uint32_t) p);
        svbool_t lane = svbic_b_z(all, svwhilelt_b64((uint64_t) 0, p + 1),
                                  svwhilelt_b64((uint64_t) 0, p));
        sum += svaddv_f64(lane, slice);
    }
    return sum;
}
```

**The panel product is the good case.** Here `zn` is a *column* of the A panel (fixed k, varying i) and `zm` a column of B, so one FMOPA per k accumulates a genuine VL×VL block of A·BT, and every lane of the readout is a value the caller asked for.

**The structural inversion.** In the dot product, k — the reduction index — maps onto vector *lanes*. In the panel product it is the *loop* index, and the lanes carry i and j instead. That is why there is no `whilelt` partial-chunk handling over K in the panel kernel: “K not a multiple of the vector length” is not a case this kernel can have. The ragged edges live on M and N, and are predicated there.

Reading a column of a row-major panel is a gather, so both operands are *packed* into k-major staging buffers first, in ordinary non-streaming code. That is the standard GEMM packing step — O(K(M+N)) to enable O(MNK) of compute — and it keeps every container access outside the streaming region, which matters because calling a non-streaming function from streaming context forces the compiler to bracket it in `smstop`/`smstart`.

```
MPMR_SME_TARGET
static void panelOuterSmeStreaming(const double *Apack, int M,
                                   const double *Bpack, int N,
                                   int K, double *const *Cout, bool accumulate)
__arm_streaming __arm_inout("za") {
    const int vl = (int) svcntd();
    for (int i0 = 0; i0 < M; i0 += vl) {
        const int mb = (M - i0 < vl) ? (M - i0) : vl;
        const svbool_t pi = svwhilelt_b64((uint64_t) 0, (uint64_t) mb);
        for (int j0 = 0; j0 < N; j0 += vl) {
            const int nb = (N - j0 < vl) ? (N - j0) : vl;
            const svbool_t pj = svwhilelt_b64((uint64_t) 0, (uint64_t) nb);

            svzero_za();
            for (int k = 0; k < K; k++) {
                // zn is a COLUMN of A (fixed k, varying i), zm a column of B.
                // One FMOPA per k accumulates a whole VL x VL block of A*B^T.
                svfloat64_t zn = svld1_f64(pi, Apack + (size_t) k * (size_t) M + i0);
                svfloat64_t zm = svld1_f64(pj, Bpack + (size_t) k * (size_t) N + j0);
                svmopa_za64_m(0, pi, pj, zn, zm);
            }

            // Horizontal slice p IS output row i0+p. Nothing is discarded.
            for (int p = 0; p < mb; p++) {
                svfloat64_t slice = svread_hor_za64_m(svdup_f64(0.0), pj, 0, (uint32_t) p);
                double *__restrict crow = Cout[i0 + p] + j0;
                if (accumulate) slice = svadd_f64_x(pj, slice, svld1_f64(pj, crow));
                svst1_f64(pj, crow, slice);
            }
        }
    }
}
```

`FEAT_SME_F64F64` provides eight FP64 ZA tiles, each SVLd×SVLd where SVLd is `svcntd()` *evaluated inside streaming mode* — the streaming vector length, which need not equal the non-streaming one. This kernel uses one tile and loops over as many blocks as it takes, rather than using all eight: an M or N that outruns a single tile is already handled by that loop, and multi-tile blocking would buy throughput at the cost of a second, independently-wrong-able index scheme.

## 4. Runtime ISA dispatch

Selection happens once, at library load, into a `const` table of function pointers. This is dynamic initialization of a namespace-scope object, so it runs on `dlopen` before any entry point can be reached — there is no per-call guard variable in the O(n³) inner loop.

```
enum class IsaPath : unsigned char { Neon = 0, Sve = 1, Sme = 2 };

struct HotKernels {
    IsaPath  path;
    DotFn    dot;      // double(*)(CSpan, CSpan)
    MatVecFn matVec;   // void(*)(const Mat&, const Vec&, Vec&)
    AxpyFn   axpy;     // void(*)(DSpan, double, CSpan)
    PanelFn  panel;    // void(*)(RowsIn, RowsIn, int, RowsOut, bool)
};

static HotKernels selectHotKernels() {
    const unsigned long hw  = getauxval(AT_HWCAP);
    const unsigned long hw2 = getauxval(AT_HWCAP2);

    // HWCAP2_SME alongside HWCAP2_SME_F64F64: the F64F64 bit only says the
    // FP64 outer product exists, the base bit says ZA/streaming mode may be
    // entered at all.
    if ((hw2 & HWCAP2_SME) && (hw2 & HWCAP2_SME_F64F64)) {
        // axpyRow has no SME variant, so it is chosen independently --
        // SME does not imply base SVE on the same core.
        AxpyFn axpyChoice = (hw & HWCAP_SVE) ? &axpyRowSve : &axpyRowNeon;
        return HotKernels{.path = IsaPath::Sme, .dot = &dotSme, .matVec = &matVecSme,
                          .axpy = axpyChoice, .panel = &panelOuterSme};
    }
    if (hw & HWCAP_SVE) {
        return HotKernels{.path = IsaPath::Sve, .dot = &dotSve, .matVec = &matVecSve,
                          .axpy = &axpyRowSve, .panel = &panelOuterSve};
    }
    return HotKernels{.path = IsaPath::Neon, .dot = &dotNeon, .matVec = &matVecNeon,
                      .axpy = &axpyRowNeon, .panel = &panelOuterNeon};
}

// Namespace-scope dynamic initialization: runs on dlopen, before any entry
// point can be reached, so the O(n^3) inner loop tests no guard variable.
static const HotKernels g_hotKernels = selectHotKernels();
```

**The `getauxval` guard is load-bearing in the strongest sense.** Executing an SVE or SME instruction on a core that lacks the feature traps with `SIGILL`. There is no graceful degradation and no way to probe by trying. The HWCAP bit positions are architectural ABI, but older NDK kernel headers predate `HWCAP2_SME_F64F64` and `HWCAP2_SME2` — so they are pinned locally with `#ifndef` fallbacks and then `static_assert`ed against the platform header wherever one does define them, which turns a hand-pinned bit that disagrees with the ABI into a build error rather than a trap on somebody’s phone.

Note also that the SME branch picks `axpyRow` independently, by testing `HWCAP_SVE` directly. SME does not imply base SVE on the same core; the architecture makes no such guarantee.

## 5. Phase 1 — Cholesky factorization

M = L LT, right-looking and panel-blocked. Each outer step does three things: factor the nb×nb diagonal panel with the unblocked algorithm, solve the panel below it (a row-oriented TRSM, since each row below the panel is independent given L11), and then apply the panel’s entire effect to the trailing submatrix as one symmetric rank-nb update.

That third step is the BLAS-3-shaped part, and it is what the panel kernel exists for. Two details of the call are worth spelling out.

```
// Phase 1, step 3 -- the trailing SYRK: A22 -= L21 * L21^T, lower triangle.
const int mt = n - p - nb;
if (mt > 0) {
    const int base = p + nb;
    for (int i0 = 0; i0 < mt; i0 += kPanelStripRows) {
        const int rb   = std::min(kPanelStripRows, mt - i0);
        const int cols = i0 + rb;              // only j <= i is ever wanted
        scratch.a.resize((size_t) rb);
        scratch.b.resize((size_t) cols);
        for (int r = 0; r < rb;   r++) scratch.a[r] = L[base + i0 + r].data() + p;
        for (int c = 0; c < cols; c++) scratch.b[c] = L[base + c].data() + p;
        RowsOut C = scratch.stage(rb, cols);
        panelOuterProduct(RowsIn(scratch.a), RowsIn(scratch.b), nb, C, false);
        for (int r = 0; r < rb; r++) {
            double *Lrow = L[base + i0 + r].data() + base;
            const double *Crow = C[r];
            for (int c = 0; c <= i0 + r; c++) Lrow[c] -= Crow[c];
        }
    }
}
```

**Why strips rather than one big block.** The trailing region is (n−p−nb)², and staging all of it would be O(n²) of scratch. Striping bounds the staging buffer at O(strip×n) — and because only j ≤ i is ever wanted, stopping each strip’s column range at `i0 + rb` computes very nearly the lower triangle instead of the full square. The memory bound and the halved flop count come from the same decision.

**Why the strips cannot race.** The kernel reads columns p…p+nb−1 of the panel and writes only columns ≥ p+nb. No strip can observe a value another strip in the same sweep has already updated.

**An aliasing subtlety that constrains the kernel’s signature.** In this call site `scratch.a[r]` and `scratch.b[c]` are *the same pointer* whenever `c == i0 + r` — a SYRK reads the same panel on both sides. So the panel kernel may mark its *output* row `__restrict`, but never its two input panels against each other. The same reasoning excludes `__restrict` from the dot product entirely: `dotProduct(v, v)` is a real call site.

## 6. Phase 2 — Congruence reduction

Ã = L−1 K L−T, done as two triangular solves. Both are of the shape L X = B for a full n×n right-hand side — a matrix TRSM, not a single-vector one — and both are panel-blocked: once a row-panel of X is solved, its effect on all remaining unsolved rows is applied as one row-oriented linear combination before moving on.

The thing to notice is what is *absent*. Every array access stays row-wise: the update for row p+i is a weighted sum of already-computed *rows* of X, never a column of anything. That is why this phase reduces to `axpyRow` and not to a dot product — and why it gets no SME variant, since AXPY is BLAS-1 and has no outer-product structure for a matrix engine to exploit.

K is symmetric, which is what lets the first solve use K’s rows directly as right-hand sides: column j of K *is* row j of K, so no gather is needed to form it.

## 7. Phase 3 — Householder tridiagonalization

T = Q1T Ã Q1, accumulating Q1. At 4n³/3 this is the dominant phase of the whole pipeline, so it is where blocking matters most.

### 7.1 Building the reflector

Per column: form x from the subdiagonal, take its norm, pick the sign that avoids cancellation (α = −sign(x0)·‖x‖), build v, normalize. Three O(n) dot products, all dispatched.

One step here is easy to get wrong in a blocked formulation. The reflector’s defining property already determines what column k becomes — `A[k+1][k] = alpha`, everything below it zero — so that is written directly rather than derived from a matrix update. A rank-2 update on the trailing submatrix alone never touches column k at all; omitting the explicit write produces eigenvalues that look superficially close — a small perturbation — while residuals go from 1e−11 to the hundreds. It was caught by running the cross-check against the unblocked version, not by re-reading the algebra, which still looked correct on paper.

### 7.2 The panel: LATRD-style deferred correction

Within a panel, each new reflector must see a trailing region that has been updated by the earlier reflectors of the *same* panel — but those updates have deliberately not been applied to A yet. So column k, the diagonal entry A\[k]\[k], and the w vector each carry an explicit correction computed from the V and W factors accumulated so far.

### 7.3 Between panels: the SYR2K

At the end of each panel, all nb reflectors’ cumulative effect lands on the region beyond the panel in one batched symmetric rank-2k update. This is two panel-kernel calls, and it is precisely why the primitive carries an `accumulate` flag instead of always assigning:

```
// Phase 3 -- the deferred SYR2K, applying all nb reflectors at once:
//     A[i][j] -= 2 * ( (V W^T)[i][j] + (W V^T)[i][j] )
// Two calls: the first ASSIGNS V*W^T into the staging block, the second
// ACCUMULATES W*V^T on top. That is why panelOuterProduct carries an
// `accumulate` flag rather than always assigning.
RowsOut C = scratch.stage(rb, cols);
for (int r = 0; r < rb;   r++) scratch.a[r] = V[base + i0 + r - p].data();
for (int c = 0; c < cols; c++) scratch.b[c] = W[base + c - p].data();
panelOuterProduct(RowsIn(scratch.a), RowsIn(scratch.b), nb, C, false);

for (int r = 0; r < rb;   r++) scratch.a[r] = W[base + i0 + r - p].data();
for (int c = 0; c < cols; c++) scratch.b[c] = V[base + c - p].data();
panelOuterProduct(RowsIn(scratch.a), RowsIn(scratch.b), nb, C, true);
```

Per (i, j) the two length-nb reductions are summed in exactly the order the unblocked pair of dot products would have used, which is what makes the blocked and unblocked paths comparable to the last bit on the NEON and SVE tiers.

Q’s own accumulation stays one reflector at a time. It is O(n) per reflector either way, and it is not the part blocking targets.

**Both versions are kept.** Every blocked phase sits in the file next to its unblocked counterpart, and a self-test cross-validates them element-by-element on the intermediate results — the L factor, the reduced Ã, the tridiagonal and Q — not merely on downstream eigenvalues. The unblocked routines are not dead code, and the build enforces that: `-Werror=unused-function` turns “the last caller of a reference implementation disappeared” into a compile error rather than a cross-check that quietly ceased to exist.

## 8. Phase 4 — Tridiagonal eigenvalues

Eigenvalues of the symmetric tridiagonal T, via the Pal–Walker–Kahan variant of the QL/QR algorithm. This phase gets **no vector benefit at all**, for two independent reasons.

**It is a first-order dependency chain.** The bulge-chase is a three-element stencil sweeping a tridiagonal: each step’s rotation depends on the previous step’s output through the GAMMA/OLDGAM/OLDC recurrence. There is nothing to put in lane 1 while lane 0 is being computed. This is not a blocking problem — it is not a candidate for panel blocking at all.

**And reordering is forbidden anyway.** This is a port of reference LAPACK `DSTERF` together with its exact dependency closure (DLAE2, DLANST’s ‘M’ branch, DLAPY2, DLAMCH’s ‘E’/‘S’/‘O’ codes, DLASCL, DLASRT), and it is verified *bit-for-bit* — not within a tolerance — against Reference-LAPACK compiled and run with gfortran. Any “mathematically equivalent but differently ordered” rewrite, vectorized or not, destroys the property that makes the phase verifiable at all.

**A build requirement that only surfaced on the target ISA.** `-fno-fast-math` does *not* disable floating-point contraction. This toolchain reports `-ffp-contract=fast` as the active default on both x86\_64 and aarch64 even under `-fno-fast-math` — and aarch64 has cheap native FMA while baseline x86\_64 does not. So identical source under identical flags silently contracted some expressions on the device and never on the host, breaking bit-identity between them. `-ffp-contract=off` is therefore **required**, at the build-system level, and must not be assumed to follow from `-fno-fast-math`.

The port itself is structured C++ — structured loops rather than the reference’s GOTO graph, 0-based arrays, `std::span` at the leaf routines, `constexpr` machine constants. What is *not* modernized is the arithmetic: same operations, same operands, same order. That distinction is the whole discipline. Each structural change was landed separately with the full bit-comparison re-run in between, and every one came back byte-identical.

## 9. Phase 5 — Inverse iteration

Given an eigenvalue, the corresponding eigenvector of T comes from a few steps of inverse iteration: solve (T − λI)y = yprev, renormalize, repeat. The solve is a Thomas recurrence on a tridiagonal — forward elimination then back substitution, each element depending on the last. **Serial by construction.** The only dispatched work is the norm, which is O(n) against an O(n) solve, so it is not where the time goes.

The shift makes T − λI deliberately near-singular, which is the point — that is what amplifies the wanted eigendirection — but it means pivots can land arbitrarily close to zero, so the solve is regularized with a floor scaled to the matrix norm.

Degenerate eigenvalues need care that is easy to miss. Inverse iteration is deterministic, so members of a numerically degenerate cluster come back as *exact duplicates* of the first rather than as a basis for the shared eigenspace. Left alone that is a silent-wrong-answer bug: φaTMφb comes out 1 instead of 0, and any modal-mass sum double-counts one direction and drops another. Clusters are detected by relative separation, each member is started from a different seed vector, and members are orthogonalized against the already-accepted modes of the same cluster in the M inner product.

## 10. Phase 6 — Back-transformation

The eigenvectors of T are not the eigenvectors of the original problem. They have to come back through both transformations: Φ = L−T Q1 Y. Done per mode that is a matVec plus a triangular solve, once each, n times. Done as written here it is two matrix operations for *all* modes at once.

```
// Phase 6 -- W = Q * Yt^T for every mode at once. Yt holds one ROW per mode,
// which is both how inverse iteration produces it and the layout the panel
// kernel's B operand wants: no transpose exists anywhere on this path.
[[nodiscard]] static Mat matMulBatchedT(const Mat &Q, const Mat &Yt) {
    const int n  = isize(Q);
    const int nm = isize(Yt);
    Mat W(n, nm, 0.0);
    if (n == 0 || nm == 0) return W;
    std::vector<const double *> ap((size_t) n), bp((size_t) nm);
    std::vector<double *> cp((size_t) n);
    for (int i = 0; i < n;  i++) { ap[i] = Q[i].data(); cp[i] = W[i].data(); }
    for (int j = 0; j < nm; j++)   bp[j] = Yt[j].data();
    panelOuterProduct(RowsIn(ap), RowsIn(bp), n, RowsOut(cp), false);
    return W;
}
```

Two design choices make this work without ever reading a column.

**Y is stored transposed** — one row per mode. That is both how inverse iteration naturally produces it (a contiguous length-n vector per mode, so storing it is a whole-row move rather than n strided single-element writes) and exactly the operand layout the panel kernel wants. No transpose is introduced anywhere on this path; the orientation removes one.

**The triangular solve is flipped.** Solving LTX = B the usual way reads a column of L: “for each row i, sum L\[k]\[i] over k &gt; i”. Instead, rows are processed top-down from n−1, and the moment row i is finalized its contribution is immediately propagated into every not-yet-finalized row j &lt; i by sweeping *along* row i of L, which is contiguous. Same result, same arithmetic, and the access pattern reduces to `axpyRow`.

## 11. Phase 7 — M-orthonormalization

Each returned mode satisfies φTMφ = 1. Computing that norm is a dense square GEMV against M followed by a dot product — O(n²) per mode, contiguous, and the cleanest `matVec` target in the pipeline.

Mass normalization is a deliberate choice over the alternative of scaling each mode so that one chosen coordinate equals 1. That alternative divides by a coordinate with no guaranteed magnitude: a mode with little amplitude at that specific degree of freedom produces a tiny divisor, and whatever rounding error already exists is amplified by however much that division blows up. Measured on this pipeline, divisors as small as 1e−7 turned underlying eigenvectors accurate to \~1e−14 into residuals of 1e−2. φTMφ is bounded away from zero for any valid mode because M is positive definite, so the mass norm has no such failure mode.

## 12. Why SME loses at small n

Blocking makes phases 1, 3 and 6 BLAS-3-shaped, which is the *precondition* for a matrix engine to help at all. It is not sufficient.

FMOPA performs VL² multiply-accumulates per instruction. That is a bargain when you want all VL² results and the operands are reused across many such instructions. It is a poor trade when the useful output is VL values (a dot product) or when the problem is smaller than a single tile.

**The blunt consequence for the motivating problem.** At n = 24 with NB = 32, there is no panel narrower than the matrix — blocking degenerates to the unblocked algorithm entirely, and *none* of the panel-kernel code executes. The SME path on a 24-DOF problem is not slow; it is unreachable. Blocking was worth doing because this is shared code that will see larger callers, not because it unlocks anything at this size.

And on the device that motivated the work — an Exynos 990, ARMv8.2 — there is no SVE and no SME at all, so the dispatcher resolves to NEON and the other two tiers are dead code that never runs. They exist for architectural coverage and for a future part, and the load-time log line reports which tier was actually selected precisely so that no measurement is ever attributed to the wrong kernel.

There is a further open question worth stating plainly rather than assuming away: `FEAT_SME_F64F64` is an *optional* feature, separate from base SME. Every public account of SME and SME2 reaching mobile silicon frames it around AI inference in BF16, FP16 and INT8 — never FP64. Whether any shipping part implements the double-precision outer product is, as far as this work can establish, unconfirmed.

## 13. Verification and reproducibility

Three properties are checked, and they are different in kind.

### Agreement between the tiers

The panel kernel is cross-validated across a sweep of 1727 panel shapes — empty and singleton dimensions, K below, at and above a plausible vector length, K prime, M and N that do and do not fill a ZA tile, strongly rectangular panels in both directions, and both the assigning and accumulating modes against a pre-seeded output. Each tier is compared against a portable scalar reference *and* against the per-element dot-product loop it replaced. Worst observed relative deviation is \~3e−15; the SME tier is the closest of the three to the scalar reference, because FMOPA accumulates fused and in strict k order.

### Agreement with the unblocked algorithm

Blocked Cholesky, congruence, and tridiagonalization are each compared element-by-element against their unblocked counterparts across a sweep of sizes and block widths, on the intermediate results rather than on downstream eigenvalues.

### Agreement with reference LAPACK

The tridiagonal phase is compared *byte-for-byte* against Reference-LAPACK `DSTERF` built with gfortran, over 185 cases: randomized tridiagonals from n = 2 to 500, adversarial large- and small-magnitude scaling chosen to exercise both branches of the internal rescaling, deliberately multi-block matrices, both QL and QR direction selection, and the edge cases (n = 0, n = 1, all-zero, clustered eigenvalues). The comparison is on raw binary output, never a decimal round trip. It is run on aarch64, not merely on the development host — which is the only reason the `-ffp-contract` divergence described in §8 was ever found.

### What “reproducible” does and does not mean here

- The **NEON and SVE tiers are bit-identical** to the per-element formulation they replaced — every eigenvalue, byte for byte, at n = 64 and n = 257.
- The **SME tier is not**, and should not be expected to be: it genuinely accumulates differently. It differs by \~5e−15 relative, and its residuals are marginally *better*.
- The **tridiagonal phase is bit-identical to Fortran LAPACK**, which is a much stronger claim than the other two and is the only one stated as byte equality against an external reference.
- **No timing claim is made.** The only execution vehicle available for the SVE and SME tiers is emulation, and emulator wall-clock measures the emulator — it says nothing about hardware.

## 14. Frequently asked questions

Why not just call LAPACK `dsygv`?

For production, on a platform with a tuned BLAS, you should. This exists to answer a narrower question — which phases of the algorithm can use which AArch64 vector features, and by what mechanism — which a library call hides by design. The tridiagonal phase here *is* reference LAPACK, ported line-for-line and checked byte-for-byte, precisely because there was no reason to invent an alternative to a routine that already has a definitive implementation.

Why is there no SME variant of `axpyRow`?

AXPY is y += a·x: one scalar, two vectors, no outer-product structure whatsoever. FMOPA has nothing to exploit, for the same reason it does not help a plain dot product. On an SME-capable core, `axpyRow` resolves to the SVE implementation — and that choice is made by testing `HWCAP_SVE` directly rather than inferring it from the SME bit, because the architecture does not guarantee that one implies the other.

Does the panel kernel need K to be a multiple of the vector length?

No, and more than that: the case cannot arise. K is the loop index in that kernel, not the lane index — lanes carry the output block’s i and j. Partial predication applies to M and N, which is where the ragged edges actually are. This is the single most important structural difference between the panel kernel and the SME dot product, and it is why the two look so different despite issuing the same instruction.

Why pack the operands instead of using gather loads?

FMOPA wants, for each k, a contiguous vector of A over i — which is a column of a row-major panel. Packing into k-major staging buffers costs O(K(M+N)) to enable O(MNK) of compute, so it amortizes as soon as M and N approach a tile. It also keeps every `std::vector` access outside the streaming region: those accessors are ordinary non-streaming functions, and calling one from streaming context makes the compiler bracket it in `smstop`/`smstart` — exactly the per-row transition cost the single-region design exists to avoid.

Is `-ffast-math` ever worth trying here?

No. The convergence tests in the tridiagonal iteration and the residual assertions in the harness both depend on IEEE semantics, and fast-math reassociation perturbs them. Separately — and this is the part that catches people — `-fno-fast-math` alone is *not enough* to guarantee reproducibility across ISAs, because it leaves FP contraction enabled. See §8.

Why `std::span` in kernels this hot?

A span is a pointer and a length, which is what the old `(const double*, ..., int)` signature already passed — same two registers, no indirection. What changes is that the length can no longer be passed inconsistently with the pointer. It is worth being precise about the limit, though: `span::operator[]` is unchecked, so the benefit is entirely at the call boundary. That happens to be where the mistakes actually were.

Why is `Mat` a contiguous buffer rather than a vector of vectors?

A vector of vectors is one heap allocation per row, with no guarantee that row i+1 lands anywhere near row i, and a pointer chase on every row access. A single buffer removes all three. Rows remain the primary access path — `operator[](i)` returns a span over one row — because “never read a column” is the invariant the blocked phases are built on; the 2D `mdspan` view is there for the places where the matrix shape, rather than the row, is what the code is about.

How would this change if SVE2 or SME2 were targeted?

SVE2 adds integer and fixed-point work that this FP64 pipeline does not use. SME2’s multi-vector forms would let the panel kernel drive several ZA tiles per instruction group, which is a real throughput argument for the one kernel that has the right shape — but it would not change the classification of any phase in the table in §2. Phases 4 and 5 stay sequential regardless of how wide the machine gets.

## 15. Further reading

- **Arm C Language Extensions (ACLE)** — the normative definition of the SVE and SME intrinsics used here, including the `__arm_streaming` and `__arm_new("za")` keywords and the ZA state rules. [github.com/ARM-software/acle](https://github.com/ARM-software/acle)
- **Arm Architecture Reference Manual, SME supplement** — ZA tile geometry, streaming vector length, and the `FEAT_SME_F64F64` feature definition. [developer.arm.com/documentation/ddi0487](https://developer.arm.com/documentation/ddi0487/latest/)
- **Arm Neon Intrinsics Reference** — `float64x2_t`, `vfmaq_f64` and the rest of the baseline tier. [developer.arm.com/architectures/instruction-sets/intrinsics](https://developer.arm.com/architectures/instruction-sets/intrinsics/)
- **Reference LAPACK** — `dsygv`, `dsytrd`/`dlatrd` for the blocked tridiagonalization pattern, and `dsterf`, which this pipeline ports verbatim. [netlib.org/lapack/explore-html](https://netlib.org/lapack/explore-html/)
- **LAPACK Users’ Guide** (Anderson et al.) — the standard account of why the generalized problem is reduced rather than attacked directly. [netlib.org/lapack/lug](https://netlib.org/lapack/lug/)
- **Golub &amp; Van Loan, *Matrix Computations*** — Householder tridiagonalization, the implicit QL/QR iteration, and inverse iteration, including the degenerate-cluster problem described in §9.
- **Parlett, *The Symmetric Eigenvalue Problem*** — the definitive treatment of the symmetric case, and the source for why shifted inverse iteration behaves as it does near a converged eigenvalue.

All measurements described on this page are correctness measurements. No performance claim is made for the SVE or SME tiers, because no hardware implementing `FEAT_SME_F64F64` was available to make one on.