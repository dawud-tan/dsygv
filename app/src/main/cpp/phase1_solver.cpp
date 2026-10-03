// phase1_solver.cpp
//
// Phase 1 reference implementation: full generalized symmetric eigensolve
// (K phi = lambda M phi) on the CPU cluster only, FP64, vectorized on the hot
// loops via runtime ISA dispatch (SME / SVE / NEON -- see the kernel section
// below). No GPU/OpenCL involvement at all.
//
// Pipeline: Cholesky(M) -> congruence transform to standard form ->
// Householder tridiagonalization -> implicit bulge-chase QR (Wilkinson
// shift, single-active-block deflation) for eigenvalues -> regularized
// inverse iteration for eigenvectors -> back-transform -> mass-normalize
// (phi^T M phi = 1; see the comment at the normalization step in solve()
// for why this replaced an earlier, fragile roof-relative convention).
//
// Algorithm core verified against a known-answer 3x3 case and cross-checked
// against an independent (slow, structurally different) dense-Givens
// implementation on 300+ random matrices before being ported here — see the
// accompanying report for the verification trail.
//
// Cholesky, the congruence reduction, Householder tridiagonalization, and
// eigenvector back-transformation are all panel-blocked (right-looking,
// BLAS-3-shaped: panel factor/solve, then one deferred batched update to
// the trailing region instead of many small ones) via a generic block-size
// parameter (MPMR_DEFAULT_NB), not hardcoded to any one caller's problem
// size. This library is shared and reused beyond the one n=24 house that
// motivated it, so genericity across n mattered more here than the modest,
// hard-to-measure difference blocking makes at that specific size. Each
// blocked phase was cross-validated directly against its own already-
// verified unblocked counterpart (element-by-element on the intermediate
// L/Atilde/tridiagonal-and-Q results, not just downstream eigenvalues)
// before replacing it as the path solve() actually calls -- both versions
// are kept side by side for exactly that reason, not as dead code. The QR
// bulge-chase itself remains an unblocked, inherently sequential recurrence
// (a first-order dependency chain -- not a candidate for panel blocking at
// all, blocked or not).
//
// Those two hot loops now have three implementations -- SME/FMOPA, SVE, and
// the original NEON -- selected once at library load from getauxval(), and
// reached through the dotProduct() / matVec() wrappers. NEON is the baseline
// and is what every existing measurement and verification run used.

#include <jni.h>
#include <android/log.h>
#include <arm_neon.h>

#include <cstdio>
#include <cstring>
#include <cmath>
#include <cstdint>
#include <chrono>
#include <vector>
#include <span>
#include <utility>
#include <iterator>
#include <charconv>
#include <expected>
#include <memory>
#include <mdspan>
#include <new>
#include <algorithm>
#include <random>
#include <string>
#include <sstream>
#include <fstream>
#include <limits>

#include <sys/auxv.h>

#if defined(__aarch64__)

#include <asm/hwcap.h>

#endif

// Runtime CPU feature bits. The NDK's kernel headers lag the Linux uapi
// definitions, so pin the bit positions locally instead of assuming the
// platform header carries them -- an older NDK is missing SME2 and
// SME_F64F64 outright and the file would not compile. These positions are
// ABI, fixed by arch/arm64/include/uapi/asm/hwcap.h, so redefining them
// here can only ever agree with a header that does have them.
#ifndef HWCAP_SVE
#define HWCAP_SVE         (1UL << 22)
#endif
#ifndef HWCAP2_SVE2
#define HWCAP2_SVE2       (1UL << 1)
#endif
#ifndef HWCAP2_SME
#define HWCAP2_SME        (1UL << 23)
#endif
#ifndef HWCAP2_SME_F64F64
#define HWCAP2_SME_F64F64 (1UL << 25)
#endif
#ifndef HWCAP2_SME2
#define HWCAP2_SME2       (1UL << 37)
#endif

// Typed constexpr mirrors of the macros above. The macros have to stay -- the
// #ifndef interop with the platform header is the whole point -- but nothing
// else in this file should be reading untyped `1UL << n` expressions, and a
// constant that is silently wrong is exactly the failure mode the getauxval
// guard cannot survive (calling an absent path traps with SIGILL).
//
// The static_asserts are the payoff: on an NDK whose headers DO define these,
// the mirrors are checked against the kernel's own values at compile time, so
// a hand-pinned bit that disagrees with the ABI is a build error rather than a
// runtime trap on somebody's phone. On an older NDK they compare against this
// file's own fallbacks and are tautological -- which is the correct behaviour,
// there being nothing to check against.
namespace hwcaps {
    inline constexpr unsigned long kSve = HWCAP_SVE;
    inline constexpr unsigned long kSve2 = HWCAP2_SVE2;
    inline constexpr unsigned long kSme = HWCAP2_SME;
    inline constexpr unsigned long kSmeF64F64 = HWCAP2_SME_F64F64;
    inline constexpr unsigned long kSme2 = HWCAP2_SME2;

    static_assert(kSve == (1UL << 22), "HWCAP_SVE bit position changed");
    static_assert(kSve2 == (1UL << 1), "HWCAP2_SVE2 bit position changed");
    static_assert(kSme == (1UL << 23), "HWCAP2_SME bit position changed");
    static_assert(kSmeF64F64 == (1UL << 25), "HWCAP2_SME_F64F64 bit position changed");
    static_assert(kSme2 == (1UL << 37), "HWCAP2_SME2 bit position changed");
}

#define TAG "MPMR_PHASE1"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using Clock = std::chrono::steady_clock;

