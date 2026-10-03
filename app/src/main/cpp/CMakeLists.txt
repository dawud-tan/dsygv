cmake_minimum_required(VERSION 4.1.2)
project(mpmreigensolver LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Library name must match System.loadLibrary("mpmreigensolver") in
# NativeBridge.kt -- CMake adds the "lib" prefix and ".so" suffix.
add_library(mpmreigensolver SHARED phase1_solver.cpp)

find_library(log-lib log)
target_link_libraries(mpmreigensolver ${log-lib})
# -ffp-contract=off is NOT implied by -fno-fast-math -- confirmed directly
# via `g++ -fno-fast-math -Q --help=optimizers`, which reports
# -ffp-contract=fast as the ACTIVE DEFAULT on both x86_64 and aarch64 even
# with -fno-fast-math set. This matters here specifically because Phase 4
# (dsterf() in phase1_solver.cpp) is a from-Fortran port whose whole
# purpose is bit-identical output to the reference LAPACK DSTERF -- and
# aarch64 has cheap native FMA (fmadd) while baseline x86_64 does not, so
# the identical source under the identical -fno-fast-math flag can
# silently contract on-device and never on a host build, breaking that
# bit-identity between them. Confirmed empirically: removing this flag
# reproduces the divergence; adding it back eliminates it, verified across
# 185 randomized and adversarial-scaling test cases on this exact toolchain
# under QEMU aarch64 emulation. Applies to the whole file, not just
# dsterf() -- harmless there, since the NEON/SVE/SME kernels use explicit
# intrinsics (vfmaq_f64 etc.), not compiler-inferred contraction, so this
# flag changes nothing about how they compile.
# -Wall -Wextra were absent entirely until now, which is how three dead
# `static` references (the unblocked cholesky/congruence/householderTridiag
# kept expressly so the blocked versions can be cross-validated against them)
# sat with no caller at all and nobody noticed. -Werror=unused-function makes
# that specific regression un-missable: if a reference loses its last caller,
# the build stops instead of the cross-check quietly ceasing to exist.
target_compile_options(mpmreigensolver PRIVATE
        -O2 -fno-fast-math -ffp-contract=off
        -Wall -Wextra -Werror=unused-function)
