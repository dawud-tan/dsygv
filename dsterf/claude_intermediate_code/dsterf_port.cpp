// Faithful C++ port of LAPACK's DSTERF and its exact dependency closure,
// transcribed line-for-line from the actual Fortran source (dsterf.f,
// LAPACK auxiliary routine, December 2016 revision of DLAMCH), not from a
// generic description of "Wilkinson-shift QL/QR." Every operation order,
// branch, and named intermediate (GAMMA, OLDGAM, OLDC, SIGMA, ISCALE, ...)
// is preserved exactly as written -- nothing here is a "mathematically
// equivalent but differently ordered" rewrite.
//
// Ported: DISNAN, LSAME (ASCII branch only -- see note), DLAE2, DLANST
// ('M' branch only -- see note), DLAPY2, DLAMCH ('E'/'S'/'O' -- the only
// codes DSTERF's own call chain uses), DLASCL (all 7 ITYPE branches, full
// argument validation), DLASRT (both DIR branches), XERBLA (adapted for a
// library embedded in an app -- see note), DSTERF itself.
//
// NOT ported: DLASSQ (only needed by DLANST's Frobenius/one-norm branches,
// which DSTERF's own call site -- DLANST('M',...) -- never reaches) and
// DLAMC3 (bundled alongside DLAMCH in the reference download, but this
// modern Fortran-90-intrinsics-based DLAMCH never actually calls it).
// Neither omission changes DSTERF's behavior for any input it can produce.
#include <cmath>
#include <cstdio>
#include <limits>
#include <stdexcept>