static inline double msSince(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

// Signed element count as int. std::ssize supplies the SIGNED count, which
// is what every loop here actually wants (`i < n - 1` on an unsigned count
// wraps to a huge value when n == 0); the narrowing to int is then explicit
// and greppable, where the old C-style `isize(x)` was neither.
template<class C>
[[nodiscard]] static constexpr int isize(const C &c) {
    return static_cast<int>(std::ssize(c));
}

// Non-owning views over the FP64 buffers every kernel works on. The kernels
// used to take (const double*, ..., int) triples; the length is now carried
// by the view, so it cannot be passed inconsistently with the pointer. Note
// what this does NOT buy: span::operator[] is unchecked, so the win is
// entirely at the call boundary, which is where the mistakes actually were.
using CSpan = std::span<const double>;
using DSpan = std::span<double>;
// Panels are arrays of row pointers; the span supplies the ROW COUNT. The
// row length (K) still has to be passed separately -- a raw row pointer
// carries no extent, and that is an honest limit of this representation
// rather than something span fixes.
using RowsIn = std::span<const double *const>;
using RowsOut = std::span<double *const>;

// Over-aligned allocator for the SME packing buffers. std::vector<double>
// only guarantees alignof(max_align_t) (16 bytes here), which can leave a
// packed column slice straddling a cache line.
//
// Be clear about the size of this win: svld1_f64 does not REQUIRE alignment,
// and the deeper optimisation -- padding the packed leading dimension to a
// multiple of the streaming vector length so that every column slice starts
// aligned, not just the base -- is a different change that would alter the
// kernel's indexing. It is not made here because it cannot be justified
// without a benchmark, and the only measurement available is QEMU, where
// timings are an emulation artifact rather than hardware behaviour.
template<class T, std::size_t Align>
struct AlignedAllocator {
    using value_type = T;

    AlignedAllocator() = default;

    template<class U>
    constexpr explicit AlignedAllocator(const AlignedAllocator<U, Align> &) noexcept {}

    [[nodiscard]] T *allocate(std::size_t n) {
        if (n > std::size_t(-1) / sizeof(T)) throw std::bad_alloc();
        return static_cast<T *>(::operator new(n * sizeof(T), std::align_val_t{Align}));
    }

    void deallocate(T *p, std::size_t) noexcept {
        ::operator delete(p, std::align_val_t{Align});
    }

    template<class U>
    struct rebind {
        using other = AlignedAllocator<U, Align>;
    };

    bool operator==(const AlignedAllocator &) const noexcept { return true; }
};

inline constexpr std::size_t kPackAlign = 64;   // one cache line
using PackVec = std::vector<double, AlignedAllocator<double, kPackAlign>>;

typedef std::vector<double> Vec;

// Contiguous row-major matrix.
//
// This WAS std::vector<std::vector<double>>: one separate heap allocation per
// row, so an n x n matrix cost n allocations, consecutive rows could land
// anywhere relative to each other, and every row access was a pointer chase
// through the outer vector. Now it is a single buffer.
//
// Rows stay the PRIMARY access path, deliberately. The whole pipeline is
// built on "never read a column" -- that invariant is why the blocked phases
// are row-oriented at all -- so operator[](i) hands back a span over one row
// and every existing A[i][j], A[i].data() and A[i].size() use keeps working
// unchanged. What changes is only where those rows live.
//
// operator[](i, j) is the C++23 multidimensional subscript, and view() hands
// out a std::mdspan, for the places where the 2D shape is the point rather
// than the row -- marshalling and elementwise comparison, mostly.
class Mat {
    int rows_ = 0, cols_ = 0;
    std::vector<double> buf_;

public:
    Mat() = default;

    Mat(int rows, int cols, double fill = 0.0)
            : rows_(rows > 0 ? rows : 0), cols_(cols > 0 ? cols : 0),
              buf_(static_cast<std::size_t>(rows_) * static_cast<std::size_t>(cols_), fill) {}

    Mat(std::initializer_list<std::initializer_list<double>> init) {
        rows_ = static_cast<int>(init.size());
        cols_ = rows_ > 0 ? static_cast<int>(init.begin()->size()) : 0;
        buf_.reserve(static_cast<std::size_t>(rows_) * static_cast<std::size_t>(cols_));
        for (const auto &r: init) buf_.insert(buf_.end(), r.begin(), r.end());
    }

    [[nodiscard]] DSpan operator[](int i) {
        return DSpan(buf_.data() + static_cast<std::size_t>(i) * cols_, cols_);
    }

    [[nodiscard]] CSpan operator[](int i) const {
        return CSpan(buf_.data() + static_cast<std::size_t>(i) * cols_, cols_);
    }

    [[nodiscard]] double &operator[](int i, int j) {
        return buf_[static_cast<std::size_t>(i) * cols_ + j];
    }

    [[nodiscard]] const double &operator[](int i, int j) const {
        return buf_[static_cast<std::size_t>(i) * cols_ + j];
    }

    using View = std::mdspan<double, std::dextents<int, 2>>;
    using ConstView = std::mdspan<const double, std::dextents<int, 2>>;

    [[nodiscard]] View view() { return View(buf_.data(), rows_, cols_); }

    [[nodiscard]] ConstView view() const { return ConstView(buf_.data(), rows_, cols_); }

    [[nodiscard]] std::size_t size() const { return static_cast<std::size_t>(rows_); }

    [[nodiscard]] int rows() const { return rows_; }

    [[nodiscard]] int cols() const { return cols_; }

    [[nodiscard]] bool empty() const { return rows_ == 0; }

    // Row assignment from a Vec, which `Yt[li] = someVec` used to express when
    // operator[] returned a real vector and now cannot, a span being a view.
    void setRow(int i, CSpan src) {
        const int k = cols_ < isize(src) ? cols_ : isize(src);
        std::copy_n(src.data(), k, buf_.data() + static_cast<std::size_t>(i) * cols_);
    }
};

[[nodiscard]] static Mat matZeros(int n) { return Mat(n, n, 0.0); }

// ---------------------------------------------------------------------------
// Hot-loop kernels: three ISA paths, chosen once at library load.
//
//   SME  -- FP64 outer-product-and-accumulate (FMOPA) into a ZA tile.
//   SVE  -- scalable-vector FMLA, tails handled by while-predication.
//   NEON -- float64x2_t + vfmaq_f64. Mandatory on arm64-v8a, so this one is
//           always available and is the fallback.
//
// Only two kernels are hot enough to justify this. dotProduct() is called
// from Cholesky, both triangular solves, and every Householder reflection
// (the O(n^3) term); matVec() is the dense product used in back-transform,
// re-orthogonalization, mass-normalization, and residual checks. The rest of
// the pipeline is sequential by construction -- see the file header.
//
// All three paths sum the same terms in a DIFFERENT ORDER, so they are not
// bit-identical to each other. They agree to within FP64 reduction rounding,
// which is orders of magnitude inside the harness's 1e-8 residual bound, but
// do not expect exact equality of the last bits across devices.
// ---------------------------------------------------------------------------

// NDK-lag guards. The intrinsic headers and the __arm_streaming / __arm_new
// keywords landed in clang 16 (SVE) and clang 18 (SME); on an older NDK the
// corresponding path compiles out entirely and the dispatcher simply cannot
// select it. NDK r30 ships clang 21, so both are live there.
#if defined(__aarch64__) && defined(__has_include)
#  if __has_include(<arm_sve.h>) && (__clang_major__ >= 16)
#    define MPMR_HAVE_SVE_PATH 1
#  endif
#  if __has_include(<arm_sme.h>) && (__clang_major__ >= 18)
#    define MPMR_HAVE_SME_PATH 1
#  endif
#endif
// #elifndef (C++23): "not defined by the block above" is the actual condition
// being expressed, and saying so directly removes the free-standing #ifndef
// that could drift away from the #if it belongs to.
#ifdef MPMR_HAVE_SVE_PATH
#elifndef MPMR_HAVE_SVE_PATH
#  define MPMR_HAVE_SVE_PATH 0
#endif
#ifdef MPMR_HAVE_SME_PATH
#elifndef MPMR_HAVE_SME_PATH
#  define MPMR_HAVE_SME_PATH 0
#endif

#if MPMR_HAVE_SVE_PATH

#include <arm_sve.h>

#endif
#if MPMR_HAVE_SME_PATH

#include <arm_sme.h>

#endif

// armv9-a is the right baseline to raise to here: every Android part that
// reports SVE or SME is Cortex-A510/A710-class or newer, i.e. armv9-a. On a
// hypothetical armv8.2+SVE core (A64FX and friends -- not an Android target)
// this would over-promise, and the arch would need dropping to armv8-a+sve.
#define MPMR_SVE_TARGET __attribute__((target("arch=armv9-a+sve")))
#define MPMR_SME_TARGET __attribute__((target("arch=armv9-a+sme+sme-f64f64")))

// --- Path 3: NEON baseline ------------------------------------------------
// Unchanged from the original hand-vectorized version: 2-wide accumulator,
// horizontal add, manual scalar cleanup for the odd tail element.

static void matVecNeon(const Mat &A, const Vec &x, Vec &y) {
    int n = isize(A);
    y.resize(n);
    for (int i = 0; i < n; i++) {
        const double *row = A[i].data();
        const double *xp = x.data();
        float64x2_t acc = vdupq_n_f64(0.0);
        int j = 0;
        for (; j + 1 < n; j += 2) {
            float64x2_t a = vld1q_f64(row + j);
            float64x2_t xv = vld1q_f64(xp + j);
            acc = vfmaq_f64(acc, a, xv);
        }
        double sum = vgetq_lane_f64(acc, 0) + vgetq_lane_f64(acc, 1);
        for (; j < n; j++) sum += row[j] * x[j];
        y[i] = sum;
    }
}

[[nodiscard]] static double dotNeon(CSpan av_, CSpan bv_) {
    const int n = isize(av_);
    const double *a = av_.data();
    const double *b = bv_.data();
    float64x2_t acc = vdupq_n_f64(0.0);
    int j = 0;
    for (; j + 1 < n; j += 2) {
        float64x2_t av = vld1q_f64(a + j);
        float64x2_t bv = vld1q_f64(b + j);
        acc = vfmaq_f64(acc, av, bv);
    }
    double sum = vgetq_lane_f64(acc, 0) + vgetq_lane_f64(acc, 1);
    for (; j < n; j++) sum += a[j] * b[j];
    return sum;
}

// ---------------------------------------------------------------------------
// axpyRow: y[i] += a*x[i], for a scalar times a row-vector accumulated into
// another row-vector. Added alongside dotNeon/dotSve/dotSme rather than as
// a one-off scalar loop at each call site: three of the blocked phases
// below (congruence's TRSM correction, the batched back-transformation's
// GEMM, and its batched triangular solve) reduce to exactly this shape --
// scalar-times-row, accumulate -- once written row-oriented, and a single
// shared, dispatched primitive is one thing to get right instead of four.
//
// No SME variant: AXPY is BLAS-1, the same arithmetic-intensity argument
// that makes FMOPA lose to NEON on a plain dot product applies identically
// here -- there is no outer-product structure to exploit, so an SME-
// capable device just uses the SVE path below, the same as dotSve. This is
// the universal fallback, defined unconditionally like dotNeon itself --
// not inside any ISA-availability guard.
// ---------------------------------------------------------------------------
// __restrict on y but NOT on x: every call site accumulates a scratch or
// result row into a DIFFERENT object than it reads from (checked: all seven).
// dotProduct gets no __restrict at all for the opposite reason -- dotProduct(v, v)
// and dotProduct(x, x) are real call sites, so its two operands genuinely alias.
static void axpyRowNeon(DSpan yv_, double a, CSpan xv_) {
    const int n = isize(yv_);
    double *__restrict y = yv_.data();
    const double *x = xv_.data();
    float64x2_t av = vdupq_n_f64(a);
    int j = 0;
    for (; j + 1 < n; j += 2) {
        float64x2_t yv = vld1q_f64(y + j);
        float64x2_t xv = vld1q_f64(x + j);
        yv = vfmaq_f64(yv, av, xv);
        vst1q_f64(y + j, yv);
    }
    for (; j < n; j++) y[j] += a * x[j];
}

// ---------------------------------------------------------------------------
// panelOuterProduct: C[i][j] (+)= sum_k Apanel[i][k] * Bpanel[j][k].
//
// This is the BLAS-3 primitive the file was missing. dotProduct and matVec
// are BLAS-1/2: calling either in a loop from inside a SYRK/SYR2K/GEMM does
// NOT inherit the aggregate operation's arithmetic intensity, because each
// call re-reads its operands and produces a single scalar. That matters most
// for the SME path, where the existing dotSme computes one dot product per
// FMOPA sequence and then discards every off-diagonal entry of ZA -- VL*VL
// multiplies for VL useful results, which is precisely why an isolated dot
// product is a bad FMOPA target. The kernel below keeps the SAME FMOPA
// instruction but reads out the FULL MxN tile, so every product the outer
// product computes is a product the caller actually asked for.
//
// Both operands are row-major panels with the REDUCTION index contiguous:
// Apanel[i] and Bpanel[j] are each K contiguous doubles. In BLAS terms this
// is C := A * B^T (+ C), which is the orientation all three call sites below
// already have their data in -- no transpose is introduced anywhere. Cout is
// an array of M row pointers, so callers can stage into a strip buffer or
// write straight into a matrix, whichever they need.
//
// `accumulate` distinguishes C += A*B^T from C = A*B^T. The SYR2K call site
// needs both: one call to lay down V*W^T, a second to add W*V^T on top.
// ---------------------------------------------------------------------------

// Degenerate shapes, handled identically by every path so the self-test can
// hold them all to one contract. An empty reduction (K <= 0) makes the
// product the zero matrix -- which still has to be WRITTEN when the caller
// asked for assignment rather than accumulation.
static inline bool panelOuterTrivial(int M, int N, int K, RowsOut C, bool accumulate) {
    if (M <= 0 || N <= 0) return true;
    if (K > 0) return false;
    if (!accumulate)
        for (int i = 0; i < M; i++)
            for (int j = 0; j < N; j++) C[i][j] = 0.0;
    return true;
}

// Portable triple-nested-loop reference. Not dispatched and not on any hot
// path: this exists to be the thing every vectorized path is checked against,
// and it is deliberately the most boring possible implementation so that
// "the reference is itself wrong" is not a plausible failure mode. It also
// fixes the summation order (strictly ascending k) that the tolerance in
// panelKernelSelfTest() is reasoned about relative to.
static void panelOuterProductScalar(RowsIn Apanel, RowsIn Bpanel,
                                    int K, RowsOut Cout, bool accumulate) {
    const int M = isize(Apanel), N = isize(Bpanel);
    if (panelOuterTrivial(M, N, K, Cout, accumulate)) return;
    for (int i = 0; i < M; i++) {
        const double *arow = Apanel[i];
        double *__restrict crow = Cout[i];
        for (int j = 0; j < N; j++) {
            const double *brow = Bpanel[j];
            double sum = 0.0;
            for (int k = 0; k < K; k++) sum += arow[k] * brow[k];
            crow[j] = accumulate ? crow[j] + sum : sum;
        }
    }
}

// NEON tier. Genuinely just nested dotNeon -- NEON has no matrix engine and
// no scalable predication, so there is no structure here to exploit beyond
// what dotNeon already does, and pretending otherwise would add risk for no
// gain. Kept as its own function anyway so all three tiers go through the
// same dispatch and the same self-test.
static void panelOuterNeon(RowsIn Apanel, RowsIn Bpanel,
                           int K, RowsOut Cout, bool accumulate) {
    const int M = isize(Apanel), N = isize(Bpanel);
    if (panelOuterTrivial(M, N, K, Cout, accumulate)) return;
    for (int i = 0; i < M; i++) {
        const double *arow = Apanel[i];
        double *__restrict crow = Cout[i];
        for (int j = 0; j < N; j++) {
            double sum = dotNeon(CSpan(arow, K), CSpan(Bpanel[j], K));
            crow[j] = accumulate ? crow[j] + sum : sum;
        }
    }
}

// --- Path 2: SVE ----------------------------------------------------------
// svwhilelt_b64 builds the active-lane mask from the loop counter against n,
// so the final partial vector is handled by the predicate and there is no
// scalar cleanup loop at all. svmla_f64_m (merging, not _x) is required:
// inactive lanes of the accumulator must survive the iteration, and _x
// leaves them undefined.

#if MPMR_HAVE_SVE_PATH

MPMR_SVE_TARGET
static double dotSve(CSpan av_, CSpan bv_) {
    const double *a = av_.data();
    const double *b = bv_.data();
    const uint64_t N = av_.size();
    const uint64_t vl = svcntd();
    svfloat64_t acc = svdup_f64(0.0);
    for (uint64_t i = 0; i < N; i += vl) {
        svbool_t pg = svwhilelt_b64(i, N);
        acc = svmla_f64_m(pg, acc, svld1_f64(pg, a + i), svld1_f64(pg, b + i));
    }
    return svaddv_f64(svptrue_b64(), acc);
}

// axpyRow's SVE variant -- see the note above axpyRowNeon for why there is
// no SME variant at all. This sits inside the same MPMR_HAVE_SVE_PATH guard
// as dotSve above, so no separate guard is needed here.
MPMR_SVE_TARGET
static void axpyRowSve(DSpan yv_, double a, CSpan xv_) {
    double *__restrict y = yv_.data();
    const double *x = xv_.data();
    const uint64_t N = yv_.size();
    const uint64_t vl = svcntd();
    svfloat64_t av = svdup_f64(a);
    for (uint64_t i = 0; i < N; i += vl) {
        svbool_t pg = svwhilelt_b64(i, N);
        svfloat64_t yv = svld1_f64(pg, y + i);
        svfloat64_t xv = svld1_f64(pg, x + i);
        yv = svmla_f64_m(pg, yv, av, xv);
        svst1_f64(pg, y + i, yv);
    }
}

// SVE tier. Unlike the NEON one this is not just nested dotSve: the j loop
// is unrolled by two so that each K-chunk of Apanel[i] is loaded ONCE and
// used against two different Bpanel rows. That halves the A-side load traffic
// and is the only reuse available without a matrix engine. The odd-N tail
// falls back to dotSve, whose reduction order over K is identical to the
// paired path's, so a result does not depend on N's parity.
MPMR_SVE_TARGET
static void panelOuterSve(RowsIn Apanel, RowsIn Bpanel,
                          int K, RowsOut Cout, bool accumulate) {
    const int M = isize(Apanel), N = isize(Bpanel);
    if (panelOuterTrivial(M, N, K, Cout, accumulate)) return;
    const uint64_t KN = (uint64_t) K;
    const uint64_t vl = svcntd();
    const svbool_t all = svptrue_b64();
    for (int i = 0; i < M; i++) {
        const double *arow = Apanel[i];
        double *__restrict crow = Cout[i];
        int j = 0;
        for (; j + 1 < N; j += 2) {
            const double *b0 = Bpanel[j];
            const double *b1 = Bpanel[j + 1];
            svfloat64_t acc0 = svdup_f64(0.0), acc1 = svdup_f64(0.0);
            for (uint64_t t = 0; t < KN; t += vl) {
                svbool_t pg = svwhilelt_b64(t, KN);
                svfloat64_t av = svld1_f64(pg, arow + t);
                acc0 = svmla_f64_m(pg, acc0, av, svld1_f64(pg, b0 + t));
                acc1 = svmla_f64_m(pg, acc1, av, svld1_f64(pg, b1 + t));
            }
            double s0 = svaddv_f64(all, acc0);
            double s1 = svaddv_f64(all, acc1);
            crow[j] = accumulate ? crow[j] + s0 : s0;
            crow[j + 1] = accumulate ? crow[j + 1] + s1 : s1;
        }
        for (; j < N; j++) {
            double s = dotSve(CSpan(arow, K), CSpan(Bpanel[j], K));
            crow[j] = accumulate ? crow[j] + s : s;
        }
    }
}

MPMR_SVE_TARGET
static void matVecSve(const Mat &A, const Vec &x, Vec &y) {
    const int n = isize(A);
    y.resize(n);
    const double *xp = x.data();
    for (int i = 0; i < n; i++) y[i] = dotSve(CSpan(A[i]).first(n), CSpan(xp, n));
}

#endif  // MPMR_HAVE_SVE_PATH

// --- Path 1: SME ----------------------------------------------------------
// FMOPA computes a rank-1 update, ZA[p][q] += zn[p] * zm[q]. Feeding it the
// SAME slice of both operands makes the diagonal ZA[p][p] accumulate
// a[i+p]*b[i+p] across chunks, so trace(ZA0.D) is the dot product.
//
// Be clear-eyed about what this costs: an outer product does VL*VL multiplies
// to produce VL useful ones, and extracting the trace is VL reads. FMOPA is a
// matrix-matrix instruction and both kernels here are BLAS-1/2 with an
// arithmetic intensity of ~1, so there is no arrangement of them that keeps
// the ZA array busy. This path is correct and it is what was asked for, but
// at this model's n=24 it will lose to NEON -- see the note in CLAUDE.md.
//
// Structurally the important part is that streaming mode is entered ONCE per
// dispatched call, not once per row: smstart/smstop is expensive and it
// invalidates the SVE register file, so a per-row transition would dominate
// everything else. dot/matVec below are __arm_locally_streaming __arm_new("za")
// entry points with ordinary (non-streaming) types, which is what lets them
// sit in a plain function-pointer table; the FMOPA kernels they call are
// __arm_streaming __arm_inout("za") and run entirely inside that one region.

#if MPMR_HAVE_SME_PATH

MPMR_SME_TARGET
static double dotSmeStreaming(const double *a, const double *b, int n)

__arm_streaming __arm_inout("za") {
    const uint64_t N = (n <= 0) ? 0 : (uint64_t) n;
    // Inside a streaming function svcntd() reports the STREAMING vector
    // length (SVL/64), which need not equal the non-streaming VL.
    const uint64_t vl = svcntd();
    const svbool_t all = svptrue_b64();

    svzero_za();
    for (uint64_t i = 0; i < N; i += vl) {
        svbool_t pg = svwhilelt_b64(i, N);
        svmopa_za64_m(0, pg, pg, svld1_f64(pg, a + i), svld1_f64(pg, b + i));
    }

    // Sum the diagonal. Rows beyond N-1 were never written when N < vl, so
    // stopping at min(N, vl) skips guaranteed zeros rather than changing the
    // result. Lane p is isolated with a two-whilelt difference and reduced
    // with svaddv, which keeps the whole extraction in registers -- storing
    // each slice to memory would move VL*VL doubles to read VL of them.
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

MPMR_SME_TARGET
static void matVecSmeStreaming(const double *const *rows, const double *x,
                               double *y, int n)

__arm_streaming __arm_inout("za") {
    for (int i = 0; i < n; i++) y[i] = dotSmeStreaming(rows[i], x, n);
}

MPMR_SME_TARGET
__arm_locally_streaming __arm_new("za")

static double dotSme(CSpan a, CSpan b) {
    // Spans stop here. Everything below runs inside the streaming region,
    // where the design rule is that no non-streaming function gets called --
    // and std::span's accessors are ordinary functions.
    return dotSmeStreaming(a.data(), b.data(), isize(a));
}

MPMR_SME_TARGET
__arm_locally_streaming __arm_new("za")

static void matVecSmeRows(const double *const *rows, const double *x,
                          double *y, int n) {
    matVecSmeStreaming(rows, x, y, n);
}

// ---------------------------------------------------------------------------
// SME tier -- the one this whole primitive exists for.
//
// FMOPA computes ZA[p][q] += zn[p] * zm[q] over a full VLxVL tile. dotSme
// feeds it the same vector twice and keeps only trace(ZA), throwing away
// VL*(VL-1) of the VL*VL products it just paid for. Here zn is a COLUMN of
// the A panel (fixed k, varying i) and zm a COLUMN of the B panel (fixed k,
// varying j), so one FMOPA per k accumulates a genuine VLxVL block of
// C = A*B^T, and every lane of the readout is a value the caller wants.
//
// The reduction index is therefore the LOOP index, not the lane index. That
// is the structural inversion relative to dotSmeStreaming, and it is why
// there is no svwhilelt partial-chunk handling over K here: K never maps onto
// vector lanes at all, so "K not a multiple of the vector length" is not a
// case this kernel can have. Partial predication is over M and N instead
// (pi/pj below), which is where the ragged edges actually live.
//
// Reading a column of a row-major panel is a gather, so both panels are
// PACKED into k-major staging buffers first, in ordinary non-streaming code.
// That is the standard GEMM packing step: O(K*(M+N)) to enable O(M*N*K) of
// compute, and it keeps every std::vector access outside the streaming region
// (calling a non-streaming function from streaming context would bracket it
// in smstop/smstart -- the per-row transition cost the single-region design
// exists to avoid). The two staging buffers are transient -- K*(M+N) doubles,
// freed on return -- but they ARE proportional to the operands, so a caller
// handing this a whole n x n matrix (matMulBatchedT does) briefly holds two
// extra operand-sized allocations. The two triangular call sites avoid that
// by striping; see PanelScratch.
//
// Capacity: FEAT_SME_F64F64 gives eight FP64 ZA tiles (ZA0.D..ZA7.D), each
// SVL_d x SVL_d where SVL_d = svcntd() IN STREAMING MODE -- the streaming
// vector length, which need not equal the non-streaming one. This kernel
// uses one tile and loops over as many MxN blocks as it takes, rather than
// using all eight, because an M or N that outruns a single tile is already
// handled by that loop and multi-tile blocking would buy throughput at the
// cost of a second, independently-wrong-able index scheme. svzero_za() zeroes
// all eight; at K FMOPAs per block that cost is noise, and it cannot leave a
// stale tile behind the way a hand-computed zero-mask could.
// ---------------------------------------------------------------------------
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
                svfloat64_t zn = svld1_f64(pi, Apack + (size_t) k * (size_t) M + i0);
                svfloat64_t zm = svld1_f64(pj, Bpack + (size_t) k * (size_t) N + j0);
                svmopa_za64_m(0, pi, pj, zn, zm);
            }

            // Horizontal slice p of ZA0.D is exactly output row i0+p over
            // columns j0..j0+nb-1. Lanes q >= nb were never written (pj was
            // inactive there) and rows p >= mb are never read, so the
            // predicated read/store below touches only real results.
            for (int p = 0; p < mb; p++) {
                svfloat64_t slice = svread_hor_za64_m(svdup_f64(0.0), pj, 0, (uint32_t) p);
                double *__restrict crow = Cout[i0 + p] + j0;
                if (accumulate) slice = svadd_f64_x(pj, slice, svld1_f64(pj, crow));
                svst1_f64(pj, crow, slice);
            }
        }
    }
}

MPMR_SME_TARGET
__arm_locally_streaming __arm_new("za")

static void panelOuterSmeKernel(const double *Apack, int M, const double *Bpack,
                                int N, int K, double *const *Cout, bool accumulate) {
    panelOuterSmeStreaming(Apack, M, Bpack, N, K, Cout, accumulate);
}

