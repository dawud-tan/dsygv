#!/bin/bash
# Bit-comparison of the PRODUCTION lapack_port::dsterf (inside
# app/src/main/cpp/phase1_solver.cpp) against reference LAPACK DSTERF built
# with gfortran. 185 cases; any differing output byte is a hard failure.
#
# The sibling claude_intermediate_code/ harness tests the standalone
# intermediate port instead. That one cannot catch a regression in the code
# that actually ships -- this one can, which is why it exists separately.
#
# Needs: gfortran, python3, the NDK clang, qemu-aarch64-static.
# aarch64 is not optional: -ffp-contract=off exists because the x86_64 and
# aarch64 builds diverged without it, so bit-identity must be shown on the
# ISA that actually ships.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"; cd "$HERE"
NDK=${NDK:-/WIN_D/Android-Linux/Sdk/ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64}

# dsterf_reference.f is input/dsterf.f with the free-form DLASSQ excised and
# replaced by an aborting stub: DSTERF only ever calls DLANST('M'), which
# never reaches DLASSQ, and the mixed fixed/free source form otherwise will
# not compile as one fixed-form unit.
gfortran -O2 -ffp-contract=off -o dsterf_ref dsterf_reference.f ../claude_intermediate_code/driver.f

$NDK/bin/clang++ --target=aarch64-none-linux-android26 -static -O2 -fno-fast-math \
  -ffp-contract=off -std=c++23 -Wall -Wextra prod_driver.cpp -o prod_drv

printf '#!/bin/bash\nexec qemu-aarch64-static -cpu ${QCPU:-max} "%s/prod_drv" "$@"\n' "$HERE" > prod_sh
chmod +x prod_sh
CPP=./prod_sh python3 compare_harness.py