namespace dsterf_port {

// ---------------------------------------------------------------------------
// DISNAN(DIN) = DLAISNAN(DIN,DIN) = (DIN .NE. DIN). IEEE-754 comparison
// makes a value unequal to itself iff NaN -- this is exact under
// -fno-fast-math (required: -ffast-math's "assume no NaNs" would remove
// this comparison's meaning entirely, and the whole overflow/NaN-safety
// argument DLAPY2 and DLANST make below depends on it working).
// ---------------------------------------------------------------------------
static inline bool disnan(double din) { return din != din; }

// ---------------------------------------------------------------------------
// LSAME(CA,CB): case-insensitive single-character compare. Only the ASCII
// branch of the real LSAME is ported -- the real routine also has EBCDIC
// and Prime-machine branches, selected at runtime via ICHAR('Z'), that
// exist solely for non-ASCII character sets. On any AArch64 Android
// target ICHAR('Z')=90 always selects the ASCII branch, so the other two
// are dead code for this port's purpose; porting them would add branches
// that can never execute and can never be tested.
// ---------------------------------------------------------------------------
static inline bool lsame(char ca, char cb) {
    if (ca == cb) return true;
    unsigned char inta = static_cast<unsigned char>(ca);
    unsigned char intb = static_cast<unsigned char>(cb);
    if (inta >= 97 && inta <= 122) inta = static_cast<unsigned char>(inta - 32);
    if (intb >= 97 && intb <= 122) intb = static_cast<unsigned char>(intb - 32);
    return inta == intb;
}

// Fortran SIGN(A,B) = |A| if B>=0, else -|A|. Deliberately NOT
// std::copysign: copysign(1.0,-0.0) = -1.0 (sign-bit based), but Fortran's
// B.GE.ZERO test is true for B=-0.0 (numeric compare), so SIGN(A,-0.0) =
// +|A| -- copysign would silently disagree with DSTERF on that one edge
// case in the shift formula (SIGN(R,SIGMA) at line ~1580/1666 of the
// Fortran).
static inline double fsign(double a, double b) {
    return (b >= 0.0) ? std::fabs(a) : -std::fabs(a);
}

// ---------------------------------------------------------------------------
// DLAE2( A, B, C, RT1, RT2 ) -- closed-form eigenvalues of
//   [ A  B ]
//   [ B  C ]
// Transcribed exactly, including the "**2" terms as literal a*a (never
// std::pow(x,2), which is not guaranteed bit-identical to a direct
// multiply for every x) and the comment LAPACK's own authors left on the
// RT2 formula: order of execution is precision-load-bearing there, not
// stylistic, so it is kept exactly as written.
// ---------------------------------------------------------------------------
static void dlae2(double a, double b, double c, double* rt1, double* rt2) {
    const double one = 1.0, two = 2.0, zero = 0.0, half = 0.5;
    double sm = a + c;
    double df = a - c;
    double adf = std::fabs(df);
    double tb = b + b;
    double ab = std::fabs(tb);
    double acmx, acmn;
    if (std::fabs(a) > std::fabs(c)) { acmx = a; acmn = c; }
    else                             { acmx = c; acmn = a; }
    double rt;
    if (adf > ab) {
        rt = adf * std::sqrt(one + (ab / adf) * (ab / adf));
    } else if (adf < ab) {
        rt = ab * std::sqrt(one + (adf / ab) * (adf / ab));
    } else {
        // Includes case AB=ADF=0
        rt = ab * std::sqrt(two);
    }
    if (sm < zero) {
        *rt1 = half * (sm - rt);
        // Order of execution important (LAPACK's own comment, preserved):
        // to get a fully accurate smaller eigenvalue this needs evaluating
        // exactly as written, not as (ACMX*ACMN - B*B) / RT1.
        *rt2 = (acmx / *rt1) * acmn - (b / *rt1) * b;
    } else if (sm > zero) {
        *rt1 = half * (sm + rt);
        *rt2 = (acmx / *rt1) * acmn - (b / *rt1) * b;
    } else {
        // Includes case RT1 = RT2 = 0
        *rt1 = half * rt;
        *rt2 = -half * rt;
    }
}

// ---------------------------------------------------------------------------
// DLAMCH: only 'E' (eps), 'S' (safmin), 'O' (overflow threshold) are
// ported -- the only codes DSTERF's own call chain (DSTERF, DLASCL,
// DLAPY2) ever requests. This is the modern (Dec 2016+) DLAMCH, built on
// Fortran 90 intrinsics (EPSILON/TINY/HUGE), not the older iterative
// bit-detection algorithm -- so this maps directly onto <limits>, no
// approximation involved.
// ---------------------------------------------------------------------------
static double dlamch(char cmach) {
    const double one = 1.0;
    // RND = ONE always in this revision ("assume rounding, not chopping,
    // always"), so the ELSE branch (EPS = EPSILON(ZERO), unhalved) is
    // genuinely dead in the original too -- not a simplification here.
    double eps = std::numeric_limits<double>::epsilon() * 0.5;
    if (lsame(cmach, 'E')) {
        return eps;
    } else if (lsame(cmach, 'S')) {
        double sfmin = std::numeric_limits<double>::min();      // TINY(ZERO)
        double small = one / std::numeric_limits<double>::max(); // ONE/HUGE(ZERO)
        if (small >= sfmin) {
            // Use SMALL plus a bit, to avoid the possibility of rounding
            // causing overflow when computing 1/sfmin.
            sfmin = small * (one + eps);
        }
        return sfmin;
    } else if (lsame(cmach, 'O')) {
        return std::numeric_limits<double>::max(); // HUGE(ZERO)
    }
    // Every other CMACH code ('B','P','N','R','M','U','L') is never
    // requested anywhere in DSTERF's call chain; the real DLAMCH returns
    // 0 for any unrecognized code, matched here for the same reason.
    return 0.0;
}

// ---------------------------------------------------------------------------
// DLAPY2(X,Y): overflow-safe sqrt(x^2+y^2), with explicit NaN propagation.
// Note the exact NaN-precedence: if X is NaN, result is set to X; if Y is
// ALSO NaN, that assignment is then OVERWRITTEN by Y -- sequential, not
// "else if" -- so a double-NaN input returns Y's NaN, not X's. Preserved
// exactly, including that literal order.
// ---------------------------------------------------------------------------
static double dlapy2(double x, double y) {
    const double zero = 0.0, one = 1.0;
    bool x_is_nan = disnan(x);
    bool y_is_nan = disnan(y);
    double result = 0.0; // Fortran leaves DLAPY2 undefined until assigned;
                          // only reachable non-NaN path always assigns below.
    if (x_is_nan) result = x;
    if (y_is_nan) result = y;
    double hugeval = dlamch('O');
    if (!(x_is_nan || y_is_nan)) {
        double xabs = std::fabs(x);
        double yabs = std::fabs(y);
        double w = (xabs > yabs) ? xabs : yabs;
        double z = (xabs < yabs) ? xabs : yabs;
        if (z == zero || w > hugeval) {
            result = w;
        } else {
            result = w * std::sqrt(one + (z / w) * (z / w));
        }
    }
    return result;
}

// ---------------------------------------------------------------------------
// DLANST('M', n, d, e): max(|d_i|, |e_i|), NaN-propagating. 0-based array
// access -- this is called from dsterf_cpp with an already-offset pointer
// (e.g. &d[l]), which is exactly how Fortran's own D(L) actual-argument
// passing works: it hands the callee the address of element L and the
// callee re-indexes from 1 there. Only the 'M' branch is ported (see the
// file header note on DLASSQ) -- DSTERF's one call site always passes 'M'
// literally, never anything else.
// ---------------------------------------------------------------------------
static double dlanst_m(int n, const double* d, const double* e) {
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

// ---------------------------------------------------------------------------
// DLASCL: multiply an M x N matrix by CTO/CFROM, computed via a safe
// iterative scaling loop (never forming CTO/CFROM directly, to avoid
// intermediate overflow/underflow). Full port: all 7 ITYPE branches and
// full argument validation, even though DSTERF's own calls only ever use
// TYPE='G' (ITYPE=0) with N=1 -- porting the whole routine rather than
// just the exercised branch avoids making a judgment call about what
// counts as "needed" that the Fortran source itself doesn't make.
// A is 0-based, column-major (A(i,j) at a[(j)*lda + i], matching
// Fortran's storage order), for the same offset-pointer reason as above.
// ---------------------------------------------------------------------------
static void dlascl(char type, int kl, int ku, double cfrom, double cto,
                    int m, int n, double* a, int lda, int* info) {
    const double zero = 0.0, one = 1.0;
    *info = 0;
    int itype;
    if (lsame(type, 'G'))      itype = 0;
    else if (lsame(type, 'L')) itype = 1;
    else if (lsame(type, 'U')) itype = 2;
    else if (lsame(type, 'H')) itype = 3;
    else if (lsame(type, 'B')) itype = 4;
    else if (lsame(type, 'Q')) itype = 5;
    else if (lsame(type, 'Z')) itype = 6;
    else itype = -1;

    if (itype == -1) {
        *info = -1;
    } else if (cfrom == zero || disnan(cfrom)) {
        *info = -4;
    } else if (disnan(cto)) {
        *info = -5;
    } else if (m < 0) {
        *info = -6;
    } else if (n < 0 || (itype == 4 && n != m) || (itype == 5 && n != m)) {
        *info = -7;
    } else if (itype <= 3 && lda < (1 > m ? 1 : m)) {
        *info = -9;
    } else if (itype >= 4) {
        if (kl < 0 || kl > (m - 1 > 0 ? m - 1 : 0)) {
            *info = -2;
        } else if (ku < 0 || ku > (n - 1 > 0 ? n - 1 : 0) ||
                   ((itype == 4 || itype == 5) && kl != ku)) {
            *info = -3;
        } else if ((itype == 4 && lda < kl + 1) ||
                   (itype == 5 && lda < ku + 1) ||
                   (itype == 6 && lda < 2 * kl + ku + 1)) {
            *info = -9;
        }
    }

    if (*info != 0) {
        // XERBLA('DLASCL', -INFO) in the reference -- see xerbla() note.
        // Every path that can set INFO!=0 here is a caller-error path (bad
        // TYPE string, negative dimension); DSTERF's own calls into this
        // routine never hit one for valid n.
        throw std::invalid_argument("DLASCL: illegal argument");
    }

    if (n == 0 || m == 0) return;

    double smlnum = dlamch('S');
    double bignum = one / smlnum;

    double cfromc = cfrom;
    double ctoc = cto;
    bool done;
    double mul;
    for (;;) {
        double cfrom1 = cfromc * smlnum;
        double cto1;
        if (cfrom1 == cfromc) {
            // CFROMC is an inf. Multiply by a correctly signed zero for
            // finite CTOC, or a NaN if CTOC is infinite.
            mul = ctoc / cfromc;
            done = true;
            cto1 = ctoc; // (unused beyond this branch, kept for fidelity)
            (void)cto1;
        } else {
            cto1 = ctoc / bignum;
            if (cto1 == ctoc) {
                // CTOC is either 0 or an inf: CTOC itself is the correct
                // multiplication factor.
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
            for (int j = 0; j < n; j++)
                for (int i = 0; i < m; i++)
                    a[j * lda + i] *= mul;
        } else if (itype == 1) {
            for (int j = 0; j < n; j++)
                for (int i = j; i < m; i++)
                    a[j * lda + i] *= mul;
        } else if (itype == 2) {
            for (int j = 0; j < n; j++) {
                int top = (j + 1 < m) ? (j + 1) : m; // MIN(J+1,M) in 1-based == MIN(J,M-1)+1 in 0-based rows
                for (int i = 0; i < top; i++)
                    a[j * lda + i] *= mul;
            }
        } else if (itype == 3) {
            for (int j = 0; j < n; j++) {
                int top = (j + 2 < m) ? (j + 2) : m; // MIN(J+1,M) in Fortran 1-based -> j+2 bound in 0-based+1
                for (int i = 0; i < top; i++)
                    a[j * lda + i] *= mul;
            }
        } else if (itype == 4) {
            int k3 = kl + 1;
            int k4 = n + 1;
            for (int j = 1; j <= n; j++) {
                int lim = k3 < (k4 - j) ? k3 : (k4 - j);
                for (int i = 1; i <= lim; i++)
                    a[(j - 1) * lda + (i - 1)] *= mul;
            }
        } else if (itype == 5) {
            int k1 = ku + 2;
            int k3 = ku + 1;
            for (int j = 1; j <= n; j++) {
                int lo = (k1 - j > 1) ? (k1 - j) : 1;
                for (int i = lo; i <= k3; i++)
                    a[(j - 1) * lda + (i - 1)] *= mul;
            }
        } else if (itype == 6) {
            int k1 = kl + ku + 2;
            int k2 = kl + 1;
            int k3 = 2 * kl + ku + 1;
            int k4 = kl + ku + 1 + m;
            for (int j = 1; j <= n; j++) {
                int lo = (k1 - j > k2) ? (k1 - j) : k2;
                int hi = (k3 < (k4 - j)) ? k3 : (k4 - j);
                for (int i = lo; i <= hi; i++)
                    a[(j - 1) * lda + (i - 1)] *= mul;
            }
        }

        if (!done) continue;
        return;
    }
}

// ---------------------------------------------------------------------------
// DLASRT('I', n, d): non-recursive quicksort (median-of-3 pivot, explicit
// 32-deep stack, matching the reference's own SELECT=20 insertion-sort
// threshold) into increasing order. Both DIR branches ported; DSTERF only
// ever calls with ID='I' (DIR=1). 0-based, offset-pointer convention as
// above (DSTERF passes its own d[1..n] array directly, 1-based, so the
// caller here is DSTERF's own 1-based array with index 0 unused --
// handled by the caller, not by this routine, exactly like Fortran does).
// ---------------------------------------------------------------------------
static void dlasrt(char id, int n, double* d, int* info) {
    const int select = 20;
    *info = 0;
    int dir = -1;
    if (lsame(id, 'D')) dir = 0;
    else if (lsame(id, 'I')) dir = 1;
    if (dir == -1) *info = -1;
    else if (n < 0) *info = -2;
    if (*info != 0) throw std::invalid_argument("DLASRT: illegal argument");

    if (n <= 1) return;

    // 1-based stack and indices, exactly mirroring the Fortran (STACK(2,32),
    // START/ENDD 1-based into d[0..n-1] treated as if it were D(1..N)).
    int stack[2][32];
    int stkpnt = 1;
    stack[0][0] = 1;
    stack[1][0] = n;

    for (;;) {
        int start = stack[0][stkpnt - 1];
        int endd = stack[1][stkpnt - 1];
        stkpnt = stkpnt - 1;

        if (endd - start <= select && endd - start > 0) {
            // Insertion sort on D(START:ENDD), 1-based -> d[start-1..endd-1]
            if (dir == 0) {
                for (int i = start + 1; i <= endd; i++) {
                    for (int j = i; j >= start + 1; j--) {
                        if (d[j - 1] > d[j - 2]) {
                            double tmp = d[j - 1]; d[j - 1] = d[j - 2]; d[j - 2] = tmp;
                        } else {
                            break;
                        }
                    }
                }
            } else {
                for (int i = start + 1; i <= endd; i++) {
                    for (int j = i; j >= start + 1; j--) {
                        if (d[j - 1] < d[j - 2]) {
                            double tmp = d[j - 1]; d[j - 1] = d[j - 2]; d[j - 2] = tmp;
                        } else {
                            break;
                        }
                    }
                }
            }
        } else if (endd - start > select) {
            double d1 = d[start - 1];
            double d2 = d[endd - 1];
            int mid = (start + endd) / 2;
            double d3 = d[mid - 1];
            double dmnmx;
            if (d1 < d2) {
                if (d3 < d1) dmnmx = d1;
                else if (d3 < d2) dmnmx = d3;
                else dmnmx = d2;
            } else {
                if (d3 < d2) dmnmx = d2;
                else if (d3 < d1) dmnmx = d3;
                else dmnmx = d1;
            }

            int i = start - 1;
            int j = endd + 1;
            if (dir == 0) {
                for (;;) {
                    do { j = j - 1; } while (d[j - 1] < dmnmx);
                    do { i = i + 1; } while (d[i - 1] > dmnmx);
                    if (i < j) {
                        double tmp = d[i - 1]; d[i - 1] = d[j - 1]; d[j - 1] = tmp;
                        continue;
                    }
                    break;
                }
            } else {
                for (;;) {
                    do { j = j - 1; } while (d[j - 1] > dmnmx);
                    do { i = i + 1; } while (d[i - 1] < dmnmx);
                    if (i < j) {
                        double tmp = d[i - 1]; d[i - 1] = d[j - 1]; d[j - 1] = tmp;
                        continue;
                    }
                    break;
                }
            }
            if (j - start > endd - j - 1) {
                stkpnt = stkpnt + 1; stack[0][stkpnt - 1] = start; stack[1][stkpnt - 1] = j;
                stkpnt = stkpnt + 1; stack[0][stkpnt - 1] = j + 1; stack[1][stkpnt - 1] = endd;
            } else {
                stkpnt = stkpnt + 1; stack[0][stkpnt - 1] = j + 1; stack[1][stkpnt - 1] = endd;
                stkpnt = stkpnt + 1; stack[0][stkpnt - 1] = start; stack[1][stkpnt - 1] = j;
            }
        }
        if (stkpnt <= 0) break;
    }
}

// ---------------------------------------------------------------------------
// XERBLA(SRNAME, INFO): the one routine that cannot be a faithful port.
// The reference prints a diagnostic and calls Fortran STOP, terminating
// the process -- correct for a standalone numerical program, wrong for a
// routine embedded in a larger Android app, where a single bad-parameter
// call must not kill the process. The diagnostic text is reproduced
// exactly; STOP is replaced with a thrown exception. This only executes
// on caller/programmer errors (bad TYPE character, negative dimension) --
// it is never on the numerical path bit-for-bit validation exercises.
// ---------------------------------------------------------------------------
static void xerbla(const char* srname, int info) {
    std::fprintf(stderr, " ** On entry to %6s parameter number %2d had an illegal value\n", srname, info);
    throw std::invalid_argument(std::string("XERBLA: ") + srname);
}

// ---------------------------------------------------------------------------
// DSTERF(N, D, E, INFO). 1-based arrays throughout (d[0]/e[0] unused) --
// matching the Fortran D(1..N)/E(1..N-1) directly avoids introducing any
// off-by-one translation risk in a control-flow-heavy routine. Labels are
// named L<n> after the Fortran statement label they replace; every GOTO
// in the reference has a corresponding goto here, in the same place, to
// the same label -- no branch has been restructured into a "cleaner"
// loop shape.
// ---------------------------------------------------------------------------
void dsterf_cpp(int n, double* d /* 1-based, size n+1 */, double* e /* 1-based, size n+1 */, int* info) {
    const double zero = 0.0, one = 1.0, two = 2.0, three = 3.0;
    const int maxit = 30;

    int i, iscale, jtot, l, l1, lend, lendsv, lsv, m, nmaxit;
    double alpha, anorm = 0.0, bb, c, eps, eps2, gamma, oldc, oldgam,
           p, r, rt1, rt2, rte, s, safmax, safmin, sigma, ssfmax, ssfmin, rmax;

    *info = 0;

    if (n < 0) {
        *info = -1;
        xerbla("DSTERF", -(*info));
        return;
    }
    if (n <= 1) return;

    eps = dlamch('E');
    eps2 = eps * eps;
    safmin = dlamch('S');
    safmax = one / safmin;
    ssfmax = std::sqrt(safmax) / three;
    ssfmin = std::sqrt(safmin) / eps2;
    rmax = dlamch('O');
    (void)rmax; // referenced in the reference's declarations; never read after assignment there either

    nmaxit = n * maxit;
    sigma = zero;
    jtot = 0;

    l1 = 1;

L10:
    if (l1 > n) goto L170;
    if (l1 > 1) e[l1 - 1] = zero;
    for (m = l1; m <= n - 1; m++) {
        if (std::fabs(e[m]) <= (std::sqrt(std::fabs(d[m])) * std::sqrt(std::fabs(d[m + 1]))) * eps) {
            e[m] = zero;
            goto L30;
        }
    }
    m = n;

L30:
    l = l1;
    lsv = l;
    lend = m;
    lendsv = lend;
    l1 = m + 1;
    if (lend == l) goto L10;

    // Scale submatrix in rows and columns L to LEND
    anorm = dlanst_m(lend - l + 1, &d[l], &e[l]);
    iscale = 0;
    if (anorm == zero) goto L10;
    if (anorm > ssfmax) {
        iscale = 1;
        dlascl('G', 0, 0, anorm, ssfmax, lend - l + 1, 1, &d[l], n, info);
        dlascl('G', 0, 0, anorm, ssfmax, lend - l, 1, &e[l], n, info);
    } else if (anorm < ssfmin) {
        iscale = 2;
        dlascl('G', 0, 0, anorm, ssfmin, lend - l + 1, 1, &d[l], n, info);
        dlascl('G', 0, 0, anorm, ssfmin, lend - l, 1, &e[l], n, info);
    }

    for (i = l; i <= lend - 1; i++) e[i] = e[i] * e[i];

    // Choose between QL and QR iteration
    if (std::fabs(d[lend]) < std::fabs(d[l])) {
        lend = lsv;
        l = lendsv;
    }

    if (lend >= l) {
        // ---- QL Iteration ----
    L50:
        if (l != lend) {
            for (m = l; m <= lend - 1; m++) {
                if (std::fabs(e[m]) <= eps2 * std::fabs(d[m] * d[m + 1])) goto L70;
            }
        }
        m = lend;

    L70:
        if (m < lend) e[m] = zero;
        p = d[l];
        if (m == l) goto L90;

        if (m == l + 1) {
            rte = std::sqrt(e[l]);
            dlae2(d[l], rte, d[l + 1], &rt1, &rt2);
            d[l] = rt1;
            d[l + 1] = rt2;
            e[l] = zero;
            l = l + 2;
            if (l <= lend) goto L50;
            goto L150;
        }

        if (jtot == nmaxit) goto L150;
        jtot = jtot + 1;

        rte = std::sqrt(e[l]);
        sigma = (d[l + 1] - p) / (two * rte);
        r = dlapy2(sigma, one);
        sigma = p - (rte / (sigma + fsign(r, sigma)));

        c = one;
        s = zero;
        gamma = d[m] - sigma;
        p = gamma * gamma;

        for (i = m - 1; i >= l; i--) {
            bb = e[i];
            r = p + bb;
            if (i != m - 1) e[i + 1] = s * r;
            oldc = c;
            c = p / r;
            s = bb / r;
            oldgam = gamma;
            alpha = d[i];
            gamma = c * (alpha - sigma) - s * oldgam;
            d[i + 1] = oldgam + (alpha - gamma);
            if (c != zero) p = (gamma * gamma) / c;
            else           p = oldc * bb;
        }

        e[l] = s * p;
        d[l] = sigma + gamma;
        goto L50;

    L90:
        d[l] = p;
        l = l + 1;
        if (l <= lend) goto L50;
        goto L150;

    } else {
        // ---- QR Iteration ----
    L100:
        for (m = l; m >= lend + 1; m--) {
            if (std::fabs(e[m - 1]) <= eps2 * std::fabs(d[m] * d[m - 1])) goto L120;
        }
        m = lend;

    L120:
        if (m > lend) e[m - 1] = zero;
        p = d[l];
        if (m == l) goto L140;

        if (m == l - 1) {
            rte = std::sqrt(e[l - 1]);
            dlae2(d[l], rte, d[l - 1], &rt1, &rt2);
            d[l] = rt1;
            d[l - 1] = rt2;
            e[l - 1] = zero;
            l = l - 2;
            if (l >= lend) goto L100;
            goto L150;
        }

        if (jtot == nmaxit) goto L150;
        jtot = jtot + 1;

        rte = std::sqrt(e[l - 1]);
        sigma = (d[l - 1] - p) / (two * rte);
        r = dlapy2(sigma, one);
        sigma = p - (rte / (sigma + fsign(r, sigma)));

        c = one;
        s = zero;
        gamma = d[m] - sigma;
        p = gamma * gamma;

        for (i = m; i <= l - 1; i++) {
            bb = e[i];
            r = p + bb;
            if (i != m) e[i - 1] = s * r;
            oldc = c;
            c = p / r;
            s = bb / r;
            oldgam = gamma;
            alpha = d[i + 1];
            gamma = c * (alpha - sigma) - s * oldgam;
            d[i] = oldgam + (alpha - gamma);
            if (c != zero) p = (gamma * gamma) / c;
            else           p = oldc * bb;
        }

        e[l - 1] = s * p;
        d[l] = sigma + gamma;
        goto L100;

    L140:
        d[l] = p;
        l = l - 1;
        if (l >= lend) goto L100;
        goto L150;
    }

L150:
    if (iscale == 1)
        dlascl('G', 0, 0, ssfmax, anorm, lendsv - lsv + 1, 1, &d[lsv], n, info);
    if (iscale == 2)
        dlascl('G', 0, 0, ssfmin, anorm, lendsv - lsv + 1, 1, &d[lsv], n, info);

    if (jtot < nmaxit) goto L10;
    for (i = 1; i <= n - 1; i++) {
        if (e[i] != zero) *info = *info + 1;
    }
    goto L180;

L170:
    dlasrt('I', n, &d[1], info);

L180:
    return;
}

} // namespace dsterf_port