static void panelOuterSme(RowsIn Apanel, RowsIn Bpanel,
                          int K, RowsOut Cout, bool accumulate) {
    const int M = isize(Apanel), N = isize(Bpanel);
    if (panelOuterTrivial(M, N, K, Cout, accumulate)) return;
    // Pack to k-major: Apack[k*M + i] = Apanel[i][k]. Source-contiguous
    // traversal (the panel was just written and is the hot one), strided
    // store into fresh memory.
    PackVec Apack((size_t) K * (size_t) M);
    PackVec Bpack((size_t) K * (size_t) N);
    for (int i = 0; i < M; i++) {
        const double *arow = Apanel[i];
        for (int k = 0; k < K; k++) Apack[(size_t) k * (size_t) M + i] = arow[k];
    }
    for (int j = 0; j < N; j++) {
        const double *brow = Bpanel[j];
        for (int k = 0; k < K; k++) Bpack[(size_t) k * (size_t) N + j] = brow[k];
    }
    panelOuterSmeKernel(std::assume_aligned<kPackAlign>(Apack.data()), M,
                        std::assume_aligned<kPackAlign>(Bpack.data()), N,
                        K, Cout.data(), accumulate);
}

static void matVecSme(const Mat &A, const Vec &x, Vec &y) {
    const int n = isize(A);
    y.resize(n);
    if (n <= 0) return;
    // Gather the row pointers OUTSIDE the streaming region. std::vector's
    // accessors are ordinary non-streaming functions; calling them from a
    // streaming context forces the compiler to bracket each one in
    // smstop/smstart, which is exactly the per-row transition cost the
    // single-region design above exists to avoid.
    std::vector<const double *> rows((size_t) n);
    for (int i = 0; i < n; i++) rows[i] = A[i].data();
    matVecSmeRows(rows.data(), x.data(), y.data(), n);
}

#endif  // MPMR_HAVE_SME_PATH

// --- Runtime dispatch -----------------------------------------------------
// Resolved once, at library load, into a const table of function pointers.
// This is dynamic initialization of a namespace-scope object, so it runs on
// dlopen (System.loadLibrary) before any JNI entry point can be reached, and
// there is no per-call guard variable to test in the O(n^3) inner loop.

typedef double (*DotFn)(CSpan, CSpan);

// MatVecFn is deliberately NOT span-ified: it already takes Mat/Vec by
// reference, so there is no separately-passed length to get wrong. Adding a
// span layer here would be churn, not safety.
typedef void   (*MatVecFn)(const Mat &, const Vec &, Vec &);

typedef void   (*AxpyFn)(DSpan, double, CSpan);

typedef void   (*PanelFn)(RowsIn, RowsIn, int, RowsOut, bool);

// Which tier the dispatcher resolved to. The identity is the enumerator; the
// string is only for humans. Previously `const char* isa` WAS the identity,
// which meant any code wanting to branch on the active path had to compare
// string literals.
enum class IsaPath : unsigned char {
    Neon = 0,
    Sve = 1,
    Sme = 2,
};

static constexpr const char *isaName(IsaPath p) {
    switch (p) {
        case IsaPath::Neon:
            return "NEON (float64x2_t FMA)";
        case IsaPath::Sve:
            return "SVE (scalable fp64 FMLA)";
        case IsaPath::Sme:
            return "SME/FMOPA (fp64 outer product)";
    }
    // Not std::unreachable(): `p` arrives from a function-pointer table that
    // is written once at load time, but an enum class can legally hold any
    // value of its underlying type, so this IS reachable through a bad cast.
    // A wrong label is a cosmetic bug; UB here would not be.
    return "unknown";
}

struct HotKernels {
    IsaPath path;
    DotFn dot;
    MatVecFn matVec;
    AxpyFn axpy;
    PanelFn panel;
};

[[nodiscard]] static HotKernels selectHotKernels() {
#if defined(__aarch64__)
    const unsigned long hw = getauxval(AT_HWCAP);
    const unsigned long hw2 = getauxval(AT_HWCAP2);

#if MPMR_HAVE_SME_PATH
    // HWCAP2_SME is checked alongside HWCAP2_SME_F64F64 on purpose. The
    // F64F64 bit only means "the FP64 variant of the outer product exists";
    // the base SME bit is what says the kernel has SME context-switch support
    // and that ZA/streaming mode may be entered at all.
    //
    // axpyRow has no SME-specific variant at all (AXPY is BLAS-1 -- no
    // outer-product structure for FMOPA to exploit, same reasoning as why
    // dotSme doesn't help a plain dot product either). It is selected here
    // by checking HWCAP_SVE directly, not by assuming SME implies base SVE
    // on the same core -- that isn't a guarantee the architecture makes.
    if ((hw2 & HWCAP2_SME) && (hw2 & HWCAP2_SME_F64F64)) {
#if MPMR_HAVE_SVE_PATH
        AxpyFn axpyChoice = (hw & HWCAP_SVE) ? &axpyRowSve : &axpyRowNeon;
#else
        AxpyFn axpyChoice = &axpyRowNeon;
#endif
        return HotKernels{.path = IsaPath::Sme, .dot = &dotSme, .matVec = &matVecSme,
                .axpy = axpyChoice, .panel = &panelOuterSme};
    }
#endif
#if MPMR_HAVE_SVE_PATH
    if (hw & HWCAP_SVE) {
        return HotKernels{.path = IsaPath::Sve, .dot = &dotSve, .matVec = &matVecSve,
                .axpy = &axpyRowSve, .panel = &panelOuterSve};
    }
#endif
    (void) hw;
    (void) hw2;
#endif  // __aarch64__
    return HotKernels{.path = IsaPath::Neon, .dot = &dotNeon, .matVec = &matVecNeon,
            .axpy = &axpyRowNeon, .panel = &panelOuterNeon};
}

static const HotKernels g_hotKernels = selectHotKernels();

// a.size() is the reduction length; b must be at least that long.
static inline double dotProduct(CSpan a, CSpan b) {
    return g_hotKernels.dot(a, b);
}

static inline void matVec(const Mat &A, const Vec &x, Vec &y) {
    g_hotKernels.matVec(A, x, y);
}

// y[0..n) += a * x[0..n), dispatched the same way dotProduct/matVec are.
static inline void axpyRow(DSpan y, double a, CSpan x) {
    g_hotKernels.axpy(y, a, x);
}

// C := A * B^T (+ C), dispatched the same way. See panelOuterProduct's header
// comment above for the operand layout.
static inline void panelOuterProduct(RowsIn Apanel, RowsIn Bpanel,
                                     int K, RowsOut Cout, bool accumulate) {
    g_hotKernels.panel(Apanel, Bpanel, K, Cout, accumulate);
}

// Human-readable summary of both the selection and what the CPU actually
// advertised, so a device run can be told apart from a host run in logcat
// and in the Phase 1 report.
[[nodiscard]] static std::string hotKernelSummary() {
    std::ostringstream s;
    // static_cast<int> is load-bearing: IsaPath's underlying type is
    // unsigned char, and operator<< prints that as a CHARACTER, so this
    // emitted an unprintable 0x02 for the SME tier instead of "2".
    s << isaName(g_hotKernels.path)
      << " [tier " << static_cast<int>(std::to_underlying(g_hotKernels.path)) << "]";
#if defined(__aarch64__)
    const unsigned long hw = getauxval(AT_HWCAP);
    const unsigned long hw2 = getauxval(AT_HWCAP2);
    s << "  [hwcap: sve=" << ((hw & HWCAP_SVE) ? 1 : 0)
      << " sve2=" << ((hw2 & HWCAP2_SVE2) ? 1 : 0)
      << " sme=" << ((hw2 & HWCAP2_SME) ? 1 : 0)
      << " sme2=" << ((hw2 & HWCAP2_SME2) ? 1 : 0)
      << " sme_f64f64=" << ((hw2 & HWCAP2_SME_F64F64) ? 1 : 0)
      << "; built with sve_path=" << MPMR_HAVE_SVE_PATH
      << " sme_path=" << MPMR_HAVE_SME_PATH << "]";
#endif
    return s.str();
}

// ---------------------------------------------------------------------------
// Dense Cholesky: M = L L^T, M symmetric positive definite.
//
// Two implementations: the original unblocked (BLAS-2, one row at a time)
// and a panel-blocked (BLAS-3-shaped) version. Kept side by side rather
// than replacing the unblocked one outright -- the unblocked version is
// also what the blocked version's own diagonal-panel step reuses, and
// having both lets every other blocked phase below be cross-validated
// directly against a known-correct reference on the same input, not just
// against a final eigenvalue.
//
// Block size is a compile-time default, not hardcoded into the algorithm
// itself -- every blocked function below takes NB as a parameter so this
// stays generic across problem sizes and callers, not tuned to one app's
// n=24 case. Any n works: a panel wider than what's left just shrinks to
// fit, and n < NB degenerates to exactly the unblocked algorithm.
// ---------------------------------------------------------------------------
static constexpr int MPMR_DEFAULT_NB = 32;

[[nodiscard]] static Mat cholesky(const Mat &M) {
    int n = isize(M);
    Mat L = matZeros(n);
    for (int i = 0; i < n; i++) {
        for (int j = 0; j <= i; j++) {
            double sum = M[i][j];
            if (j > 0) sum -= dotProduct(CSpan(L[i]).first(j), CSpan(L[j]).first(j));
            L[i][j] = (i == j) ? std::sqrt(sum) : sum / L[j][j];
        }
    }
    return L;
}

// Unblocked Cholesky restricted to the [p, p+nb) diagonal panel, operating
// in place on an L buffer that already holds M's values there (nothing
// outside the panel is touched or read). This is the small, inherently
// ---------------------------------------------------------------------------
// Staging buffer for the two SYMMETRIC trailing updates below (Cholesky's
// SYRK, the blocked Householder's SYR2K). Neither can hand the panel kernel
// its target matrix directly: the kernel produces a dense rectangular block,
// while both targets are triangular by convention (only j <= i of L is ever
// read; A's upper half is mirrored from its lower). So the block is staged
// here and then folded in.
//
// Row-STRIPED rather than one (n-p-nb)^2 block, for two reasons that happen
// to point the same way. It bounds the staging buffer at O(strip * n) instead
// of O(n^2). And because only j <= i is ever wanted, stopping each strip's
// column range at i0+rb computes essentially just the lower triangle -- half
// the flops of the full square a naive whole-block call would do.
// ---------------------------------------------------------------------------
static constexpr int kPanelStripRows = 64;

struct PanelScratch {
    std::vector<double> buf;
    std::vector<double *> crow;
    std::vector<const double *> a, b;

    // Row pointers are rebuilt after every resize, never cached across one.
    RowsOut stage(int m, int n) {
        buf.resize((size_t) m * (size_t) n);
        crow.resize((size_t) m);
        for (int i = 0; i < m; i++) crow[i] = buf.data() + (size_t) i * (size_t) n;
        return RowsOut(crow);
    }
};

// sequential O(nb^3) kernel every blocked Cholesky still bottoms out to.
static void choleskyPanelUnblocked(Mat &L, int p, int nb) {
    for (int ii = 0; ii < nb; ii++) {
        int i = p + ii;
        for (int jj = 0; jj <= ii; jj++) {
            int j = p + jj;
            double sum = L[i][j];
            if (jj > 0) sum -= dotProduct(CSpan(L[i]).subspan(p, jj), CSpan(L[j]).subspan(p, jj));
            L[i][j] = (i == j) ? std::sqrt(sum) : sum / L[j][j];
        }
    }
}

[[nodiscard]] static Mat choleskyBlocked(const Mat &M, int NB = MPMR_DEFAULT_NB) {
    int n = isize(M);
    PanelScratch scratch;
    Mat L = matZeros(n);
    for (int i = 0; i < n; i++)
        for (int j = 0; j <= i; j++)
            L[i][j] = M[i][j];

    for (int p = 0; p < n; p += NB) {
        int nb = std::min(NB, n - p);

        // 1. Factor the nb x nb diagonal panel.
        choleskyPanelUnblocked(L, p, nb);

        // 2. Panel solve (row-oriented TRSM): each row below the panel is
        //    independent given L11, solved by the same forward-substitution
        //    recurrence as the unblocked algorithm, just scoped to nb
        //    columns instead of the whole row.
        for (int i = p + nb; i < n; i++) {
            for (int jj = 0; jj < nb; jj++) {
                int j = p + jj;
                double sum = L[i][j];
                if (jj > 0)
                    sum -= dotProduct(CSpan(L[i]).subspan(p, jj), CSpan(L[j]).subspan(p, jj));
                L[i][j] = sum / L[j][j];
            }
        }

        // 3. Trailing update (SYRK): A22 -= L21 * L21^T, lower triangle
        //    only, matching this file's convention that only j<=i of L is
        //    ever read elsewhere. This is the genuinely BLAS-3-shaped part
        //    -- an (n-p-nb) x (n-p-nb) symmetric rank-nb update.
        //
        //    It used to be a per-(i,j) dotProduct loop. That was correct, but
        //    it handed a BLAS-3 operation to a BLAS-1 kernel one output
        //    element at a time, which the SME path in particular cannot
        //    exploit at all -- see panelOuterProduct's header. One call per
        //    strip now does the whole block.
        //
        //    Read/write disjointness: the kernel reads columns p..p+nb-1 of
        //    the panel and writes only columns >= p+nb, so no strip can see a
        //    value another strip in the same sweep has already updated.
        const int mt = n - p - nb;
        if (mt > 0) {
            const int base = p + nb;
            for (int i0 = 0; i0 < mt; i0 += kPanelStripRows) {
                const int rb = std::min(kPanelStripRows, mt - i0);
                const int cols = i0 + rb;   // lower triangle: j <= i
                scratch.a.resize((size_t) rb);
                scratch.b.resize((size_t) cols);
                for (int r = 0; r < rb; r++) scratch.a[r] = L[base + i0 + r].data() + p;
                for (int c = 0; c < cols; c++) scratch.b[c] = L[base + c].data() + p;
                RowsOut C = scratch.stage(rb, cols);
                panelOuterProduct(RowsIn(scratch.a), RowsIn(scratch.b), nb, C, false);
                for (int r = 0; r < rb; r++) {
                    double *Lrow = L[base + i0 + r].data() + base;
                    const int lim = i0 + r;
                    const double *Crow = C[r];
                    for (int c = 0; c <= lim; c++) Lrow[c] -= Crow[c];
                }
            }
        }
    }
    return L;
}


[[nodiscard]] static Vec forwardSub(const Mat &L, CSpan b) {
    int n = isize(L);
    Vec x(n);
    for (int i = 0; i < n; i++) {
        double sum = b[i];
        if (i > 0) sum -= dotProduct(CSpan(L[i]).first(i), CSpan(x).first(i));
        x[i] = sum / L[i][i];
    }
    return x;
}

// ---------------------------------------------------------------------------
// Batched back-transformation: W = Q*Y then Phi = L^-T * W, for ALL modes at
// once instead of one matVec + one backSubLT per mode. Two changes make this
// possible without ever reading a column:
//
//  - matMulBatchedT computes Q*Y as one panel-batched GEMM call. Y is held
//    TRANSPOSED (one row per mode) by its producer, which is both the
//    natural way inverseIteration's output lands and the operand layout
//    panelOuterProduct wants -- so this reads rows of both operands and
//    introduces no transpose. It replaces a row-AXPY loop that, like the two
//    trailing updates above, was a BLAS-3 operation driven one BLAS-1 call
//    at a time.
//
//  - backSolveLTBatched solves L^T*X=B by flipping the usual column-reading
//    recurrence around: instead of "for each row i, sum L[k][i] for k>i"
//    (a column read), it processes rows top-down from n-1, and as soon as
//    row i is finalized, immediately propagates -L[i][j]*X[i] into every
//    not-yet-finalized row j<i by sweeping ALONG row i of L (contiguous).
//    Same final result, only ever reads rows.
//
// Verified against the per-mode reference (matVec + backSubLT called once
// per column) on random matrices from n=2 to n=64 and 1 to 17 simultaneous
// right-hand sides: agreed to floating-point noise throughout (up to ~1e-9
// at n=64, consistent with random, not specially-conditioned, test matrices
// growing more rounding-sensitive with size -- not a sign of divergence).
// ---------------------------------------------------------------------------
// W = Q * Yt^T, i.e. W[i][j] = sum_k Q[i][k] * Yt[j][k]. Yt has one ROW per
// mode. Dimensions come from Q.size()/Yt.size(), never from Y[0].size(), so a
// zero-mode call is a well-defined empty result instead of a deref of row 0
// of an empty matrix.
[[nodiscard]] static Mat matMulBatchedT(const Mat &Q, const Mat &Yt) {
    const int n = isize(Q);
    const int nm = isize(Yt);
    Mat W(n, nm, 0.0);
    if (n == 0 || nm == 0) return W;
    std::vector<const double *> ap((size_t) n), bp((size_t) nm);
    std::vector<double *> cp((size_t) n);
    for (int i = 0; i < n; i++) {
        ap[i] = Q[i].data();
        cp[i] = W[i].data();
    }
    for (int j = 0; j < nm; j++) bp[j] = Yt[j].data();
    panelOuterProduct(RowsIn(ap), RowsIn(bp), n, RowsOut(cp), false);
    return W;
}

[[nodiscard]] static Mat backSolveLTBatched(const Mat &L, Mat B) {
    int n = isize(L);
    int nm = (int) B[0].size();
    Mat X(n, nm);
    for (int i = n - 1; i >= 0; i--) {
        for (int c = 0; c < nm; c++) X[i][c] = B[i][c] / L[i][i];
        for (int j = 0; j < i; j++) {
            double lij = L[i][j];
            if (lij == 0.0) continue;
            axpyRow(B[j], -lij, X[i]);
        }
    }
    return X;
}

// ---------------------------------------------------------------------------
// Congruence transform: Atilde = L^-1 K L^-T, via two triangular solves.
//
// Both solves are of the shape "L * X = B" for full n x n B -- a matrix
// TRSM, not just a single right-hand side. Panel-blocked (right-looking):
// once a row-panel of X is solved, its effect on all remaining unsolved
// rows is applied as one row-oriented linear combination (a GEMM, not a
// per-column loop) before moving to the next panel. Every array access in
// blockedForwardSolveMatrix stays row-wise -- the update for row p+i is a
// weighted sum of ALREADY-COMPUTED rows of X, never a column of anything.
// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// Congruence transform: Atilde = L^-1 K L^-T, via two triangular solves.
//
// Both solves are of the shape "L * X = B" for full n x n B -- a matrix
// TRSM, not just a single right-hand side. Panel-blocked (right-looking):
// once a row-panel of X is solved, its effect on all remaining unsolved
// rows is applied as one row-oriented linear combination (a GEMM, not a
// per-column loop) before moving to the next panel. Every array access in
// blockedForwardSolveMatrix stays row-wise -- the update for row p+i is a
// weighted sum of ALREADY-COMPUTED rows of X, never a column of anything.
// ---------------------------------------------------------------------------

// Solves L * X = B for X, where L is n x n lower-triangular and B is a full
// n x n right-hand side (B's rows need not relate to L's structure at all --
// this is a generic blocked matrix-TRSM, reused for both solves below).
[[nodiscard]] static Mat
blockedForwardSolveMatrix(const Mat &L, const Mat &B, int NB = MPMR_DEFAULT_NB) {
    int n = isize(L);
    Mat X = matZeros(n);
    for (int p = 0; p < n; p += NB) {
        int nb = std::min(NB, n - p);
        for (int ii = 0; ii < nb; ii++) {
            int i = p + ii;
            // Row-oriented GEMM-shaped correction: subtract the effect of
            // every already-solved row 0..p-1 (rows solved in EARLIER
            // panels) plus the rows solved so far within THIS panel
            // (0..ii-1 of the current panel) -- a weighted sum of rows of
            // X, never a column.
            Vec rhs(B[i].begin(), B[i].end()); // copy: row i of B, contiguous already
            for (int k = 0; k < p + ii; k++) {
                double lik = L[i][k];
                if (lik == 0.0)
                    continue; // most panels: only a few nonzero L[i][k] before the diagonal block
                axpyRow(rhs, -lik, X[k]);
            }
            for (int j = 0; j < n; j++) X[i][j] = rhs[j] / L[i][i];
        }
    }
    return X;
}

[[nodiscard]] static Mat congruenceBlocked(const Mat &L, const Mat &K, int NB = MPMR_DEFAULT_NB) {
    int n = isize(K);
    // First solve: L*Y = K. K's rows are the right-hand sides directly (K
    // is symmetric, so this matches the unblocked version's K[j] use).
    Mat Y = blockedForwardSolveMatrix(L, K, NB);

    // Second solve needs L*Z = Y^T. Y^T's rows are Y's columns -- an
    // explicit O(n^2) transpose here, not blocked or vectorized, since it
    // is asymptotically dominated by the O(n^3) solves on either side of
    // it and isn't worth the complexity of avoiding.
    Mat Yt = matZeros(n);
    for (int i = 0; i < n; i++)
        for (int j = 0; j < n; j++)
            Yt[i][j] = Y[j][i];
    Mat Z = blockedForwardSolveMatrix(L, Yt, NB);

    Mat A = matZeros(n);
    for (int i = 0; i < n; i++)
        for (int j = 0; j < n; j++)
            A[i][j] = 0.5 * (Z[j][i] + Z[i][j]);
    return A;
}

[[nodiscard]] static Mat congruence(const Mat &L, const Mat &K) {
    int n = isize(K);
    Mat Y = matZeros(n);
    for (int j = 0; j < n; j++) {
        // K is symmetric, so column j of K IS row j of K. No gather needed.
        Vec col = forwardSub(L, K[j]);
        for (int i = 0; i < n; i++) Y[i][j] = col[i];
    }
    Mat Z = matZeros(n);
    for (int j = 0; j < n; j++) {
        // Y[j] IS row j of Y, i.e. column j of Y^T, already contiguous --
        // no copy loop needed, just pass it directly.
        Vec col = forwardSub(L, Y[j]);
        for (int i = 0; i < n; i++) Z[i][j] = col[i];
    }
    Mat A = matZeros(n);
    for (int i = 0; i < n; i++)
        for (int j = 0; j < n; j++)
            A[i][j] = 0.5 * (Z[j][i] + Z[i][j]);
    return A;
}

// ---------------------------------------------------------------------------
// Householder tridiagonalization: T = Q1^T Atilde Q1, accumulating Q1.
// The three O(n) inner dot-products per k are NEON-vectorized; this is the
// dominant O(n^3)-total cost in the whole pipeline for the exact-answer path.
// ---------------------------------------------------------------------------
struct TriDiag {
    Vec d, e;
    Mat Q;
};

[[nodiscard]] static TriDiag householderTridiag(Mat A) {
    int n = isize(A);
    Mat Q = matZeros(n);
    for (int i = 0; i < n; i++) Q[i][i] = 1.0;

    for (int k = 0; k < n - 2; k++) {
        int m = n - k - 1;
        Vec x(m);
        for (int i = 0; i < m; i++) x[i] = A[k + 1 + i][k];

        double normx = std::sqrt(dotProduct(x, x));
        if (normx < 1e-300) continue;

        double alpha = (x[0] >= 0) ? -normx : normx;
        Vec v = x;
        v[0] -= alpha;
        double vnorm = std::sqrt(dotProduct(v, v));
        if (vnorm < 1e-300) continue;
        for (double &vv: v) vv /= vnorm;

        // The reflector's defining property already tells us exactly what
        // column/row k become -- A[k+1][k] = alpha, everything below it
        // zero -- so write that directly instead of deriving it from a
        // matrix update. This step was missing from the first attempt at
        // this fix: the rank-2 update below only touches the (k+1..n)
        // trailing submatrix, but the ORIGINAL two-sweep code's left sweep
        // ran over ALL columns j (0..n-1), which is what actually zeroed
        // column k -- an unblocked rank-2 update on the trailing submatrix
        // alone never touches column k at all. Skipping this produced
        // eigenvalues that were superficially close (small perturbation)
        // but residuals in the hundreds instead of 1e-11 -- caught by
        // running the existing regression tests, not by re-reading the
        // algebra, which still looked correct on paper.
        A[k + 1][k] = alpha;
        A[k][k + 1] = alpha;
        for (int i = 1; i < m; i++) {
            A[k + 1 + i][k] = 0.0;
            A[k][k + 1 + i] = 0.0;
        }

        // Symmetric rank-2 update HAH = A - 2(vw^T + wv^T), where
        // w = A_sub*v - (v^T A_sub v)v, applied to the (k+1..n)x(k+1..n)
        // trailing submatrix only -- column k is handled above, and this
        // block is disjoint from it either way. Replaces the two one-sided
        // sweeps this file used to have (a left sweep needing a gathered
        // column, then a right sweep) with one row-only pass -- roughly
        // half the memory traffic, no column ever read. Q's own
        // accumulation loop below is unrelated and unchanged.
        Vec w(m);
        for (int i = 0; i < m; i++)
            w[i] = dotProduct(CSpan(A[k + 1 + i]).subspan(k + 1, m), v);
        double vAv = dotProduct(v, w);
        for (int i = 0; i < m; i++) w[i] -= vAv * v[i];
        for (int i = 0; i < m; i++) {
            const DSpan row = DSpan(A[k + 1 + i]).subspan(k + 1, m);
            axpyRow(row, -2.0 * v[i], w);
            axpyRow(row, -2.0 * w[i], v);
        }
        for (int i = 0; i < n; i++) {
            const DSpan qrow = DSpan(Q[i]).subspan(k + 1, m);
            double dot = dotProduct(qrow, v);
            axpyRow(qrow, -2.0 * dot, v);
        }
    }

    TriDiag td;
    td.d.resize(n);
    td.e.resize(n > 0 ? n - 1 : 0);
    for (int i = 0; i < n; i++) td.d[i] = A[i][i];
    for (int i = 0; i < n - 1; i++) td.e[i] = A[i][i + 1];
    td.Q = Q;
    return td;
}

// ---------------------------------------------------------------------------
// Blocked (LATRD/SYR2K-style) Householder tridiagonalization -- the same
// transform as householderTridiag() above, but deferring each panel's
// trailing-submatrix update and applying all NB reflectors' cumulative
// effect at once as a symmetric rank-2k update, matching LAPACK's
// dsytrd+dlatrd pattern. This is where blocking actually pays off: O(n/NB)
// BLAS-3-shaped updates instead of O(n) individual rank-2 (BLAS-2) ones.
//
// Verified by direct cross-validation against householderTridiag() above,
// on random symmetric matrices from n=3 to n=50 and block widths 2/3/4/8:
// d and e matched to floating-point noise (~1e-13), and reconstructing
// Q*T*Q^T (robust to any Q column-sign ambiguity, unlike comparing Q
// directly) reproduced the original input to the same precision. Two real
// bugs were caught this way before it passed: the deferred correction to
// each new reflector's w vector was missing its factor of 2, and the
// diagonal entry A[k][k] itself was never corrected for earlier
// within-panel reflectors at all -- only the column below it was. Neither
// was visible from re-reading the algebra; both showed up immediately as
// soon as a panel with more than one reflector was actually exercised.
[[nodiscard]] static TriDiag householderTridiagBlocked(Mat A, int NB = MPMR_DEFAULT_NB) {
    int n = isize(A);
    PanelScratch scratch;
    Mat Q = matZeros(n);
    for (int i = 0; i < n; i++) Q[i][i] = 1.0;

    for (int p = 0; p < n - 2; p += NB) {
        int nb = std::min(NB, n - 2 - p);
        int npRows = n - p; // V/W local row index r <-> global row (p+r)
        Mat V(npRows, nb, 0.0), W(npRows, nb, 0.0);
        std::vector<char> degenerate(nb, 0);

        for (int c = 0; c < nb; c++) {
            int k = p + c;
            int m = n - k - 1;

            // Bring column k, and the A[k][k] diagonal entry itself, up to
            // date for every earlier within-panel reflector (0..c-1) --
            // their trailing-submatrix update hasn't actually been applied
            // to A yet, only deferred into V/W, so both need an explicit
            // correction before this step can read them.
            Vec xcorr(m);
            for (int i = 0; i < m; i++) xcorr[i] = A[k + 1 + i][k];
            double diagCorr = 0.0;
            for (int cp = 0; cp < c; cp++) {
                if (degenerate[cp]) continue;
                double Vk = V[k - p][cp], Wk = W[k - p][cp];
                diagCorr += 4.0 * Vk * Wk;
                for (int i = 0; i < m; i++)
                    xcorr[i] -= 2.0 * (V[k + 1 + i - p][cp] * Wk + W[k + 1 + i - p][cp] * Vk);
            }
            A[k][k] -= diagCorr;

            double normx = std::sqrt(dotProduct(xcorr, xcorr));
            if (normx < 1e-300) {
                degenerate[c] = 1;
                continue;
            }
            double alpha = (xcorr[0] >= 0) ? -normx : normx;
            Vec v = xcorr;
            v[0] -= alpha;
            double vnorm = std::sqrt(dotProduct(v, v));
            if (vnorm < 1e-300) {
                degenerate[c] = 1;
                continue;
            }
            for (double &vv: v) vv /= vnorm;

            A[k + 1][k] = alpha;
            A[k][k + 1] = alpha;
            for (int i = 1; i < m; i++) {
                A[k + 1 + i][k] = 0.0;
                A[k][k + 1 + i] = 0.0;
            }

            // w against the STILL-STALE trailing region (correct at this
            // point only for the same earlier within-panel reflectors).
            Vec w(m);
            for (int i = 0; i < m; i++)
                w[i] = dotProduct(CSpan(A[k + 1 + i]).subspan(k + 1, m), v);
            for (int cp = 0; cp < c; cp++) {
                if (degenerate[cp]) continue;
                double wpv = 0.0, vpv = 0.0;
                for (int i = 0; i < m; i++) {
                    wpv += W[k + 1 + i - p][cp] * v[i];
                    vpv += V[k + 1 + i - p][cp] * v[i];
                }
                for (int i = 0; i < m; i++)
                    w[i] -= 2.0 * (V[k + 1 + i - p][cp] * wpv + W[k + 1 + i - p][cp] * vpv);
            }
            double vAv = dotProduct(v, w);
            for (int i = 0; i < m; i++) w[i] -= vAv * v[i];

            for (int i = 0; i < m; i++) {
                V[k + 1 + i - p][c] = v[i];
                W[k + 1 + i - p][c] = w[i];
            }
        }

        // Deferred SYR2K: apply all nb reflectors' cumulative effect to the
        // region beyond this panel in one batched update -- the genuinely
        // BLAS-3-shaped part blocking exists for.
        //
        // A[i][j] -= 2*( (V W^T)[i][j] + (W V^T)[i][j] ), which is two calls
        // to the panel kernel: the first ASSIGNS V*W^T into the staging
        // block, the second ACCUMULATES W*V^T on top of it. That is exactly
        // why panelOuterProduct carries an `accumulate` flag rather than
        // always assigning. Per (i,j) the two length-nb reductions are summed
        // in the same order as the dotProduct pair this replaces.
        const int mt = n - p - nb;
        if (mt > 0) {
            const int base = p + nb;
            for (int i0 = 0; i0 < mt; i0 += kPanelStripRows) {
                const int rb = std::min(kPanelStripRows, mt - i0);
                const int cols = i0 + rb;   // lower triangle: j <= i
                scratch.a.resize((size_t) rb);
                scratch.b.resize((size_t) cols);
                for (int r = 0; r < rb; r++) scratch.a[r] = V[base + i0 + r - p].data();
                for (int c = 0; c < cols; c++) scratch.b[c] = W[base + c - p].data();
                RowsOut C = scratch.stage(rb, cols);
                panelOuterProduct(RowsIn(scratch.a), RowsIn(scratch.b), nb, C, false);
                for (int r = 0; r < rb; r++) scratch.a[r] = W[base + i0 + r - p].data();
                for (int c = 0; c < cols; c++) scratch.b[c] = V[base + c - p].data();
                panelOuterProduct(RowsIn(scratch.a), RowsIn(scratch.b), nb, C, true);
                for (int r = 0; r < rb; r++) {
                    const int i = base + i0 + r;
                    const int lim = i0 + r;
                    const double *Crow = C[r];
                    for (int c = 0; c <= lim; c++) {
                        const int j = base + c;
                        A[i][j] -= 2.0 * Crow[c];
                        A[j][i] = A[i][j];
                    }
                }
            }
        }

        // Q accumulation, one reflector at a time -- O(n) per reflector
        // either way, not the part blocking targets.
        for (int c = 0; c < nb; c++) {
            if (degenerate[c]) continue;
            int k = p + c, m = n - k - 1;
            Vec v(m);
            for (int i = 0; i < m; i++) v[i] = V[k + 1 + i - p][c];
            for (int i = 0; i < n; i++) {
                const DSpan qrow = DSpan(Q[i]).subspan(k + 1, m);
                double dot = dotProduct(qrow, v);
                axpyRow(qrow, -2.0 * dot, v);
            }
        }
    }

    TriDiag td;
    td.d.resize(n);
    td.e.resize(n > 0 ? n - 1 : 0);
    for (int i = 0; i < n; i++) td.d[i] = A[i][i];
    for (int i = 0; i < n - 1; i++) td.e[i] = A[i][i + 1];
    td.Q = Q;
    return td;
}

// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// Phase 4 -- eigenvalues, via a faithful port of LAPACK's actual DSTERF
// (reference Fortran, the Pal-Walker-Kahan variant of the QL/QR algorithm),
// not a generic Wilkinson-shift-and-bulge-chase description. Transcribed
// line-for-line from dsterf.f and its exact dependency closure (DLAE2,
// DLANST's 'M' branch, DLAPY2, DLAMCH's 'E'/'S'/'O' codes, DLASCL, DLASRT),
// preserving control flow, the QL/QR direction selection, the deflation
// test, the scaling logic, and the GAMMA/OLDGAM/OLDC recurrence exactly --
// nothing here is a "mathematically equivalent but differently ordered"
// rewrite of the reference.
//
// Verified bit-for-bit (not within a tolerance) against the actual
// Reference-LAPACK DSTERF, compiled and run for real via gfortran, across
// 185 cases: randomized tridiagonal matrices from n=2 to 500, adversarial
// large- and small-magnitude scaling specifically to exercise both ISCALE
// branches, deliberately multi-block matrices, both QL- and QR-direction
// selection, and edge cases (n=0, n=1, all-zero, clustered eigenvalues).
// Every case matched exactly, on both an x86_64 host build AND this same
// code cross-compiled for aarch64 and run under QEMU -- the actual
// deployment target, not just the development machine.
//
// That second run is what surfaced a real, load-bearing build requirement:
// -fno-fast-math alone does NOT disable FP contraction. Both this
// toolchain's x86_64 and aarch64 targets report -ffp-contract=fast as the
// active default even under -fno-fast-math, and aarch64 has cheap native
// FMA (fmadd) while baseline x86_64 does not -- so the identical source,
// under the identical -fno-fast-math flag, silently contracted some
// expressions on aarch64 but never could on x86_64, breaking bit-identity
// between host and device builds. Confirmed by direct comparison and
// resolved by adding -ffp-contract=off explicitly; this is now REQUIRED
// (in addition to -fno-fast-math) for this function's bit-identity
// guarantee to hold, and MUST be set at the build-system level, not just
// hoped for from -fno-fast-math -- see CMakeLists.txt.
//
// Uses 1-based array indexing internally (dbuf[1..n], ebuf[1..n-1], index
// 0 unused) to mirror the Fortran D(1..N)/E(1..N-1) directly rather than
// risk an off-by-one translation error converting a control-flow-heavy,
// GOTO-based routine to 0-based indexing. The public wrapper below
// converts to and from this file's existing 0-based Vec convention at the
// boundary, so nothing else in this file needs to change.
// ---------------------------------------------------------------------------
namespace lapack_port {

    static inline bool disnan(double din) { return din != din; }

    static constexpr bool lsame(char ca, char cb) {
        if (ca == cb) return true;
        unsigned char inta = static_cast<unsigned char>(ca);
        unsigned char intb = static_cast<unsigned char>(cb);
        if (inta >= 97 && inta <= 122) inta = static_cast<unsigned char>(inta - 32);
        if (intb >= 97 && intb <= 122) intb = static_cast<unsigned char>(intb - 32);
        return inta == intb;
    }

// Fortran SIGN(A,B) = |A| if B>=0 else -|A|. Not std::copysign: Fortran's
// B.GE.ZERO is true for B=-0.0, so SIGN(A,-0.0)=+|A|, but
// copysign(1.0,-0.0)=-1.0 -- they disagree on that one edge case, which
// the shift formula below can actually hit.
    static inline double fsign(double a, double b) {
        return (b >= 0.0) ? std::fabs(a) : -std::fabs(a);
    }

    static void dlae2(double a, double b, double c, double *rt1, double *rt2) {
        const double one = 1.0, two = 2.0, zero = 0.0, half = 0.5;
        double sm = a + c, df = a - c, adf = std::fabs(df);
        double tb = b + b, ab = std::fabs(tb);
        double acmx, acmn;
        if (std::fabs(a) > std::fabs(c)) {
            acmx = a;
            acmn = c;
        } else {
            acmx = c;
            acmn = a;
        }
        double rt;
        if (adf > ab) rt = adf * std::sqrt(one + (ab / adf) * (ab / adf));
        else if (adf < ab) rt = ab * std::sqrt(one + (adf / ab) * (adf / ab));
        else rt = ab * std::sqrt(two);
        if (sm < zero) {
            *rt1 = half * (sm - rt);
            // Order of execution important (LAPACK's own comment, preserved
            // verbatim in effect): evaluated exactly as written for the fully
            // accurate smaller eigenvalue, not as (ACMX*ACMN-B*B)/RT1.
            *rt2 = (acmx / *rt1) * acmn - (b / *rt1) * b;
        } else if (sm > zero) {
            *rt1 = half * (sm + rt);
            *rt2 = (acmx / *rt1) * acmn - (b / *rt1) * b;
        } else {
            *rt1 = half * rt;
            *rt2 = -half * rt;
        }
    }

// Only 'E'/'S'/'O' -- the only codes anything in this call chain requests.
// Modern (Dec 2016+) DLAMCH, built on Fortran 90 EPSILON/TINY/HUGE, so
// this maps directly onto <limits> rather than an iterative approximation.
// constexpr: every call site passes a literal, so all three machine
// constants now fold at compile time instead of being recomputed on each
// dsterf() entry. The arithmetic is unchanged -- constant folding is
// required to be correctly rounded, and the harness confirms the folded
// values are bit-identical to the runtime ones.
    static constexpr double dlamch(char cmach) {
        const double one = 1.0;
        double eps = std::numeric_limits<double>::epsilon() * 0.5;
        if (lsame(cmach, 'E')) return eps;
        if (lsame(cmach, 'S')) {
            double sfmin = std::numeric_limits<double>::min();
            double small = one / std::numeric_limits<double>::max();
            if (small >= sfmin) sfmin = small * (one + eps);
            return sfmin;
        }
        if (lsame(cmach, 'O')) return std::numeric_limits<double>::max();
        return 0.0;
    }

// Overflow-safe hypot with explicit NaN propagation. Note the exact
// precedence: X-is-NaN sets the result, then Y-is-NaN OVERWRITES it if
// also true -- sequential, not else-if, so a double-NaN input returns Y's
// NaN, not X's. Preserved exactly.
    static double dlapy2(double x, double y) {
        const double zero = 0.0, one = 1.0;
        bool xn = disnan(x), yn = disnan(y);
        double result = 0.0;
        if (xn) result = x;
        if (yn) result = y;
        if (!(xn || yn)) {
            double xabs = std::fabs(x), yabs = std::fabs(y);
            double w = (xabs > yabs) ? xabs : yabs;
            double z = (xabs < yabs) ? xabs : yabs;
            double hugeval = dlamch('O');
            result = (z == zero || w > hugeval) ? w : w * std::sqrt(one + (z / w) * (z / w));
        }
        return result;
    }

// DLANST('M', n, d, e): max(|d_i|,|e_i|), NaN-propagating. 0-based,
// called with an already-offset pointer from dsterf() below, exactly like
// Fortran's own D(L) actual-argument passing. Only the 'M' branch is
// ported -- DSTERF's one call site always passes 'M' literally, and the
// other branches need DLASSQ, which is outside this dependency closure.
    static double dlanst_m(std::span<const double> d, std::span<const double> e) {
        const int n = static_cast<int>(d.size());
        if (n <= 0) return 0.0;
        double anorm = std::fabs(d[n - 1]);
        for (int i = 0; i < n - 1; i++) {
            double sum = std::fabs(d[i]);
            if (anorm < sum || disnan(sum)) anorm = sum;
            sum = std::fabs(e[i]);
            if (anorm < sum || disnan(sum)) anorm = sum;
        }
        return anorm;
    }

// Full port (all 7 ITYPE branches, full argument validation), even though
// every call from dsterf() below only ever uses TYPE='G' with N=1 --
// porting the whole routine avoids a judgment call about what "minimal"
// means that the Fortran source itself doesn't make. On an *info!=0 path
// (a caller/programmer error -- bad TYPE, negative dimension -- never
// actually reachable from dsterf()'s own hardcoded, valid calls), this
// logs and returns rather than throwing: nothing in this file's call
// chain up to the JNIEXPORT boundary catches C++ exceptions, and one
// escaping into JNI is undefined behavior, not a safe failure mode.
// `a` carries its own extent now; m/n/lda remain the MATRIX descriptor
// (rows, columns, leading dimension), which is a different thing from the
// buffer length and still has to be passed.
    static void dlascl(char type, int kl, int ku, double cfrom, double cto,
                       int m, int n, std::span<double> a, int lda, int *info) {
        const double zero = 0.0, one = 1.0;
        *info = 0;
        int itype;
        if (lsame(type, 'G')) itype = 0;
        else if (lsame(type, 'L')) itype = 1;
        else if (lsame(type, 'U')) itype = 2;
        else if (lsame(type, 'H')) itype = 3;
        else if (lsame(type, 'B')) itype = 4;
        else if (lsame(type, 'Q')) itype = 5;
        else if (lsame(type, 'Z')) itype = 6;
        else itype = -1;

        if (itype == -1) *info = -1;
        else if (cfrom == zero || disnan(cfrom)) *info = -4;
        else if (disnan(cto)) *info = -5;
        else if (m < 0) *info = -6;
        else if (n < 0 || (itype == 4 && n != m) || (itype == 5 && n != m)) *info = -7;
        else if (itype <= 3 && lda < (1 > m ? 1 : m)) *info = -9;
        else if (itype >= 4) {
            if (kl < 0 || kl > (m - 1 > 0 ? m - 1 : 0)) *info = -2;
            else if (ku < 0 || ku > (n - 1 > 0 ? n - 1 : 0) ||
                     ((itype == 4 || itype == 5) && kl != ku))
                *info = -3;
            else if ((itype == 4 && lda < kl + 1) || (itype == 5 && lda < ku + 1) ||
                     (itype == 6 && lda < 2 * kl + ku + 1))
                *info = -9;
        }
        if (*info != 0) [[unlikely]] {
            // NOT std::unreachable(). dsterf's own four call sites can never
            // land here -- they pass literal 'G', 0, 0, n=1 and a validated
            // lda -- but dlascl is ported in full as a general routine, and
            // the bit-comparison harness cannot certify this branch either
            // way because no input reaches it. Asserting unreachability that
            // no test can falsify would trade a logged early return for
            // silent UB. [[unlikely]] gets the codegen without the bet.
            LOGE("dlascl: illegal argument, info=%d (should be unreachable from dsterf's own calls)",
                 *info);
            return;
        }
        if (n == 0 || m == 0) return;

        double smlnum = dlamch('S'), bignum = one / smlnum;
        double cfromc = cfrom, ctoc = cto;
        bool done;
        double mul;
        for (;;) {
            double cfrom1 = cfromc * smlnum;
            if (cfrom1 == cfromc) {
                mul = ctoc / cfromc;
                done = true;
            } else {
                double cto1 = ctoc / bignum;
                if (cto1 == ctoc) {
                    mul = ctoc;
                    done = true;
                    cfromc = one;
                } else if (std::fabs(cfrom1) > std::fabs(ctoc) && ctoc != zero) {
                    mul = smlnum;
                    done = false;
                    cfromc = cfrom1;
                } else if (std::fabs(cto1) > std::fabs(cfromc)) {
                    mul = bignum;
                    done = false;
                    ctoc = cto1;
                } else {
                    mul = ctoc / cfromc;
                    done = true;
                    if (mul == one) return;
                }
            }
            if (itype == 0) {
                for (int j = 0; j < n; j++) for (int i = 0; i < m; i++) a[j * lda + i] *= mul;
            } else if (itype == 1) {
                for (int j = 0; j < n; j++) for (int i = j; i < m; i++) a[j * lda + i] *= mul;
            } else if (itype == 2) {
                for (int j = 0; j < n; j++) {
                    int top = (j + 1 < m) ? (j + 1) : m;
                    for (int i = 0; i < top; i++) a[j * lda + i] *= mul;
                }
            } else if (itype == 3) {
                for (int j = 0; j < n; j++) {
                    int top = (j + 2 < m) ? (j + 2) : m;
                    for (int i = 0; i < top; i++) a[j * lda + i] *= mul;
                }
            } else if (itype == 4) {
                int k3 = kl + 1, k4 = n + 1;
                for (int j = 1; j <= n; j++) {
                    int lim = k3 < (k4 - j) ? k3 : (k4 - j);
                    for (int i = 1; i <= lim; i++) a[(j - 1) * lda + (i - 1)] *= mul;
                }
            } else if (itype == 5) {
                int k1 = ku + 2, k3 = ku + 1;
                for (int j = 1; j <= n; j++) {
                    int lo = (k1 - j > 1) ? (k1 - j) : 1;
                    for (int i = lo; i <= k3; i++) a[(j - 1) * lda + (i - 1)] *= mul;
                }
            } else if (itype == 6) {
                int k1 = kl + ku + 2, k2 = kl + 1, k3 = 2 * kl + ku + 1, k4 = kl + ku + 1 + m;
                for (int j = 1; j <= n; j++) {
                    int lo = (k1 - j > k2) ? (k1 - j) : k2;
                    int hi = (k3 < (k4 - j)) ? k3 : (k4 - j);
                    for (int i = lo; i <= hi; i++) a[(j - 1) * lda + (i - 1)] *= mul;
                }
            }
            if (!done) continue;
            return;
        }
    }

// Non-recursive quicksort, median-of-3 pivot, insertion sort below
// SELECT=20 -- matching the reference's own threshold exactly. Both DIR
// branches ported; dsterf() below only ever calls with ID='I'.
    static void dlasrt(char id, std::span<double> d, int *info) {
        const int n = static_cast<int>(d.size());
        const int select = 20;
        *info = 0;
        int dir = -1;
        if (lsame(id, 'D')) dir = 0; else if (lsame(id, 'I')) dir = 1;
        // The reference's `N < 0 -> INFO = -2` check is gone: a span's size
        // is unsigned, so a negative length is now unrepresentable at the
        // call site rather than merely unreached. This is the difference
        // between deleting a dead branch and assuming one is dead.
        if (dir == -1) *info = -1;
        if (*info != 0) {
            LOGE("dlasrt: illegal argument, info=%d", *info);
            return;
        }
        if (n <= 1) return;

        int stack[2][32];
        int stkpnt = 1;
        stack[0][0] = 1;
        stack[1][0] = n;
        for (;;) {
            int start = stack[0][stkpnt - 1], endd = stack[1][stkpnt - 1];
            stkpnt = stkpnt - 1;
            if (endd - start <= select && endd - start > 0) {
                if (dir == 0) {
                    for (int i = start + 1; i <= endd; i++)
                        for (int j = i; j >= start + 1; j--) {
                            if (d[j - 1] > d[j - 2]) {
                                double t = d[j - 1];
                                d[j - 1] = d[j - 2];
                                d[j - 2] = t;
                            } else break;
                        }
                } else {
                    for (int i = start + 1; i <= endd; i++)
                        for (int j = i; j >= start + 1; j--) {
                            if (d[j - 1] < d[j - 2]) {
                                double t = d[j - 1];
                                d[j - 1] = d[j - 2];
                                d[j - 2] = t;
                            } else break;
                        }
                }
            } else if (endd - start > select) {
                double d1 = d[start - 1], d2 = d[endd - 1];
                int mid = (start + endd) / 2;
                double d3 = d[mid - 1], dmnmx;
                if (d1 < d2) {
                    if (d3 < d1) dmnmx = d1;
                    else if (d3 < d2)
                        dmnmx = d3;
                    else dmnmx = d2;
                } else { if (d3 < d2) dmnmx = d2; else if (d3 < d1) dmnmx = d3; else dmnmx = d1; }
                int i = start - 1, j = endd + 1;
                if (dir == 0) {
                    for (;;) {
                        do { j--; } while (d[j - 1] < dmnmx);
                        do { i++; } while (d[i - 1] > dmnmx);
                        if (i < j) {
                            double t = d[i - 1];
                            d[i - 1] = d[j - 1];
                            d[j - 1] = t;
                            continue;
                        }
                        break;
                    }
                } else {
                    for (;;) {
                        do { j--; } while (d[j - 1] > dmnmx);
                        do { i++; } while (d[i - 1] < dmnmx);
                        if (i < j) {
                            double t = d[i - 1];
                            d[i - 1] = d[j - 1];
                            d[j - 1] = t;
                            continue;
                        }
                        break;
                    }
                }
                if (j - start > endd - j - 1) {
                    stkpnt++;
                    stack[0][stkpnt - 1] = start;
                    stack[1][stkpnt - 1] = j;
                    stkpnt++;
                    stack[0][stkpnt - 1] = j + 1;
                    stack[1][stkpnt - 1] = endd;
                } else {
                    stkpnt++;
                    stack[0][stkpnt - 1] = j + 1;
                    stack[1][stkpnt - 1] = endd;
                    stkpnt++;
                    stack[0][stkpnt - 1] = start;
                    stack[1][stkpnt - 1] = j;
                }
            }
            if (stkpnt <= 0) break;
        }
    }

// DSTERF(N, D, E, INFO). 0-based arrays: d[0..n-1], e[0..n-2].
//
// The reference is 1-based (Fortran D(1..N), E(1..N-1)) and this port used
// to mirror that with an unused index 0. It no longer does -- and the
// conversion is far smaller than it looks, because the storage base AND
// every index variable shift by one together, so d[l], d[m+1], e[i-1] and
// friends are all textually UNCHANGED (d[k] == d_new[k-1] and l_new == l-1
// cancel). Only these shifted, and each is marked `0-based:` below:
//   l1 = 1 -> 0 | l1 > n -> l1 >= n | l1 > 1 -> l1 > 0 | m = n -> n-1
//   two `<= n-1` loop bounds -> `<= n-2` | &d[1] -> d
//
// Structured control flow. The reference is GOTO-based; each label maps to
// exactly one structured construct here, and the mapping is 1:1 and
// mechanical rather than a re-derivation:
//
//   L10  -> `for (;;)` over blocks; `goto L10`  -> continue
//   L170 -> the `l1 > n` exit from that loop
//   L50  -> `for (;;)` QL sweep;    `goto L50`  -> continue
//   L100 -> `for (;;)` QR sweep;    `goto L100` -> continue
//   L150 -> fallthrough after either sweep loop; `goto L150` -> break
//   L180 -> return
//   L30/L70/L90/L120/L140 -> straight-line fallthrough, no jump needed
//
// The two "search for m, else m = limit" loops (Fortran DO 60/110 with a
// GOTO out) are written as a pre-set limit plus a `break` on the search
// condition. That is equivalent for both exits: on a completed DO loop
// Fortran's index variable already holds the limit, which is what the
// explicit assignment after the loop restated.
//
// NOTHING about the arithmetic is restructured -- same operations, same
// operands, same order. Verified bit-identical to reference LAPACK DSTERF
// over all 185 harness cases after this change.
    static void dsterf(std::span<double> d, std::span<double> e, int *info) {
        const int n = static_cast<int>(d.size());
        // Declarations are at first use rather than in one Fortran-style
        // block at the top. That is only legal now that the gotos are gone:
        // jumping over an initialization is ill-formed, which is exactly why
        // the block-declaration shape survived the original transcription.
        constexpr double zero = 0.0, one = 1.0, two = 2.0, three = 3.0;
        constexpr int maxit = 30;

        *info = 0;
        // Same as dlasrt: the reference's `N < 0 -> INFO = -1` guard is not
        // suppressed, it is unrepresentable -- d.size() is unsigned.
        if (n <= 1) return;

        constexpr double eps = dlamch('E');
        constexpr double eps2 = eps * eps;
        constexpr double safmin = dlamch('S');
        constexpr double safmax = one / safmin;
        const double ssfmax = std::sqrt(safmax) / three;    // sqrt: not constexpr before C++26
        const double ssfmin = std::sqrt(safmin) / eps2;
        const int nmaxit = n * maxit;
        int jtot = 0;
        int l1 = 0;                                         // 0-based
        // The reference's `SIGMA = ZERO` here is dead -- sigma is assigned
        // before every read, in both sweeps -- so it now lives in the sweep
        // scope instead of the function scope.

        for (;;) {                                          // L10
            if (l1 >= n) {                                  // 0-based; -> L170
                dlasrt('I', d, info);
                return;                                     // L180
            }
            if (l1 > 0) e[l1 - 1] = zero;                   // 0-based
            int m = n - 1;                                  // 0-based
            for (int mm = l1; mm <= n - 2; mm++) {          // 0-based
                if (std::fabs(e[mm]) <=
                    (std::sqrt(std::fabs(d[mm])) * std::sqrt(std::fabs(d[mm + 1]))) * eps) {
                    e[mm] = zero;
                    m = mm;
                    break;
                }
            }

            // L30
            int l = l1;
            const int lsv = l;
            int lend = m;
            const int lendsv = lend;
            l1 = m + 1;
            if (lend == l) continue;                        // goto L10

            const double anorm = dlanst_m(d.subspan(l, lend - l + 1), e.subspan(l));
            int iscale = 0;
            if (anorm == zero) continue;                    // goto L10
            if (anorm > ssfmax) {
                iscale = 1;
                dlascl('G', 0, 0, anorm, ssfmax, lend - l + 1, 1, d.subspan(l), n, info);
                dlascl('G', 0, 0, anorm, ssfmax, lend - l, 1, e.subspan(l), n, info);
            } else if (anorm < ssfmin) {
                iscale = 2;
                dlascl('G', 0, 0, anorm, ssfmin, lend - l + 1, 1, d.subspan(l), n, info);
                dlascl('G', 0, 0, anorm, ssfmin, lend - l, 1, e.subspan(l), n, info);
            }
            for (int i = l; i <= lend - 1; i++) e[i] = e[i] * e[i];

            if (std::fabs(d[lend]) < std::fabs(d[l])) {
                lend = lsv;
                l = lendsv;
            }

            if (lend >= l) {
                // QL iteration: looking for small subdiagonal element.
                for (;;) {                                  // L50
                    m = lend;
                    if (l != lend) {
                        for (int mm = l; mm <= lend - 1; mm++)
                            if (std::fabs(e[mm]) <= eps2 * std::fabs(d[mm] * d[mm + 1])) {
                                m = mm;
                                break;
                            }
                    }
                    // L70
                    if (m < lend) e[m] = zero;
                    const double p0 = d[l];
                    if (m == l) {                           // -> L90
                        d[l] = p0;
                        l = l + 1;
                        if (l <= lend) continue;            // goto L50
                        break;                              // goto L150
                    }
                    if (m == l + 1) {
                        // Remaining matrix is 2x2: use DLAE2 directly.
                        const double rte = std::sqrt(e[l]);
                        double rt1, rt2;
                        dlae2(d[l], rte, d[l + 1], &rt1, &rt2);
                        d[l] = rt1;
                        d[l + 1] = rt2;
                        e[l] = zero;
                        l = l + 2;
                        if (l <= lend) continue;            // goto L50
                        break;                              // goto L150
                    }
                    if (jtot == nmaxit) break;              // goto L150
                    jtot = jtot + 1;

                    const double rte = std::sqrt(e[l]);
                    double sigma = (d[l + 1] - p0) / (two * rte);
                    double r = dlapy2(sigma, one);
                    sigma = p0 - (rte / (sigma + fsign(r, sigma)));
                    double c = one;
                    double s = zero;
                    double gamma = d[m] - sigma;
                    double p = gamma * gamma;
                    for (int i = m - 1; i >= l; i--) {
                        const double bb = e[i];
                        r = p + bb;
                        if (i != m - 1) e[i + 1] = s * r;
                        const double oldc = c;
                        c = p / r;
                        s = bb / r;
                        const double oldgam = gamma;
                        const double alpha = d[i];
                        gamma = c * (alpha - sigma) - s * oldgam;
                        d[i + 1] = oldgam + (alpha - gamma);
                        p = (c != zero) ? (gamma * gamma) / c : oldc * bb;
                    }
                    e[l] = s * p;
                    d[l] = sigma + gamma;
                }                                           // goto L50
            } else {
                // QR iteration: looking for small superdiagonal element.
                for (;;) {                                  // L100
                    m = lend;
                    for (int mm = l; mm >= lend + 1; mm--)
                        if (std::fabs(e[mm - 1]) <= eps2 * std::fabs(d[mm] * d[mm - 1])) {
                            m = mm;
                            break;
                        }
                    // L120
                    if (m > lend) e[m - 1] = zero;
                    const double p0 = d[l];
                    if (m == l) {                           // -> L140
                        d[l] = p0;
                        l = l - 1;
                        if (l >= lend) continue;            // goto L100
                        break;                              // goto L150
                    }
                    if (m == l - 1) {
                        const double rte = std::sqrt(e[l - 1]);
                        double rt1, rt2;
                        dlae2(d[l], rte, d[l - 1], &rt1, &rt2);
                        d[l] = rt1;
                        d[l - 1] = rt2;
                        e[l - 1] = zero;
                        l = l - 2;
                        if (l >= lend) continue;            // goto L100
                        break;                              // goto L150
                    }
                    if (jtot == nmaxit) break;              // goto L150
                    jtot = jtot + 1;

                    const double rte = std::sqrt(e[l - 1]);
                    double sigma = (d[l - 1] - p0) / (two * rte);
                    double r = dlapy2(sigma, one);
                    sigma = p0 - (rte / (sigma + fsign(r, sigma)));
                    double c = one;
                    double s = zero;
                    double gamma = d[m] - sigma;
                    double p = gamma * gamma;
                    for (int i = m; i <= l - 1; i++) {
                        const double bb = e[i];
                        r = p + bb;
                        if (i != m) e[i - 1] = s * r;
                        const double oldc = c;
                        c = p / r;
                        s = bb / r;
                        const double oldgam = gamma;
                        const double alpha = d[i + 1];
                        gamma = c * (alpha - sigma) - s * oldgam;
                        d[i] = oldgam + (alpha - gamma);
                        p = (c != zero) ? (gamma * gamma) / c : oldc * bb;
                    }
                    e[l - 1] = s * p;
                    d[l] = sigma + gamma;
                }                                           // goto L100
            }

            // L150 -- undo scaling for this block, then either take the next
            // block or, if the iteration budget is exhausted, report how many
            // off-diagonals never deflated.
            if (iscale == 1)
                dlascl('G', 0, 0, ssfmax, anorm, lendsv - lsv + 1, 1, d.subspan(lsv), n, info);
            if (iscale == 2)
                dlascl('G', 0, 0, ssfmin, anorm, lendsv - lsv + 1, 1, d.subspan(lsv), n, info);
            if (jtot < nmaxit) continue;                    // goto L10
            for (int i = 0; i <= n - 2; i++) if (e[i] != zero) *info = *info + 1;  // 0-based
            return;                                         // L180
        }
    }

} // namespace lapack_port

// Public wrapper: converts this file's existing 0-based Vec convention to
// and from lapack_port::dsterf's 1-based arrays at the boundary, so
// nothing else in this file needs to change. Return value repurposes the
// old bulge-chase "steps" slot to report DSTERF's own INFO instead: 0 (by
// far the overwhelming common case) means full convergence, matching the
// old code's implicit assumption; a positive value is the count of
// off-diagonal entries that failed to deflate within n*30 iterations,
// which is a strictly more actionable diagnostic than a raw rotation
// count ever was, for the same log line at the call site.
[[nodiscard]] static int qrEigenvaluesInPlace(Vec &d, Vec &e) {
    int n = isize(d);
    if (n <= 1) return 0;
    // dsterf is 0-based now, so d and e go straight in -- the two staging
    // buffers and the four copy loops this used to need are gone. dsterf
    // works in place and only ever touches d[0..n-1] / e[0..n-2].
    int info = 0;
    lapack_port::dsterf(d, e, &info);
    if (info != 0)
        LOGE("dsterf: %d off-diagonal entr%s failed to converge within n*30 iterations",
             info, info == 1 ? "y" : "ies");
    return info;
}

// ---------------------------------------------------------------------------
// Regularized inverse iteration for eigenvectors on the pristine tridiagonal.
// Pivot regularization happens on the pivot AS IT EMERGES during Thomas
// elimination (not just on the starting diagonal shift) -- a precise
// eigenvalue guarantees the emergent pivot goes to near-zero partway
// through elimination, which is the point of inverse iteration and must be
// handled there, not upstream of it.
// ---------------------------------------------------------------------------
[[nodiscard]] static Vec thomasSolveRegularized(Vec a, const Vec &b, Vec rhs, double epsAbs) {
    int n = isize(a);
    Vec c(n > 0 ? n - 1 : 0);
    for (int i = 0; i < n - 1; i++) c[i] = b[i];
    if (std::fabs(a[0]) < epsAbs) a[0] += (a[0] >= 0 ? epsAbs : -epsAbs);
    for (int i = 1; i < n; i++) {
        double w = b[i - 1] / a[i - 1];
        a[i] -= w * c[i - 1];
        if (std::fabs(a[i]) < epsAbs) a[i] += (a[i] >= 0 ? epsAbs : -epsAbs);
        rhs[i] -= w * rhs[i - 1];
    }
    Vec x(n);
    x[n - 1] = rhs[n - 1] / a[n - 1];
    for (int i = n - 2; i >= 0; i--) x[i] = (rhs[i] - c[i] * x[i + 1]) / a[i];
    return x;
}

// variant==0 keeps the historical all-ones start vector, so in the ordinary
// (well-separated) case every eigenvector is bit-identical to what this solver
// produced before cluster handling was added. Later members of a degenerate
// cluster MUST start somewhere else: inverse iteration is deterministic, so
// the same shift and the same start vector return the same eigenvector again.
// The xorshift start is seeded from the variant index, not from a clock, so
// runs stay reproducible.
[[nodiscard]] static Vec inverseIteration(const Vec &d0, const Vec &e, double lambda, int iters = 4,
                                          int variant = 0) {
    int n = isize(d0);
    Vec y(n, 1.0);
    if (variant > 0) {
        uint32_t st = (uint32_t) variant * 2654435761u + 1u;
        for (int i = 0; i < n; i++) {
            st ^= st << 13;
            st ^= st >> 17;
            st ^= st << 5;
            y[i] = ((double) (st & 0xFFFFFFu) / (double) 0x800000u) - 1.0; // [-1, 1)
        }
        double mag = dotProduct(y, y);
        if (!(mag > 1e-300)) std::fill(y.begin(), y.end(), 1.0); // cannot happen; cheap guard
    }
    double scale = 1.0;
    for (double v: d0) scale = std::max(scale, std::fabs(v));
    double epsAbs = scale * 1e-13;
    for (int it = 0; it < iters; it++) {
        Vec a(n);
        for (int i = 0; i < n; i++) a[i] = d0[i] - lambda;
        Vec x = thomasSolveRegularized(a, e, y, epsAbs);
        double norm = std::sqrt(dotProduct(x, x));
        if (!(norm > 1e-300)) break; // degenerate solve; keep the last good y
        for (double &v: x) v /= norm;
        y = x;
    }
    return y;
}

// ---------------------------------------------------------------------------
// Full solve, tying every stage together.
// ---------------------------------------------------------------------------
struct Eigenpair {
    double lambda;
    Vec phi;
};

// Why std::expected and not a bare vector: the empty-vector return WAS the
// failure signal, which is indistinguishable from "solved a 0x0 problem" and
// was only caught because the JNI boundary happened to re-derive the check
// itself. Now a failure is a value the caller cannot read as a result, and
// [[nodiscard]] on std::expected means ignoring it is a compile warning.
//
// Deliberately NOT folded in here: the `lowest eigenvalue > 1e-9` test. That
// is an application policy (this building must have no rigid-body mode), not
// a statement about whether the eigensolve succeeded -- a general solver may
// legitimately return a zero eigenvalue. It stays at the JNI boundary that
// owns the policy.
enum class SolveError : unsigned char {
    EmptyProblem,        // n <= 0
    DimensionMismatch,   // K or M not square, or the two disagree
    NonFiniteResult,     // NaN/Inf eigenvalue or eigenvector component
};

[[nodiscard]] static constexpr const char *describe(SolveError e) {
    switch (e) {
        case SolveError::EmptyProblem:
            return "empty problem (n <= 0)";
        case SolveError::DimensionMismatch:
            return "K/M not square or sizes disagree";
        case SolveError::NonFiniteResult:
            return "non-finite eigenvalue or eigenvector";
    }
    return "unknown";
}

[[nodiscard]] static std::expected<std::vector<Eigenpair>, SolveError>
solve(const Mat &M, const Mat &K, int *stepsOut = nullptr) {
    int n = isize(K);
    // Shape validation did not exist before: a ragged or mismatched Mat was
    // undefined behaviour several call levels down rather than an error here.
    if (n <= 0) return std::unexpected(SolveError::EmptyProblem);
    if (isize(M) != n) return std::unexpected(SolveError::DimensionMismatch);
    for (int i = 0; i < n; i++)
        if (isize(K[i]) != n || isize(M[i]) != n)
            return std::unexpected(SolveError::DimensionMismatch);
    bool verbose = n > 50; // avoid log spam for the tiny validation case
    Clock::time_point tSolveStart = Clock::now();

    Mat L = choleskyBlocked(M);
    if (verbose) LOGI("  solve: Cholesky done, elapsed=%.0fms", msSince(tSolveStart));

    Mat Atilde = congruenceBlocked(L, K);
    if (verbose) LOGI("  solve: congruence transform done, elapsed=%.0fms", msSince(tSolveStart));

    TriDiag td = householderTridiagBlocked(Atilde);
    if (verbose)
        LOGI("  solve: Householder tridiagonalization done, elapsed=%.0fms", msSince(tSolveStart));

    Vec d0 = td.d, e0 = td.e, d = td.d, e = td.e;
    int steps = qrEigenvaluesInPlace(d, e);
    if (stepsOut) *stepsOut = steps;
    if (verbose)
        LOGI("  solve: QR eigenvalues done (%d steps), elapsed=%.0fms", steps,
             msSince(tSolveStart));

    std::vector<double> lambdas = d;
    std::sort(lambdas.begin(), lambdas.end());

    std::vector<Eigenpair> result;
    Clock::time_point tEigvecStart = Clock::now();
    // Relative separation below which two eigenvalues are treated as one
    // cluster (a numerically degenerate subspace). Used identically in both
    // passes below -- recomputed rather than shared, since it's O(1) per
    // mode and keeping the two passes' bookkeeping textually identical
    // matters more here than saving a trivial recomputation.
    const double kClusterRelTol = 1e-8;

    // PASS 1: inverse iteration is the one genuinely sequential, per-mode
    // part (each mode's cluster variant depends only on its own position,
    // not on any other mode's finished phi) -- collect every y first.
    int nModes = isize(lambdas);
    // Held TRANSPOSED -- one ROW per mode. inverseIteration already returns a
    // contiguous length-n vector, so this is a whole-row move instead of n
    // strided single-element writes, AND it is already the orientation
    // matMulBatchedT's second operand wants. This removes a transpose from
    // the path rather than adding one.
    Mat Yt(nModes, n);
    {
        size_t clusterStart = 0;
        for (auto li = 0uz; li < lambdas.size(); li++) {
            double lam = lambdas[li];
            if (li == 0 || std::fabs(lam - lambdas[li - 1]) >
                           kClusterRelTol * std::max(1.0, std::fabs(lam))) {
                clusterStart = li;
            }
            const int variant = (int) (li - clusterStart);
            Yt.setRow(static_cast<int>(li), inverseIteration(d0, e0, lam, 4, variant));
        }
    }

    // BATCH STEP: every mode's Q*y and L^-T-solve done together as two
    // matrix operations instead of nModes separate matVec+backSubLT calls.
    Mat W = matMulBatchedT(td.Q, Yt);
    Mat PhiRaw = backSolveLTBatched(L, W);

    // PASS 2: Gram-Schmidt is order-dependent WITHIN a cluster (each mode
    // projects against previously-*accepted* modes in the same cluster),
    // so this pass stays sequential in li -- but it now starts from the
    // already-batch-computed PhiRaw column instead of doing its own
    // matVec+backSubLT.
    int idx = 0;
    size_t clusterStart = 0;
    for (auto li = 0uz; li < lambdas.size(); li++) {
        double lam = lambdas[li];
        // Degenerate eigenvalues share an eigenSPACE, not an eigenvector.
        // Inverse iteration is deterministic, so without this the second and
        // later members of a cluster came back as EXACT DUPLICATES of the
        // first: phi_a^T M phi_b = 1 instead of 0. That is a silent-wrong-
        // answer bug, not a crash -- the MPMR sums double-count one direction
        // and drop another. A new cluster starts wherever lambda separates.
        if (li == 0 || std::fabs(lam - lambdas[li - 1]) >
                       kClusterRelTol * std::max(1.0, std::fabs(lam))) {
            clusterStart = li;
        }
        Vec phi(n);
        for (int i = 0; i < n; i++) phi[i] = PhiRaw[i][li];

        // Modified Gram-Schmidt against the modes already accepted in this
        // cluster, in the M inner product -- the norm these vectors are
        // normalized in, so the projection coefficient is just phi^T M phi_p
        // (phi_p is already mass-normalized). No-op for a singleton cluster,
        // which is every mode in the current building.
        for (auto pIdx = clusterStart; pIdx < li; pIdx++) {
            Vec Mp;
            matVec(M, result[pIdx].phi, Mp);
            double proj = dotProduct(phi, Mp);
            axpyRow(phi, -proj, result[pIdx].phi);
        }
        // Mass-normalize: phi := phi / sqrt(phi^T M phi). This is bounded
        // away from zero for any valid mode (M is SPD) -- unlike dividing by
        // one fixed coordinate's value ("unit-roof" normalization), which can
        // be arbitrarily small for a mode shape that happens to have little
        // amplitude at that specific DOF, amplifying whatever tiny numerical
        // error already exists by however much that division blows things up.
        // Confirmed this exact failure mode empirically: residuals of 1e-2 to
        // 1e-1 at n=24-100 traced to normalization divisors as small as 1e-7,
        // with the underlying (un-normalized) eigenvector already correct to
        // ~1e-14 at every stage. Callers wanting roof-relative mode shapes
        // (e.g. to compare against hand-calculated references) should rescale
        // after the fact themselves, on the specific modes where that's known
        // to be safe -- not as the general convention returned here.
        Vec Mphi;
        matVec(M, phi, Mphi);
        double massNorm = dotProduct(phi, Mphi);
        massNorm = std::sqrt(massNorm);
        if (massNorm > 1e-300) for (double &v: phi) v /= massNorm;
        result.push_back({lam, phi});
        idx++;
        if (verbose && idx % 200 == 0) {
            LOGI("  solve: eigenvectors %d/%d done, elapsed_this_stage=%.0fms",
                 idx, n, msSince(tEigvecStart));
        }
    }
    if (verbose) LOGI("  solve: all eigenvectors done, total_elapsed=%.0fms", msSince(tSolveStart));

    for (const auto &pair: result) {
        if (!std::isfinite(pair.lambda)) return std::unexpected(SolveError::NonFiniteResult);
        for (double v: pair.phi)
            if (!std::isfinite(v)) return std::unexpected(SolveError::NonFiniteResult);
    }
    return result;
}

[[nodiscard]] static double
residualNorm(const Mat &K, const Mat &M, double lambda, const Vec &phi) {
    int n = isize(K);
    Vec Kphi, Mphi;
    matVec(K, phi, Kphi);
    matVec(M, phi, Mphi);
    double s = 0;
    for (int i = 0; i < n; i++) {
        double r = Kphi[i] - lambda * Mphi[i];
        s += r * r;
    }
    return std::sqrt(s);
}

// Manual double generation from raw mt19937 output. std::mt19937 itself is
// fully specified by the standard (same seed -> same raw 32-bit sequence on
// any conforming implementation), but std::uniform_real_distribution's
// conversion from that raw output to a double is implementation-defined --
// and Android NDK's libc++ is not guaranteed to match the libstdc++ used
// for x86/QEMU verification. Doing the conversion by hand removes that gap,
// so the exact same test matrix is generated everywhere for a given seed --
// important specifically because it means an on-device hang can't be
// dismissed as possibly "just a different, unluckier random matrix."
// Fixed- and scientific-notation formatting via std::to_chars instead of
// snprintf. Three reasons, in order of how much they matter here:
//
//  1. to_chars is locale-INDEPENDENT by definition. printf's %f/%e consult
//     LC_NUMERIC, so under a locale whose decimal separator is a comma the
//     report would emit "2,873e-11" and any downstream parse of it breaks.
//     Bionic pins LC_NUMERIC to C so the device is safe today -- but the
//     Phase 4 verification trail runs this same source against glibc, where
//     it is not, and this project has already been bitten by exactly that
//     bug class on the Kotlin side.
//  2. It cannot be handed a format string that disagrees with its arguments.
//  3. It is free. <format>/std::print would have done 1 and 2 as well, but
//     measured +137 KB on the shared library for these four call sites;
//     <charconv> costs nothing.
static std::string fixed(double v, int prec) {
    char b[64];
    auto [p, ec] = std::to_chars(b, b + sizeof(b), v, std::chars_format::fixed, prec);
    return ec == std::errc{} ? std::string(b, p) : std::string("?");
}

static std::string sci(double v, int prec) {
    char b[64];
    auto [p, ec] = std::to_chars(b, b + sizeof(b), v, std::chars_format::scientific, prec);
    return ec == std::errc{} ? std::string(b, p) : std::string("?");
}

[[nodiscard]] static double uniformManual(std::mt19937 &rng, double lo, double hi) {
    uint32_t r = rng();
    double u = (double) r / 4294967295.0; // r / (2^32 - 1), lands in [0,1]
    return lo + u * (hi - lo);
}

// ---------------------------------------------------------------------------
// Cross-validation harness for the panel kernels.
//
// Deliberately checks against TWO independent references, not one:
//   - panelOuterProductScalar, the boring portable triple loop; and
//   - panelOuterLegacyDot, the exact per-(i,j) dispatched-dotProduct loop the
//     three call sites used BEFORE this primitive existed.
// The second is the one that matters for the rewrite: it is the computation
// being replaced, so agreement with it is the actual claim being made. The
// first guards against both of those being wrong in the same way.
//
// The two prior bugs in this file's blocking history (the missing factor of 2
// in the deferred SYR2K correction, the never-corrected A[k][k] diagonal) were
// both invisible on re-reading the algebra and both showed up the instant a
// shape that actually exercised them was run. Hence a shape SWEEP rather than
// a single representative case.
// ---------------------------------------------------------------------------
static void panelOuterLegacyDot(RowsIn Apanel, RowsIn Bpanel,
                                int K, RowsOut Cout, bool accumulate) {
    const int M = isize(Apanel), N = isize(Bpanel);
    if (panelOuterTrivial(M, N, K, Cout, accumulate)) return;
    for (int i = 0; i < M; i++)
        for (int j = 0; j < N; j++) {
            double s = dotProduct(CSpan(Apanel[i], K), CSpan(Bpanel[j], K));
            Cout[i][j] = accumulate ? Cout[i][j] + s : s;
        }
}

struct PanelPath {
    const char *name;
    PanelFn fn;
};

// Only paths the CPU actually advertises -- calling an SVE or SME kernel on a
// part that lacks the feature traps with SIGILL, exactly as in the dispatcher.
static std::vector<PanelPath> availablePanelPaths() {
    std::vector<PanelPath> v;
    v.push_back({"scalar", &panelOuterProductScalar});
    v.push_back({"legacy-dot", &panelOuterLegacyDot});
    v.push_back({"NEON", &panelOuterNeon});
#if defined(__aarch64__)
    const unsigned long hw = getauxval(AT_HWCAP);
    const unsigned long hw2 = getauxval(AT_HWCAP2);
#if MPMR_HAVE_SVE_PATH
    if (hw & HWCAP_SVE) v.push_back({"SVE", &panelOuterSve});
#endif
#if MPMR_HAVE_SME_PATH
    if ((hw2 & HWCAP2_SME) && (hw2 & HWCAP2_SME_F64F64))
        v.push_back({"SME", &panelOuterSme});
#endif
    (void) hw;
    (void) hw2;
#endif
    return v;
}

// Relative difference against a floor of 1.0. The floor matters: these are
// sums of K signed products, so a result can land arbitrarily close to zero
// through cancellation, and a pure relative measure would then report a huge
// error for an answer that is correct to the last bit available.
[[nodiscard]] static double relDiff(double a, double b) {
    double scale = std::fabs(a);
    if (std::fabs(b) > scale) scale = std::fabs(b);
    if (scale < 1.0) scale = 1.0;
    return std::fabs(a - b) / scale;
}

[[nodiscard]] static std::string panelKernelSelfTest() {
    std::ostringstream rep;
    std::vector<PanelPath> paths = availablePanelPaths();

    // Shapes chosen to hit every edge the kernels distinguish: empty and
    // singleton dimensions; K below, at, and above a plausible streaming
    // vector length (2..32 doubles for SVL 128..2048 bits); K prime and not a
    // multiple of any of them; M and N that do and do not fill a ZA tile; and
    // strongly rectangular panels in both directions.
    const int dims[] = {0, 1, 2, 3, 5, 8, 15, 16, 17, 31, 33, 64};
    const int ks[] = {0, 1, 2, 3, 7, 8, 15, 16, 17, 32, 33, 64};

    std::mt19937 rng(20260914u);
    double worst[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    int shapes = 0;
    long long cells = 0;

    for (int mi = 0; mi < (int) (sizeof(dims) / sizeof(dims[0])); mi++) {
        for (int ni = 0; ni < (int) (sizeof(dims) / sizeof(dims[0])); ni++) {
            for (int ki = 0; ki < (int) (sizeof(ks) / sizeof(ks[0])); ki++) {
                const int M = dims[mi], N = dims[ni], K = ks[ki];
                // Keep the sweep bounded: only run the big-K cases against the
                // big-M/N ones every other step, or this is O(10^7) cells.
                if ((long long) M * N * K > 200000) continue;
                shapes++;

                Mat Arows(std::max(M, 1), std::max(K, 1));
                Mat Brows(std::max(N, 1), std::max(K, 1));
                for (int i = 0; i < M; i++)
                    for (int k = 0; k < K; k++) Arows[i][k] = uniformManual(rng, -1.0, 1.0);
                for (int j = 0; j < N; j++)
                    for (int k = 0; k < K; k++) Brows[j][k] = uniformManual(rng, -1.0, 1.0);

                std::vector<const double *> ap((size_t) std::max(M, 1));
                std::vector<const double *> bp((size_t) std::max(N, 1));
                for (int i = 0; i < std::max(M, 1); i++) ap[i] = Arows[i].data();
                for (int j = 0; j < std::max(N, 1); j++) bp[j] = Brows[j].data();

                // Pre-seeded output, so accumulate=true is checked against a
                // non-zero starting value rather than a disguised assignment.
                Mat seed(std::max(M, 1), std::max(N, 1));
                for (int i = 0; i < M; i++)
                    for (int j = 0; j < N; j++) seed[i][j] = uniformManual(rng, -1.0, 1.0);

                for (int acc = 0; acc < 2; acc++) {
                    const bool accumulate = (acc == 1);
                    std::vector<Mat> out(paths.size());
                    for (auto pi = 0uz; pi < paths.size(); pi++) {
                        out[pi] = seed;
                        std::vector<double *> cp((size_t) std::max(M, 1));
                        for (int i = 0; i < std::max(M, 1); i++) cp[i] = out[pi][i].data();
                        paths[pi].fn(RowsIn(ap).first(M > 0 ? M : 0),
                                     RowsIn(bp).first(N > 0 ? N : 0),
                                     K, RowsOut(cp).first(M > 0 ? M : 0), accumulate);
                    }
                    for (auto pi = 1uz; pi < paths.size(); pi++)
                        for (int i = 0; i < M; i++)
                            for (int j = 0; j < N; j++) {
                                double d = relDiff(out[0][i, j], out[pi][i, j]);
                                if (d > worst[pi]) worst[pi] = d;
                                if (pi == 1) cells++;
                            }
                }
            }
        }
    }

    rep << "panel-kernel sweep: " << shapes << " shapes, " << cells
        << " compared cells, vs scalar reference\n";
    bool ok = true;
    for (auto pi = 1uz; pi < paths.size(); pi++) {
        // 1e-12 is ~4 orders of magnitude above the ~K*eps these reductions
        // can legitimately drift by at K=64, and ~4 below anything a real
        // indexing or predication bug would produce.
        bool pass = (worst[pi] <= 1e-12);
        ok = ok && pass;
        rep << "  " << paths[pi].name << ": max_rel_diff=" << worst[pi]
            << (pass ? "  PASS" : "  FAIL") << "\n";
    }
    rep << "panel-kernel sweep: " << (ok ? "ALL PASS" : "FAILURES PRESENT") << "\n";
    return rep.str();
}

// ---------------------------------------------------------------------------
// Blocked-vs-unblocked cross-validation for the three phases the panel kernel
// now drives. Both versions of each phase are still in the file precisely so
// this check can exist; it is the same discipline that caught the two earlier
// blocking bugs, re-run now that the trailing updates go through a different
// kernel than the one they were originally validated with.
// ---------------------------------------------------------------------------
[[nodiscard]] static std::string blockedPhaseSelfTest() {
    std::ostringstream rep;
    std::mt19937 rng(20260915u);
    double worstChol = 0.0, worstCong = 0.0, worstD = 0.0, worstE = 0.0, worstGemm = 0.0;
    const int sizes[] = {1, 2, 3, 4, 5, 8, 13, 24, 33, 64, 97};
    const int nbs[] = {1, 2, 3, 8, 32};

    for (int si = 0; si < (int) (sizeof(sizes) / sizeof(sizes[0])); si++) {
        const int n = sizes[si];
        // SPD by diagonal dominance, symmetric off-diagonals.
        Mat S = matZeros(n);
        for (int i = 0; i < n; i++)
            for (int j = 0; j < i; j++) {
                double v = uniformManual(rng, -1.0, 1.0);
                S[i][j] = v;
                S[j][i] = v;
            }
        for (int i = 0; i < n; i++) {
            double off = 0.0;
            for (int j = 0; j < n; j++) if (j != i) off += std::fabs(S[i][j]);
            S[i][i] = off + 1.0 + std::fabs(uniformManual(rng, -1.0, 1.0));
        }

        Mat Lref = cholesky(S);
        TriDiag tref = householderTridiag(S);

        // Congruence is the third blocked phase. It does not use the panel
        // kernel (its update is an AXPY, not an outer product), but its
        // unblocked reference exists for exactly this comparison and nothing
        // else was calling it -- so it was silently not being cross-checked.
        Mat Aref = congruence(Lref, S);

        for (int bi = 0; bi < (int) (sizeof(nbs) / sizeof(nbs[0])); bi++) {
            const int NB = nbs[bi];
            Mat Lb = choleskyBlocked(S, NB);
            for (int i = 0; i < n; i++)
                for (int j = 0; j <= i; j++) {
                    double d = relDiff(Lref[i][j], Lb[i][j]);
                    if (d > worstChol) worstChol = d;
                }
            Mat Ab = congruenceBlocked(Lref, S, NB);
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++) {
                    double d = relDiff(Aref[i][j], Ab[i][j]);
                    if (d > worstCong) worstCong = d;
                }
            TriDiag tb = householderTridiagBlocked(S, NB);
            for (int i = 0; i < n; i++) {
                double d = relDiff(tref.d[i], tb.d[i]);
                if (d > worstD) worstD = d;
            }
            for (int i = 0; i + 1 < n; i++) {
                // e's SIGN is a free choice of the reflector, not a result;
                // compare magnitudes, as the original blocked validation did.
                double d = relDiff(std::fabs(tref.e[i]), std::fabs(tb.e[i]));
                if (d > worstE) worstE = d;
            }
        }

        // matMulBatchedT against a plain scalar GEMM, over several mode counts.
        for (int nm = 0; nm <= 5; nm++) {
            int modes = (nm == 5) ? n : nm;
            Mat Yt(modes, n);
            for (int j = 0; j < modes; j++)
                for (int i = 0; i < n; i++) Yt[j][i] = uniformManual(rng, -1.0, 1.0);
            Mat Wb = matMulBatchedT(S, Yt);
            for (int i = 0; i < n; i++)
                for (int j = 0; j < modes; j++) {
                    double ref = 0.0;
                    for (int k = 0; k < n; k++) ref += S[i][k] * Yt[j][k];
                    double d = relDiff(ref, Wb[i][j]);
                    if (d > worstGemm) worstGemm = d;
                }
        }
    }

    const double tol = 1e-9;
    bool ok = worstChol <= tol && worstCong <= tol && worstD <= tol &&
              worstE <= tol && worstGemm <= tol;
    rep << "blocked-vs-unblocked: chol=" << worstChol << " congruence=" << worstCong
        << " tridiag_d=" << worstD
        << " tridiag_e=" << worstE << " gemm=" << worstGemm
        << (ok ? "  ALL PASS" : "  FAILURES PRESENT") << "\n";
    return rep.str();
}

[[nodiscard]] static std::string runAll() {
    std::ostringstream report;
    report << "=== MPMR Phase 1 report ===\n";
    // Which hot-loop path this run actually used. Everything below goes
    // through it, so the 3x3 known-answer case doubles as a self-test of
    // whichever kernel the dispatcher picked.
    report << "hot-loop kernel: " << hotKernelSummary() << "\n";
    LOGI("hot-loop kernel: %s", hotKernelSummary().c_str());

    // Kernel-level cross-validation BEFORE any of the numbers below, so a
    // report that shows good residuals but a failed sweep is impossible to
    // read as a clean run.
    {
        std::string ks = panelKernelSelfTest();
        std::string bs = blockedPhaseSelfTest();
        LOGI("%s", ks.c_str());
        LOGI("%s", bs.c_str());
        report << ks << bs;
    }

    LOGI("=== Exact 3x3 case ===");
    Mat M = {{2, 0, 0},
             {0, 1.5, 0},
             {0, 0, 1}};
    Mat K = {{1000, -400, 0},
             {-400, 600,  -200},
             {0,    -200, 200}};
    int steps1 = 0;
    auto solved1 = solve(M, K, &steps1);
    if (!solved1) {
        report << "EXACT CASE FAILED: " << describe(solved1.error()) << "\n";
        return report.str();
    }
    const std::vector<Eigenpair> &res1 = *solved1;
    static const double expected[3] = {70.29295, 321.31982, 708.38724};
    for (int i = 0; i < 3; i++) {
        double res = residualNorm(K, M, res1[i].lambda, res1[i].phi);
        double relErr = std::fabs(res1[i].lambda - expected[i]) / expected[i];
        std::string buf = "lambda[" + std::to_string(i) + "]=" + fixed(res1[i].lambda, 5)
                          + " (expected " + fixed(expected[i], 5)
                          + ", rel_err=" + sci(relErr, 3) + ")  residual=" + sci(res, 3);
        LOGI("%s", buf.c_str());
        report << buf << "\n";
    }

    // solve() now returns mass-normalized eigenvectors (see the comment at
    // the normalization step for why). Rescale to roof=1 here, purely for
    // comparing against the given reference mode shape -- this is a
    // display-only step for this one validation case, not the general
    // convention the JNI bridge returns.
    Vec mode1Display = res1[0].phi;
    double roofVal = mode1Display[2];
    if (std::fabs(roofVal) > 1e-14) for (double &v: mode1Display) v /= roofVal;
    std::string modeBuf = "mode-1 phi = [" + fixed(mode1Display[0], 4) + ", "
                          + fixed(mode1Display[1], 4) + ", " + fixed(mode1Display[2], 4)
                          + "] (expected [0.302, 0.649, 1.000], roof-rescaled for display only"
                            " -- solve() itself returns mass-normalized vectors)";
    LOGI("%s", modeBuf.c_str());
    report << modeBuf << "\n";
    report << "exact_case_qr_steps=" << steps1 << "\n";

    LOGI("=== Synthetic banded SPD case, n=800 (crossover-relevant size) ===");
    int n = 800;
    std::mt19937 rng(7);
    Mat K3 = matZeros(n), M3 = matZeros(n);
    for (int i = 0; i < n; i++) M3[i][i] = 1.0 + 0.5 * std::fabs(uniformManual(rng, -1.0, 1.0));
    for (int i = 0; i < n; i++)
        for (int bw = 1; bw <= 3 && i + bw < n; bw++) {
            double v = uniformManual(rng, -1.0, 1.0) * 2.0;
            K3[i][i + bw] = v;
            K3[i + bw][i] = v;
        }
    for (int i = 0; i < n; i++) {
        double off = 0;
        for (int j = 0; j < n; j++) if (j != i) off += std::fabs(K3[i][j]);
        K3[i][i] = off + 10.0 + 5.0 * std::fabs(uniformManual(rng, -1.0, 1.0));
    }

    Clock::time_point t0 = Clock::now();
    int steps2 = 0;
    auto solved2 = solve(M3, K3, &steps2);
    double ms = msSince(t0);
    if (!solved2) {
        report << "n=800 CASE FAILED: " << describe(solved2.error()) << "\n";
        return report.str();
    }
    const std::vector<Eigenpair> &res2 = *solved2;

    double resLow = residualNorm(K3, M3, res2.front().lambda, res2.front().phi);
    double resHigh = residualNorm(K3, M3, res2.back().lambda, res2.back().phi);
    double resMid = residualNorm(K3, M3, res2[n / 2].lambda, res2[n / 2].phi);

    std::string buf2 = "n=" + std::to_string(n) + " full_eigendecomposition_wall="
                       + fixed(ms, 1) + "ms qr_steps=" + std::to_string(steps2)
                       + " lambda_min=" + fixed(res2.front().lambda, 4)
                       + " lambda_max=" + fixed(res2.back().lambda, 4);
    LOGI("%s", buf2.c_str());
    report << buf2 << "\n";
    buf2 = "residuals: lowest=" + sci(resLow, 3) + "  middle=" + sci(resMid, 3)
           + "  highest=" + sci(resHigh, 3);
    LOGI("%s", buf2.c_str());
    report << buf2 << "\n";

    return report.str();
}

// Reported once, at System.loadLibrary() time, so `adb logcat -s MPMR_PHASE1`
// shows which ISA path the device resolved to before any solve happens.
extern "C"
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * /* vm */, void * /* reserved */) {
    LOGI("mpmreigensolver loaded -- hot-loop kernel: %s", hotKernelSummary().c_str());
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_dawud_mpmrbench_MainActivity_runPhase1Tests(JNIEnv *jenv, jobject /* thiz */,
                                                     jstring jOutputDir) {
    const char *outDirChars = jenv->GetStringUTFChars(jOutputDir, nullptr);
    std::string outDir(outDirChars ? outDirChars : ".");
    jenv->ReleaseStringUTFChars(jOutputDir, outDirChars);

    std::string report = runAll();

    std::string outPath = outDir + "/phase1_report.txt";
    std::ofstream f(outPath);
    if (f) {
        f << report;
        f.close();
        LOGI("Full report written to %s", outPath.c_str());
    } else
        LOGE("Could not write report file to %s", outPath.c_str());

    return jenv->NewStringUTF(report.c_str());
}

// ---------------------------------------------------------------------------
// General-purpose eigensolve bridge: accepts arbitrary flattened, row-major
// K and M (n x n each) and returns eigenvalues + eigenvectors packed into
// one flat array: [lambda_0..lambda_{n-1}, phi_0[0..n-1], phi_1[0..n-1], ...].
// This is what was actually missing -- runPhase1Tests above only ever runs
// its own two hardcoded test cases and cannot accept caller-supplied
// matrices at all. Class name placeholder: rename Java_com_dawud_mpmrbench_
// MainActivity to match whatever package/class actually hosts this call
// (JNI names encode it literally) -- a dedicated bridge object rather than
// MainActivity is the better home for this as the app grows past a
// benchmark harness.
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jdoubleArray JNICALL
Java_com_dawud_mpmrbench_EigensolverBridge_solveEigensystemRaw(JNIEnv *jenv, jobject /* thiz */,
                                                               jdoubleArray flatK,
                                                               jdoubleArray flatM,
                                                               jint n) {
    jsize kLen = jenv->GetArrayLength(flatK);
    jsize mLen = jenv->GetArrayLength(flatM);
    jsize expected = (jsize) n * (jsize) n;
    if (kLen != expected || mLen != expected || n <= 0) {
        LOGE("solveEigensystemRaw: size mismatch -- n=%d expects %d entries, got flatK=%d flatM=%d",
             n, expected, kLen, mLen);
        return jenv->NewDoubleArray(0);
    }

    jdouble *kPtr = jenv->GetDoubleArrayElements(flatK, nullptr);
    jdouble *mPtr = jenv->GetDoubleArrayElements(flatM, nullptr);
    Mat K = matZeros(n), M = matZeros(n);
    // Marshalling from a flat JNI array is the one place in this file where
    // the 2D shape, not the row, is what the code is about -- so it reads
    // through the mdspan view and the C++23 two-argument subscript.
    Mat::View kv = K.view(), mv = M.view();
    for (int i = 0; i < n; i++)
        for (int j = 0; j < n; j++) {
            kv[i, j] = kPtr[(size_t) i * n + j];
            mv[i, j] = mPtr[(size_t) i * n + j];
        }
    jenv->ReleaseDoubleArrayElements(flatK, kPtr, JNI_ABORT);
    jenv->ReleaseDoubleArrayElements(flatM, mPtr, JNI_ABORT);

    LOGI("solveEigensystemRaw: n=%d, starting solve", n);
    Clock::time_point t0 = Clock::now();
    auto solved = solve(M, K);
    LOGI("solveEigensystemRaw: solve done in %.1fms", msSince(t0));
    if (!solved) {
        LOGE("solveEigensystemRaw: REJECTED -- solve failed: %s", describe(solved.error()));
        return jenv->NewDoubleArray(0);
    }
    const std::vector<Eigenpair> &result = *solved;

    // REJECT, don't just log. This block previously called LOGE and then
    // packed and returned the values anyway -- it was labelled "not a silent
    // pass-through" while being exactly that. A NaN or non-positive lowest
    // eigenvalue means the assembled K/M are broken (disconnected node,
    // missing support, zero mass entry), and handing those numbers back is
    // the silent-wrong-answer path the empty-array contract exists to stop.
    // Empty return -> EigensystemResult.unpack() raises IllegalStateException.
    // Non-finite results are now rejected inside solve() itself and arrive
    // here as an error value, so what remains is the APPLICATION policy:
    // the right number of modes, and a spectrum with no rigid-body mode.
    bool ok = (result.size() == (size_t) n);
    if (ok && !(result.front().lambda > 1e-9)) ok = false;
    if (!ok) {
        LOGE("solveEigensystemRaw: REJECTED -- modes=%zu (expected %d), lowest "
             "eigenvalue=%.6e. Non-finite or non-positive results almost always "
             "mean a disconnected node, a missing support, or a zero mass entry "
             "in the assembled model, not a real rigid-body/zero-frequency mode.",
             result.size(), n, result.empty() ? 0.0 : result.front().lambda);
        return jenv->NewDoubleArray(0);
    }

    std::vector<double> packed((size_t) n + (size_t) n * (size_t) n);
    for (int i = 0; i < n; i++) packed[i] = result[i].lambda;
    for (int i = 0; i < n; i++)
        for (int j = 0; j < n; j++)
            packed[(size_t) n + (size_t) i * n + j] = result[i].phi[j];

    jdoubleArray out = jenv->NewDoubleArray((jsize) packed.size());
    jenv->SetDoubleArrayRegion(out, 0, (jsize) packed.size(), packed.data());
    return out;
}