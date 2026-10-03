# Griya Sakha — Seismic Modal Analysis

Kotlin structural-analysis code for a real single-story reinforced-concrete
house in Sumber, Jatipohon, Grobogan, Central Java. Builds the stiffness and
mass matrices, condenses them, and runs SNI 1726 / ASCE 7 code checks.

In production the eigenvalues come from a **separately verified C++ solver**
reached over JNI (see `NativeBridge.kt`). The Jacobi solver inside
`Verification.kt` exists only so the harness can run standalone.

The engineering report for the native solver exists in two synced forms:
`README.md` and `eigensolver-neon-sve-sme.html` (a standalone page with its
own styling, published at the canonical URL in its `<link rel="canonical">`).
Same 15 sections, same content — the phase-by-phase account of where
NEON/SVE/SME actually pay off, which of the four hot kernels is a genuine
matrix-engine target and which are not, and the DSTERF verification trail.
Read it before touching `phase1_solver.cpp`, and **update both** if the
solver's phase structure changes.

**This repo is the Android app** the home owner uses to consult, confront and
discuss the build with the contractor, plus the shared Kotlin analysis behind it.

**The site plan is not here.** Choosing the parcel, the GNSS baseline, the
total-station setout onto the qibla, and the solar gnomons that read Dhuhr, Asr
and Maghrib off the sun are one end-to-end page in the **quarkus** repo,
`src/main/resources/META-INF/resources/qibla-setout-field-sheet.html`, served at
<http://183.81.158.231:8080/qibla-setout-field-sheet.html>. The gnomon plan used to be
`griya-sakha-gnomons.html` at this repo's root; on 2026-09-24 it was merged into
that page and deleted here. Do not recreate it. `setout/` still lives here and
still pins that page's numbers: see **Site setout and the solar gnomons** below.

## Repository layout

The Gradle project **is** the repository root (it used to live in a nested
`android/` directory — that move is done, do not look for it).

```
build.gradle.kts, settings.gradle.kts, gradle.properties
gradle/libs.versions.toml            <- version catalog
gradlew, gradlew.bat, gradle/wrapper/ <- wrapper IS committed (Gradle 9.7.1)
src/                                 <- shared Kotlin, package griyasakha (+ NativeBridge.kt)
app/build.gradle.kts                 <- kotlin.directories.add("../src")
app/src/main/AndroidManifest.xml
app/src/main/java/com/dawud/mpmrbench/{MainActivity,StructureView}.kt
app/src/main/cpp/{CMakeLists.txt, phase1_solver.cpp}
app/src/androidTest/java/com/dawud/mpmrbench/DeviceVerificationTest.kt
README.md                            <- native solver report
setout/                              <- qibla stakeout + solar gnomons; NOT part of the Gradle build
                                        (its plan is the quarkus repo's qibla-setout-field-sheet.html)
setout/out/                          <- generated SO-01..03 DXF + PDF (sheets.py); git-ignored
```

The APK does **not** duplicate the Kotlin sources: `app/build.gradle.kts`
points its main source set at `../src`, so the host harness and the APK
compile the same files. Do not copy sources into `app/` — they will drift.

`MainActivity.kt`, `StructureView.kt` and `DeviceVerificationTest.kt` live
under `app/`, not `src/`, because they import `android.*` and would break the
host build.

Several files under `src/` carry a `fun main()` used by the host harness.
They compile into the APK as unreachable code and are stripped by R8 in
release builds. Harmless — do not delete them to "clean up" the APK, they
are how the host verification runs.

## Build and verify

Two paths. Use the fast one for iteration, the device one before trusting
any number.

**Host (fast, no device, ~1 min).** Pure-JVM logic only:

```bash
kotlinc src/*.kt -include-runtime -d build/griya.jar
java -cp build/griya.jar griyasakha.VerificationKt
```

That compiles every source under `src/` and runs `griyasakha.VerificationKt`,
which asserts **126** invariants in 17 sections and **exits non-zero on any
failure**. Tested in both directions: injecting a known bug makes it fail,
removing it makes it pass. Requires `kotlinc` and a JDK on `PATH`. This path
does **not** exercise the native solver at all — it uses a JVM Jacobi
reference instead.

> **`verify.sh` does not exist in the tree.** It is still referenced by
> `.claude/agents/test-runner.md`, by the comment in `app/build.gradle.kts`,
> and by the header of `src/Verification.kt`. Those references are dangling.
> Either restore the script (the two commands above are its whole body) or
> fix the references — don't assume `./verify.sh` will run.

**Device (Samsung SM-G980F over ADB).** The only path that exercises the real
C++ eigensolver. Run from the repository root:

```bash
adb devices                          # must list the phone as "device"
./gradlew connectedAndroidTest       # runs the same 126 checks ON the phone
./gradlew installDebug               # or: adb install -r app/build/outputs/apk/debug/mpmr.apk
adb logcat -s MPMR_PHASE1            # native-side diagnostics
```

Two details that bite:

- **The APK is named `mpmr.apk`, not `app-debug.apk`.** `androidComponents`
  overrides `outputFileName` for every variant, so the debug artifact is
  `app/build/outputs/apk/debug/mpmr.apk`.
- **The logcat tag is `MPMR_PHASE1`**, set by `#define TAG` in
  `phase1_solver.cpp`. It is not the library name — `-s mpmreigensolver`
  matches nothing.

`connectedAndroidTest` runs `DeviceVerificationTest` (6 tests): the whole
harness on-device, native library load, native-vs-JVM cross-check (residual,
mass-normalization, eigenvalues), and both halves of the rejection contract.
**A green host run means nothing about the JNI layer** — that code path is
not executed at all until it runs on the phone.

**Release builds.** `assembleRelease` no longer fails without a keystore: it
produces an *unsigned* APK. Supply `MPMR_KEYSTORE_FILE`,
`MPMR_KEYSTORE_PASSWORD`, `MPMR_KEYSTORE_KEY_ALIAS` and
`MPMR_KEYSTORE_KEY_PASSWORD` as Gradle properties or environment variables to
get a signed one (v1–v4 signing all enabled).

## Toolchain

| Thing | Version | Where |
|---|---|---|
| Gradle | 9.7.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Daemon JVM toolchain | 25 | `gradle/gradle-daemon-jvm.properties` |
| AGP | 9.4.0 | `gradle/libs.versions.toml` |
| Kotlin | 2.4.20 | `gradle/libs.versions.toml` |
| compileSdk / targetSdk | 37 (minor 1) / 37 | `app/build.gradle.kts` |
| minSdk | 26 | `app/build.gradle.kts` |
| NDK | 30.0.16248370 | `app/build.gradle.kts` |
| CMake | 4.1.2 | `app/build.gradle.kts`, `CMakeLists.txt` |
| Kotlin language level / jvmTarget | `KOTLIN_2_4` / 17 | `app/build.gradle.kts` |

`libs.plugins.kotlin.android` is declared `apply false` in the root
`build.gradle.kts` and **must stay that way** — AGP 9 hard-errors on the
Kotlin Android plugin. The `apply false` alias exists only to pin the KGP on
the buildscript classpath so AGP's built-in Kotlin uses it and
`KotlinVersion.KOTLIN_2_4` resolves. The comment in that file still says
"2.4.10"; the catalog actually pins **2.4.20**.

## Unit system — read this before touching anything numeric

Base units are **kN, m, s**. In that system the consistent mass unit is the
**megagram (tonne, Mg)**, *not* the kilogram, because `kN = Mg·m/s²`.

- `K` is in **kN/m**
- `M` is in **Mg**
- `ω² = K/M` then comes out in rad²/s² with no correction factor

Callers pass mass in **kg** (natural for densities and floor loads) and
`ModelAssembly.addMass()` converts once, at that single choke point
(`KG_TO_MG`). A kg/Mg mixup inflates every period by `√1000 ≈ 31×` — measured
directly: forcing `KG_TO_MG = 1.0` moves `T1` from 0.0886 s to 2.8008 s, a
factor of 31.6. This was a real bug that survived a long time because the
number *looked* plausible in isolation. If periods ever come out ~31× too
long, look here first.

(The comment on `addMass()` says "~21x". That number is wrong — it appears to
have picked up the harness's *T1/Ta ratio* rather than the error factor.
Harmless, but don't calibrate off it.)

## Architecture

| File | Role |
|---|---|
| `src/Geometry.kt` | 24-node grid (12 fixed base + 12 roof), DOF assignment, `DOF_PER_NODE`/`MASTER_DOF_PER_NODE`, `Node.reducedUx/reducedUy` |
| `src/Materials.kt` | Concrete K250, AAC/brick masonry, section properties |
| `src/ElementStiffness.kt` | 3D frame element, 12×12 local K + global transform; `matMul`/`matVec`/`transpose` helpers |
| `src/DiagonalStrut.kt` | Mainstone/FEMA-356 infill strut, axial-only |
| `src/Assembly.kt` | Global K and M assembly, tributary mass distribution |
| `src/GuyanReduction.kt` | Static condensation, 72 → 24 DOF. `Result` carries the expansion operator `tSM` and `expand()` back to full DOF — load-bearing for force recovery |
| `src/EjmlStandin.kt` | SimpleMatrix-compatible stand-in — see note below |
| `src/EigensolverBridge.kt` | `Eigenpair`, `EigensystemResult.unpack()`, `EigensolverSupport` — pure Kotlin, package `griyasakha`, no JNI |
| `src/NativeBridge.kt` | The actual JNI object, `object EigensolverBridge` in package `com.dawud.mpmrbench` |
| `src/SeismicParams.kt` | Hazard parameters, elastic `sa()` and R-reduced `saReduced()` |
| `src/MPMR.kt` | Modal participating mass ratios |
| `src/CQC.kt` | CQC modal combination, base shear, 12.9.4 scaling |
| `src/CodeChecks.kt` | Drift, P-Delta, torsional irregularity |
| `src/ForceRecovery.kt` | Element end forces from a displacement field; per-mode recovery then CQC on the FORCES |
| `src/SectionCapacity.kt` | SNI 2847 section strength — P-M interaction by strain compatibility, shear, tie-spacing limits. Works in **N and mm** internally |
| `src/DesignVariant.kt` | One parameterized description of what got built; `AS_SPECIFIED` reproduces the spec bit-exactly |
| `src/Assessment.kt` | Demand/capacity per member, plus variant comparison |
| `src/SiteChecklist.kt` | Site-inspection checklist generated from the variant + grid |
| `src/QuantityTakeoff.kt` | Bill of quantities re-derived from `Grid` (NOT from `asm.elements` — independence is the point), plus `reconcileLumpedMass()` against the assembled `M` |
| `src/Verification.kt` | Assertion harness — `runAllChecks()`, `solveAllModesForTest()`; not shipped to the app |
| `src/Ahsp.kt` | Published AHSP coefficients (SNI 7394:2008, SNI 2837:2008, Permen PUPR 1/2022) per takeoff line, the owner's price file, strict number parsing. **No prices in code** |
| `src/RabComparison.kt` | Contractor RAB vs takeoff + AHSP reference: volume gap, price gap, exact volume/price split of the money gap, flags |
| `src/BoqWorkbook.kt` | The owner's bill of quantities as `.xlsx` for the contractor to price, and the strict reader that feeds their filled copy back to the RAB page. Hand-rolled zip + XML, no dependency |
| `app/.../MainActivity.kt` | On-device viewer. One button cycles six pages: 3D → Report → Bill → RAB → Checks → Site |
| `app/.../StructureView.kt` | Plain Canvas `View` doing the drawing (no Compose, no GL — deliberate) |
| `app/.../DeviceVerificationTest.kt` | Instrumented tests, 6 of them |

Note the split: **`EigensolverBridge.kt` holds no JNI at all.** The
`external fun` lives in `NativeBridge.kt`, whose `object EigensolverBridge`
sits in `com.dawud.mpmrbench` for the symbol-name reason below.

**Conventions.** DOF order per node is `[Ux, Uy, Uz, Rx, Ry, Rz]`. After
Guyan reduction the layout is `[Ux, Uy, Ux, Uy, …]` — masters only. Fixed
base nodes get **no DOF at all**; they are skipped during assembly rather
than assembled and then constrained. Eigenvectors are **mass-normalized**
(`φᵀMφ = 1`), which is why effective-modal-mass formulas here have no
denominator. Unit-roof normalization was tried and rejected: it divides by a
coordinate not guaranteed to have healthy amplitude in every mode.

**Mapping a full-DOF node onto the reduced layout** goes through
`Node.reducedUx` / `Node.reducedUy`, backed by the `DOF_PER_NODE = 6` and
`MASTER_DOF_PER_NODE = 2` constants. The hand-inlined `dofStart / 6 * 2` that
used to appear in `CodeChecks.kt` is gone — do not reintroduce it. The
`require(hasDof)` inside `reducedUx` is load-bearing: a fixed base node has
`dofStart = -1`, and `-1 / 6 * 2 == 0` in Kotlin, which would silently alias
every base node onto the first roof node's Ux.

`EjmlStandin.kt` is a minimal stand-in with the same API surface as
`org.ejml.simple.SimpleMatrix`, and it is **kept deliberately** — it is
exercised by all 126 passing checks, so the build needs no EJML dependency at
all, one less moving part. Switching to real EJML is optional: uncomment the
dependency in `app/build.gradle.kts`, delete `src/EjmlStandin.kt`, change the
one import at the top of `src/GuyanReduction.kt`. No other line changes.

## What has already been verified, and how

Stated so you can judge whether each check was *adequate* — not so you skip
them. If a verification method looks weak to you, say so.

- **Frame element** — matches three closed-form cantilever solutions exactly
  (`PL/EA`, `PL³/3EIz`, `PL³/3EIy`), plus rigid-body-mode checks.
- **Rigid-body kinematics** — axial and torsion are "same value at both
  ends", but a rigid *rotation* about node 1 displaces node 2 by `θ·L`
  transversely, with **opposite sign** between the y and z bending planes.
  Getting this wrong produces a false failure on correct code. It did, once.
- **Guyan reduction** — reproduces `3EI/L³` exactly on a single global-frame
  column, matches a direct 6×6 solve, and mass is conserved through the
  reduction (37.782 Mg both sides).
- **Assembly** — global K is positive definite across all 72 DOF, which is
  the correct integrity invariant for a *fixed-base* structure. Rigid-body
  translation of the free DOF is **not** a zero-energy mode here, because
  the base does not move with it.
- **Wall orientation** — `Ky > Kx` (325418 vs 207653 kN/m), since the shear
  walls run along Y.
- **Modal solve** — all 24 modes residual-checked; worst relative residual on
  the JVM reference is 2.36e-14, asserted below 1e-13.
- **MPMR** — sums to exactly 1.0 in both directions (completeness), and
  reproduces a known 2-DOF golden-ratio reference (0.9472 / 0.0528). 90% mass
  needs 3 modes in X but **10 in Y**.
- **CQC** — `ρ(self)=1`, decays for separated modes, symmetric.
- **Spectrum** — `Sa(T)` continuous at `T0` and `TS`, and no modal period
  exceeds `TS` (0.0886 s vs 0.7342 s), which is what keeps the unimplemented
  `T_L` branch provably unreachable rather than merely untested.
- **Force/displacement consistency** — `V_CQC` and `V_ELF` are asserted to sit
  within a sane band of each other (24.9 vs 38.1 kN, ratio 0.654), and the
  ASCE 7 §12.9.4 scale factor computed from real model values is finite and
  ≥ 1 (1.2999). This is the check whose absence let the elastic/reduced
  spectrum mismatch survive.

Real bugs found and fixed so far: shear walls modeled horizontally instead of
vertically; strong/weak wall bending axes swapped; the kg/Mg unit mixup; wall
torsion `J` scaling with width cubed instead of thickness cubed (~77×
overestimate); the exterior/interior bay predicate misclassifying 6 of 11
bays; strut panel mass using the strut's own cross-section (capturing ~38% of
the tributary panel mass); torsional-irregularity node pairing, wrong twice;
the elastic-vs-reduced spectrum mismatch in `combinedBaseShear`; a missing
`Ie` in the P-Delta numerator; and `BEAM_WALLEDGE` duplicating `BEAM_Y` on
both wall lines (+20.6 m of phantom beam, +9.03% mass), found by the quantity
takeoff's independent mass reconciliation.

## Capacity assessment, variants and site checks

Everything above this line is a **demand** model. `ForceRecovery`,
`SectionCapacity` and `Assessment` are the strength side, and they answer a
different question.

**`tSM` is load-bearing.** `GuyanReduction` used to compute the expansion
operator, use it once and drop it. The reduced DOF are translations only, so
without it every rotation — and therefore every bending moment — is
unrecoverable, and no section check is possible. `Result.expand()` restores
the condensed DOF via `u_s = -tSM * u_m`. Section 12 asserts the defining
property (slave rows of `K*u` vanish); a sign error there would be invisible
downstream.

**Recover forces per mode, then CQC the forces.** Not the other way round. A
CQC-combined displacement vector is an envelope of magnitudes that no instant
of the response ever takes; differencing it across a member gives a meaningless
curvature. `combinedDemands()` does it in the right order and the comment says
why.

**`SectionCapacity.kt` works in N and mm internally**, because that is the only
unit system SNI 2847's expressions are dimensionally true in. Conversion happens
once at the public boundary (`KN_PER_N`, `KNM_PER_NMM`). Do not "tidy" it into
kN and m — every coefficient would have to change, and this repo's own history
says what happens then.

**The substantive result: detailing governs, not force demand.** With the shear
walls taking ~95% of the lateral load, every force-based D/C ratio on this frame
is below 0.10 — column flexure is **0.007**. The only check that comes near
governing is **tie spacing at 0.781**. That is not a quirk of the model; it is
what a stiff single-storey infilled box is like. Section 14 asserts it, so if it
ever stops being true the building or the model has changed materially.

**`DesignVariant` parameterizes the design so it can be perturbed.** f'c is the
source of truth and E is derived from it via `Ec = 4700*sqrt(f'c)` — they cannot
be set independently, because a variant modelling a grade shortfall has to
soften the frame as well as weaken it. The round trip is bit-exact, so
`AS_SPECIFIED` reproduces every previously verified number. Its section
overrides keep the spec's own rounded second moments (`Iz = 0.000233` where
`0.230^4/12` is `0.00023320`, 0.09% higher); any variant that changes a
dimension must set the overrides to null so the section follows.

A comparison of two variants is worth far more than either absolute number:
both runs share every modelling assumption, so whatever is wrong with the model
is wrong in both and largely cancels.

**What is NOT checked**, and it is a long list: the shear walls themselves
(in-plane wall design needs boundary-element and sliding-shear checks), the
beam-column joints, development and splice lengths, infill-to-frame anchorage,
and everything below the sloof. Several of those govern real failures in exactly
this kind of house. `Assessment.Result.notes` carries the list and the app
prints it under the numbers — do not remove it. Column gravity axial comes from
the assembly's tributary mass, not a gravity FE solve (there is no vertical mass
in this model at all), and the beam gravity moment is an explicit `wL^2/12` hand
estimate for the same reason. X and Y are checked separately; the 100/30
directional rule is not applied. `rho = 1.0`; if ASCE 7 12.3.4.2 is not
satisfied it is 1.3, which is +30% on every seismic demand.

## Unit rates and the RAB check

`Ahsp.kt` + `RabComparison.kt` turn the takeoff into money, so the owner can
check a contractor's RAB without arguing structural engineering. Section 16
of the harness asserts them.

- **Coefficients are transcriptions**, read from the primary PDFs, each
  carrying its clause (`Analysis.ref`). Same rule as `lapack_port`: do not
  round or "improve" them. The rebar item is SNI 7394 6.17 only (PUPR 1/2022
  lists it but does not reprint it); plaster and acian are SNI 2837 6.4/6.27.
- **No prices live in code.** The owner's quotes arrive in `prices.txt`. An
  unquoted resource makes its items *unpriced* (`null`), never free; `0` is
  a real price and distinct from blank. Do not add default prices -- an
  invented number next to the contractor's reads as evidence.
- **Where a choice exists it favours the contractor** (mortar 1:4, O&P at the
  15% PUPR maximum, the larger AAC mortar figure), so flags survive the
  argument. Two known exceptions, both stated in the report: AAC 15 cm labour
  is the 10 cm row (a 15 cm block is heavier), and steel carries AHSP's 5%
  cutting allowance on top of the takeoff's lap/waste factor (generous).
- **`PAS.01` (15 cm AAC) is DERIVED**, not published: PUPR stops at 10 cm, and
  its 10 cm mortar figure (0.063 kg/m2) is unusable, so mortar comes from the
  MU-380 data sheet. See the `Ahsp.AAC_150` comment.
- **Formwork is the most generous line.** The timber items carry a full set of
  timber per m2 and state no reuse, so a contractor's formwork price well under
  the reference is normal, and it dominates the priced total. Read gaps line
  by line.
- **Number parsing is strict on purpose.** Rupiah must be whole with no `.` or
  `,` (`1.500` is 1500 in Indonesian, 1.5 to a computer); decimals use a point.
  Do not relax this to be "friendly".
- **Files on the phone:** `getExternalFilesDir(null)`, i.e.
  `/sdcard/Android/data/com.dawud.mpmrbench/files/`. The page rewrites
  `prices.template.txt` and `rab.template.txt` there on every visit, and reads
  `prices.txt` / `rab.txt` pushed with `adb push`. No rebuild for new quotes.
- **Host:** `java -cp build/griya.jar griyasakha.RabComparisonKt prices.txt rab.txt|boq.xlsx`
  (`-` for none), or `--templates DIR` for blank files.
- **The BoQ workbook is the preferred contractor input.** `BoqWorkbookKt out.xlsx
  [--with-quantities]` writes it (the app also drops `boq-template.xlsx` in its
  files dir); the contractor prices OUR rows, one per takeoff code, plus the
  owner's special items (SO-02/SO-03) and a `-` section for everything else.
  Their filled copy, pushed as `rab.xlsx`, is read directly and wins over
  `rab.txt`. Without quantities (default) the RAB page tests their measuring
  too; with them it is price-only.
- **Reading a workbook:** a row is priced when its PRICE cell is filled (the
  owner pre-fills volumes, so volume-without-price is just unpriced); price
  without volume is an error. Numeric cells carry no separator ambiguity; TEXT
  cells go through the same strict `OwnerInput` rules. Any part with a DOCTYPE
  is refused before parsing -- the file comes from outside. Tested against a
  copy re-saved by LibreOffice (shared strings, its own styles), not only
  against our own writer.
- **Not yet the owner's house:** the rebar schedule is the placeholder
  `RcSchedule()` and no openings are entered; the report says so every time.
  A contractor's OWN RAB line bundling concrete + formwork + steel per m3 still
  cannot be mapped (`BSI.01` is not split per member); the BoQ workbook avoids
  the problem by fixing the structure they price.

## Site setout and the solar gnomons

`setout/` shares nothing with the Kotlin analysis or the Gradle build. Its
Python is stdlib-only; `observe.js` needs Bun. `setout/README.md` has the detail.

| File | Role |
|---|---|
| `setout/setout.py` | Stakeout from two GNSS marks A/B. Stakes the corners and central line on the Karney qibla azimuth, plus the meridian line (`MF MN MS`) and outdoor pad (`GF GP GN GS P1-P4`) on **true** north. Prints every angle as the whole-arcsecond value to dial, and a total-station budget. `--selftest` = 27 checks, exits non-zero on failure |
| `setout/geolib.py` | Vincenty inverse, ECEF-ENU (Karney LocalCartesian equivalent) |
| `setout/tsspec.py` | What the rented total station adds to the central line's azimuth: angle class (ISO 17123-3, single face), centring, EDM, marking, whole-arcsecond rounding; `--ts-table` judges classes by parcel size. Checked against a Monte Carlo of the staking |
| `setout/gnomon.py` | Sun geometry for the two gnomons, by declination sweep: meridian-line extent, Asr shadow point, pad extent |
| `setout/cors.py` | Ina-CORS station ranking for the GNSS half |
| `setout/sheets.py` | Drawing sheets for the owner-supplied items: SO-01 setout plan 1:100, SO-02 meridian room 1:25, SO-03 Asr post and pad 1:20, as true-size DXF R12 (mm; SO-01/02 at real house coordinates) and one A3 PDF. Every number from `setout.py`/`gnomon.py`, none retyped. `--selftest` = 21 checks |
| `setout/drawing.py` | The primitives both writers share, the DXF R12 writer and a stdlib PDF writer (Helvetica, WinAnsi) |
| `setout/observe.js` | praytime.js v3.2 times, unrounded, next to what each instrument should show and the expected observed-minus-code offset; `--at` turns a twilight observation into sun depression |
| `setout/praytime.js`, `praytime.LICENSE` | npm `praytime@3.2.0`, **byte-identical, vendored on purpose**. It is the code under test, so never edit or upgrade it in place; a new version is a new comparison |
| `setout/engineer/pertanyaan-insinyur-struktur.{html,pdf}` | The owner's one-page A4 checklist (Bahasa Indonesia) for the structural-engineer meeting. The PDF is rendered from the HTML with headless Chrome. Numbers are copied by hand from `src/`, so keep them in step and re-check `pdfinfo` still says one page |

**The plan these tools serve lives in the quarkus repo**, as the field sheet at
<http://183.81.158.231:8080/qibla-setout-field-sheet.html> (locally
`/WIN_D/protek/quarkus/src/main/resources/META-INF/resources/qibla-setout-field-sheet.html`).
Part two of it, stages 8-12, is the gnomon plan that used to be
`griya-sakha-gnomons.html` here. **Read it before** touching `gnomon.py`,
`observe.js` or the gnomon half of `setout.py`, and before any design change to
the qibla-end room (grid x 0-3, y 7-10.3), the roof sheeting over it, the ring
beams on y = 7 / y = 10.3, the roof mass, or anything near the west boundary.
It is about 170 KB, so read the section you need; they are anchored:
`#parcel #integer #skyline` (choosing the parcel), `#s0-card`..`#s7-card` (the
setout stages), `#gnomons #verdict #code #s8-card #dhuhr #asr #maghrib
#twilight #house #check #year #tool` (the gnomons), `#instrument` (the total
station). What part two decided, so none of it gets re-derived:

- **Dhuhr** (whole disc past the meridian): an indoor pinhole meridian. A 3 mm
  hole at H 3.20 m, on a steel angle spanning the y = 7 and y = 10.3 ring beams;
  never on the light-steel truss, because 1 mm of movement is 4 s. Foot at house
  (1.95, 8.80); the line is 2.873 m on true north, 24°15′18″ from +x toward +y.
  Refraction-free, ±4 s. Observed minus code equals the semi-duration,
  63.7-70.9 s over the year, not a constant 65 s.
- **Asr** (Standard): the roof aperture cannot work, because the shadow radius
  is 3.20-5.07 m against a 2.85 × 3.14 m room. Instead, an outdoor 60 mm ball
  nodus at 1.50 m on a rigid post, over a 2.07 × 2.19 m pad levelled to ±0.5 mm.
  The ball is deliberate: a pinhole image is invisible in open daylight. Arcs
  are set daily, since the radius changes by up to 11 mm a day. ±6 s; observed
  minus code is -2.4 to +8.7 s.
- **Maghrib**: neither gnomon works. Video the upper limb against a skyline
  measured once with the total station. Observed minus code is -8.6 to -12.3 s
  on a 0° skyline; horizon refraction adds ±10-40 s from night to night.
- **Isha/Fajr**: no sun or shadow method exists; the lowest sunlit air is
  330-410 km up. Use sky photometry and compare in degrees via `observe.js --at`.
- **The code is not the weak link.** praytime.js v3.2 has no elevation input,
  rounds to minutes unless `.round('none').format('x')`, and iterates once. With
  matching definitions it agrees with the NREL SPA to 2 s or better (Isha
  3.2 s). The ±30 s budget goes on the instruments and the air.

**The structural consequence.** The meridian room adds about 12 kg -- an
L50×50×5 over the 3.07 m clear span between the y = 7 and y = 10.3 ring beams
is 11.6 kg, plus plate and anchors. (Both this file and the page said "under
10 kg" until 2026-09-24; still 0.03% of the roof mass.) The model does not
change. What deserves the engineer's sign-off is the anchors, drilled into the
ring beams' side faces between their 2 + 2 D12 bars and ties: locate the bars
before drilling. A roof deck for watching sunsets would put about 2.9 t
at roof level against 37.8 Mg, moving T1, MPMR, base shear and the §12.9.4
factor, and the 126 checks would have to be re-run. The plan keeps the sunset
station off the house for exactly that reason; do not quietly reverse it.

**Who owns the marks** (the owner's decision, 2026-09-24). The owner produces
the setout -- the GNSS baseline, the total-station day, the bouwplank marks --
so the house's position and alignment are the owner's responsibility, not the
contractor's. From handover the contractor's job is to preserve the bouwplank
and build to it. Stage 7 (tape and shadow checks) is done jointly with the
contractor, before anyone digs, and goes into the contract. Anything generated
for the contractor (checklists, RAB notes) should treat setting-out as
owner-supplied, not as contractor work to be checked.

**Keeping this repo and the field sheet in step** -- two repos, so nothing does
it for you:

- **Edit the page in quarkus**, not here; there is no copy here any more.
  Deploying it is the quarkus repo's business (the office server).
- The old published artifact (a standalone copy of the gnomon page, distinct
  from the quarkus repo's own artifact history) was **deleted on 2026-09-25**;
  its link is dead. Do not republish to it or reference it.
- The page's `#tool` section embeds `setout/observe.js` verbatim, HTML-escaped.
  Change one, change the other. (Checked equal on 2026-09-24 by unescaping the
  `<pre id="c2">` block and comparing it with the file.)
- `setout.py --selftest` asserts the page's gnomon dimensions -- meridian ends
  +0.971 / -1.902 m, pad E -0.04..2.03 / N -1.35..0.84 m, June Asr shadow
  2.380 m toward 123.4° -- and the verdicts in its parcel table. A design change
  that moves them updates the page and those checks together.
- The drawing sheets (`sheets.py`) are derived from `setout.py` and `gnomon.py`, and
  their self-test re-checks the page's figures (line ends, pad size, the MF
  window, the clear-sheet fractions, the Asr sky). Change a constant and
  regenerate the sheets before handing them out; the title block carries the
  commit they came from.
- The year table and the figures come from a dated 2027 run of `observe.js`,
  cross-checked by an SPA harness built from the sibling Qur'an app's
  `SolarPosition.java`. That harness is **not** in this repo, and nothing here
  regenerates the tables. `bun observe.js --from 2027-01-01 --days 365 --csv`
  reproduces their inputs.

```bash
cd setout && python3 sheets.py                   # out/SO-01..03.dxf + out/griya-sakha-SO.pdf
cd setout && python3 sheets.py --selftest        # 21 checks: numbers vs the field sheet, DXF, PDF, layout
cd setout && python3 setout.py --selftest       # 27 checks
cd setout && bun observe.js --from 2027-06-21    # code: dhuhr 11:38:00.0, asr 14:59:19.2, sunset 17:29:34.4
```

**The Kaaba is 21.4225395, 39.8262** in `setout.py`, the same point the Qur'an
app's `Geodesic.java` and `IntegerArcsecondQiblaDirection` (etcetera repo) use.
It was 21.4225 until 2026-09-23; that 4.4 m put the 294°15′18″ site at
294°15′17.86″ and hid behind a self-test tolerance that read it as
"Vincenty and Karney differ by 0.14″". They do not: same point, ~1e-9°. Change
the constant and the integer-arcsecond check fails, which is the point of it.

**The total station.** `tsspec.py` decided, and the self-test pins, that the
angle class is rarely the weak link: put A on the central line's extension
(`--anchor-house 3,-4 --anchor-enu 0,0`) so EDM error cannot turn the line,
drop the instrument onto the GNSS tribrach (forced centring beats a 1″ class),
and mark lines by crosshair on the bouwplank, never with a hand-held pole
(85″ on the 10.3 m line). A 5″ instrument is enough on every parcel from 8×15
to 20×40 m. The full write-up is the field sheet's `#instrument` section; the
page also absorbed the old S20 GNSS guide and this repo's gnomon plan.

**What the repo cannot tell you.** The fence, the parcel boundary and the
western skyline are not recorded anywhere here. `--pad-foot` therefore
defaults to a placeholder 4 m past the qibla wall, and the skyline is a
measured input (`observe.js --horizon-file`). The volcano positions in the
Maghrib figure are approximate, and the brightness figures are estimates.

## Running on the device (SM-G980F)

Target is a Samsung Galaxy S20, model **SM-G980F/DS** — Exynos 990, so
**arm64-v8a only**. Do not add other ABIs unless testing on an emulator
(which needs `x86_64`, or the library won't be found).

Expected values on device, for comparison against a host run: **24 modes,
worst residual below 1e-8, fundamental period ≈ 0.0886 s, MPMR summing to 1.0
in both directions.**

### Four things that break at runtime, not compile time

1. **The JNI symbol name is load-bearing.** The native library exports
   `Java_com_dawud_mpmrbench_EigensolverBridge_solveEigensystemRaw`. JNI
   derives that mechanically from package + class + method, so
   `EigensolverBridge` **must** stay in package `com.dawud.mpmrbench` with
   that exact class and method name (see `src/NativeBridge.kt`). Renaming any
   part compiles cleanly and then throws `UnsatisfiedLinkError` on the phone.
   If you rename, change the C++ symbol in the same commit.

2. **`@JvmName("solveEigensystemRaw")` is load-bearing too.** The
   `external fun` is `internal` (so the instrumented tests can drive the JNI
   boundary directly), and Kotlin mangles an `internal` member's JVM name by
   appending `$<module>`. Without the annotation the method compiles to
   `solveEigensystemRaw$app`, JNI looks for
   `..._solveEigensystemRaw_00024app`, and the `.so` does not export it.
   Verified: dropping it fails `nativeSolverMatchesJvmReference` and
   `nativeRejectsMismatchedArrays` on the device, and nothing at compile time.

3. **`exitProcess` would kill the test runner.** `Verification.kt` exposes
   `runAllChecks(): Int` returning a failure count; only the JVM `main()`
   wrapper calls `exitProcess`. Instrumented tests must call `runAllChecks()`
   and assert the result, never `main()`.

4. **`arm_neon.h` means arm64 only.** `phase1_solver.cpp` includes
   `<arm_neon.h>`, so it will not compile for `x86_64`. Adding an emulator
   ABI to `abiFilters` needs that include guarded first — the build will
   fail loudly, but the cause is not obvious from the error.

### Runtime ISA dispatch in the hot loops

`phase1_solver.cpp` resolves **four** hot kernels once at
`System.loadLibrary` time from `getauxval(AT_HWCAP/AT_HWCAP2)`, into a
function-pointer table (`g_hotKernels`), reached through the `dotProduct()`,
`matVec()`, `axpyRow()` and `panelOuterProduct()` wrappers:

| Path | Trigger | Instruction |
|---|---|---|
| SME | `HWCAP2_SME` **and** `HWCAP2_SME_F64F64` | `fmopa za0.d` (FP64 outer product) |
| SVE | `HWCAP_SVE` | `fmla z.d` with `whilelt` predication |
| NEON | always available | `vfmaq_f64` — the baseline, and the fallback |

Things to know before touching it:

- **`axpyRow` has no SME variant.** AXPY is BLAS-1 — no outer-product
  structure for FMOPA to exploit. On the SME path it is selected
  independently by checking `HWCAP_SVE` directly, because SME does not imply
  base SVE on the same core.
- **`panelOuterProduct` is the only real FMOPA target here.** It computes
  `C := A·Bᵀ (+ C)` for row-major panels whose *reduction* index is
  contiguous, and drives all three BLAS-3-shaped trailing updates: Cholesky's
  SYRK, the blocked Householder's SYR2K (two calls — assign `V·Wᵀ`, then
  accumulate `W·Vᵀ`), and `matMulBatchedT`'s GEMM. Unlike `dotSme`, the SME
  variant reads out the **full M×N tile** rather than `trace(ZA)`, so every
  product FMOPA computes is one the caller asked for. The reduction index is
  the *loop* index and M/N are the *lane* indices — the structural inversion
  relative to `dotSmeStreaming`, and why there is no `whilelt` chunking over
  K in that kernel. Both operands are packed k-major first (ordinary
  non-streaming code, outside the one streaming region).
- **SM-G980F always takes the NEON path.** Exynos 990 has neither SVE nor
  SME, so on the actual target device the SVE and SME code is dead. It is
  reached only on newer silicon. `JNI_OnLoad` logs the resolved path (with
  the raw HWCAP bits, via `hotKernelSummary()`), and it is also the first
  line of the Phase 1 report — check it before attributing any number to a
  particular kernel.
- **FMOPA is a matrix-matrix instruction being used for BLAS-1/2 work.** The
  dot product falls out of `trace(ZA)` when both operands of the outer
  product are the same slice. That is correct but inherently does `VL*VL`
  multiplies per `VL` useful ones, so at this building's n=24 the SME path is
  expected to be *slower* than NEON. It is there for architectural coverage,
  not speed. `panelOuterProduct` is the exception in *kind* — it uses the full
  tile, so it is a genuine matrix-engine shape — but not in *effect* here: at
  n=24 with `NB=32` there is no panel wide enough to batch, blocking
  degenerates to the unblocked path, and none of the panel code executes at
  all. It is for a larger future caller, not for this house.
- **The HWCAP bit values are pinned locally** with `#ifndef` fallbacks
  because older NDK kernel headers lack `HWCAP2_SME_F64F64` / `HWCAP2_SME2`.
  Note `HWCAP2_SME_F64F64` is bit **25**.
- **The three paths are not bit-identical** — they sum in different orders.
  They agree to well inside the 1e-8 residual bound, but do not write an
  assertion that expects exact equality between a host run and a device run.
  (Phase 4 is the one exception — see below.)
- Each path was checked under `qemu-aarch64 -cpu {max, cortex-a76,
  cortex-a710, neoverse-v1, a64fx}` against a long-double scalar reference,
  across SVE and SME vector lengths of 128 through 2048 bits. Calling a path
  the CPU lacks traps with `SIGILL`, so the `getauxval` guard is load-bearing
  in the same way the JNI symbol name is.

### Blocked phases and the DSTERF port

Cholesky, the congruence reduction, Householder tridiagonalization and
eigenvector back-transformation are **panel-blocked** (right-looking,
BLAS-3-shaped) via a generic block-size parameter, `MPMR_DEFAULT_NB = 32` —
not tuned to n=24. Each blocked phase is kept **side by side with its
unblocked counterpart**, which is what every blocked version was
cross-validated against element-by-element. That is not dead code; do not
delete the unblocked versions.

All three of those trailing updates go through `panelOuterProduct` rather
than a per-`(i,j)` `dotProduct` loop. The two symmetric ones (SYRK, SYR2K)
stage through `PanelScratch` in row strips of `kPanelStripRows`, which both
bounds the staging buffer at O(strip·n) instead of O(n²) and — because only
`j <= i` is wanted — computes roughly the lower triangle instead of the full
square. Cross-validated under QEMU across 1727 panel shapes on all four
tiers; the NEON and SVE paths are **bit-identical** to the `dotProduct` loops
they replaced, the SME path differs by ≤5e-15 because FMOPA genuinely
accumulates differently (fused, strict k order — marginally *better*
conditioned than the kernel it replaced).

Phase 4 (eigenvalues of the tridiagonal) is a port of reference LAPACK
`DSTERF` and its dependency closure (DLAE2, DLANST 'M', DLAPY2, DLAMCH
'E'/'S'/'O', DLASCL, DLASRT). It is verified **bit-for-bit** — not within a
tolerance — against Reference-LAPACK compiled with gfortran, across 185 cases.

It is no longer a *literal* transcript: the gotos are structured loops, the
arrays are 0-based, the leaf routines take `std::span`, and DLAMCH is
`constexpr`. Each of those was landed as its own step with the full 185-case
bit-comparison re-run in between, and every step came back byte-identical.
The **arithmetic** is still untouched — same operations, same operands, same
order — and that is the invariant that matters. Rewriting any expression as a
"mathematically equivalent but differently ordered" one still breaks the
guarantee.

**Re-run the harness after any change in here**, and do not rely on the
downstream residual checks to catch a Phase 4 regression — they are four
orders of magnitude too loose:

```bash
dsterf/production_harness/run.sh     # 185 cases, must print 185/185
```

That harness drives the **production** `lapack_port::dsterf`, built for
aarch64 and run under QEMU. The older `dsterf/claude_intermediate_code/` one
drives a standalone intermediate port instead and cannot catch a regression
in shipping code. aarch64 is not incidental: `-ffp-contract=off` exists
precisely because x86_64 and aarch64 diverged without it.

Two "should be unreachable" guards were removed **as unrepresentable, not as
assumed-dead**: `dsterf`'s `N < 0` and `dlasrt`'s, both gone because a span's
size is unsigned. `dlascl`'s argument-validation branch is deliberately still
a logged early return marked `[[unlikely]]`, *not* `std::unreachable()` — no
input reaches it, so the harness cannot falsify an unreachability claim
there, and asserting one would trade a safe return for silent UB.

### C++23 baseline

`phase1_solver.cpp` requires **C++23** and will not compile as C++17. The
standard comes from `CMAKE_CXX_STANDARD 23` in `CMakeLists.txt`, which is also
now the single source of the compile flags — the old `cppFlags` list in
`app/build.gradle.kts` (which carried a stale `-std=c++17` that was silently
overridden) is gone.

What the file depends on, and why it matters when reading it:

| Feature | Where | Why |
|---|---|---|
| `std::span` (`CSpan`/`DSpan`/`RowsIn`/`RowsOut`) | the whole kernel ABI | `DotFn`/`AxpyFn`/`PanelFn` used to be `(const double*, …, int)` triples; the length now travels with the pointer. `span::operator[]` is unchecked, so the win is at the call boundary — which is where the mistakes were |
| `std::mdspan` + contiguous `Mat` | `Mat` | was `vector<vector<double>>`: one heap allocation *per row*. Now one buffer. `Mat::operator[](i)` still returns a row, because the pipeline's "never read a column" invariant means rows are the real access pattern |
| multidimensional `operator[](i, j)` | `Mat`, JNI marshalling | for the places where the 2D shape, not the row, is the point |
| `std::expected` | `solve()` | an empty vector *was* the failure signal, indistinguishable from a 0×0 solve. `SolveError` makes failure unreadable as a result |
| `constexpr` DLAMCH | `lapack_port` | the three machine constants fold at compile time |
| `#elifndef` | NDK-lag guards | says "not defined by the block above" directly |
| `std::to_chars` | the four report sites | locale-independent, unlike `printf`'s `%f`/`%e`. `<format>` would do the same but measured **+137 KB**; `<charconv>` is free |
| `enum class IsaPath` + `std::to_underlying` | dispatch | the tier is the identity; the string is only for humans |

Two traps found by doing this, worth remembering:

- **`std::to_underlying` on a `unsigned char`-backed enum streams as a
  character.** `[tier 2]` printed an unprintable `0x02` until the
  `static_cast<int>` went in. No numerical test can catch that — only reading
  the output can.
- **`__restrict` is on kernel *outputs* only.** `axpyRow`'s `y`, and the panel
  kernels' `C` row. Never on `dotProduct`'s operands (`dotProduct(v, v)` is a
  real call site) and never between the panel kernels' A and B — in the
  Cholesky SYRK call site `scratch.a[r]` and `scratch.b[c]` are *the same
  pointer* when `c == i0 + r`.

`-Wall -Wextra -Werror=unused-function` are on. The last of those is deliberate:
the unblocked `cholesky`/`congruence`/`householderTridiag` references exist
only to cross-validate the blocked versions against, and all three had silently
lost their last caller before this was turned on.

### Native build flags

Build with `-O2 -fno-fast-math -ffp-contract=off`.

- **Do not add `-ffast-math`**: the solver's convergence tests and the
  harness's residual assertions both depend on IEEE semantics, and fast-math
  reassociation can perturb them enough to matter.
- **`-ffp-contract=off` is REQUIRED and is not implied by `-fno-fast-math`.**
  Both targets of this toolchain report `-ffp-contract=fast` as the active
  default even under `-fno-fast-math`, and aarch64 has cheap native FMA while
  baseline x86_64 does not — so identical source under identical flags
  silently contracted on-device and never on the host, breaking Phase 4's
  bit-identity. It must be set at the build-system level; it is, in
  `CMakeLists.txt`. Removing it reproduces the divergence.
- **`CMakeLists.txt` is now the single source of the compile flags.** The
  `externalNativeBuild { cmake { cppFlags … } }` block in
  `app/build.gradle.kts` is gone, along with the stale `-std=c++17` it used to
  carry (silently overridden, because `CMAKE_CXX_STANDARD 23` emits its flag
  later on the command line). The effective compile line is
  `-std=gnu++23 -O2 -fno-fast-math -ffp-contract=off -Wall -Wextra
  -Werror=unused-function`, with no duplicates. Verify with
  `app/.cxx/Debug/*/arm64-v8a/compile_commands.json` after a build rather than
  by reading the Gradle DSL — the two used to disagree.

### What device testing is actually for

Phase 0 benchmarking already established that GPU/Mali offload only pays off
above roughly n=1350-1400 DOF, and this building is 24 DOF — so **CPU-only is
settled, and no Mali work is needed**. Do not reopen that.

What the device run is for is the JNI boundary itself: array marshalling,
the packed return format, mass-normalization convention surviving the
round trip, and the empty-array rejection path. Those are exactly the things
a host build cannot check.

## Standing review instructions

Apply these on every review of this repository.

### Judgment calls that are now CLOSED

All six previously-open calls have been decided. Do not silently reopen them;
if you disagree, say so explicitly and give the reasoning.

1. **Elastic vs design spectrum** (`CQC.kt`) — **resolved.** Both the modal
   force path and the modal displacement path now go through
   `SeismicParams.saReduced()`, the single R/Ie choke point, so `V_CQC` and
   `V_ELF` share a basis and the §12.9.4 comparison is live (the real factor
   here is ~1.30). The old elastic version made `baseShearScaleFactor()`
   return 1.0 unconditionally, silently skipping a required scale-up.
2. **Exterior vs interior bay assignment** (`Assembly.kt`) — **fixed, it was
   a real bug.** The old single predicate was vacuously true for every
   X-spanning bay and misclassified 6 of 11. Now split into
   `isExteriorXBay(y)` / `isExteriorYBay(x)`; x=0 and x=6 are shear walls, so
   the only exterior infill runs along y=0 and y=10.3 — 4 exterior, 7
   interior. (`isExteriorBay` no longer exists.)
3. **`beta = 1.0` in the P-Delta check** (`CodeChecks.kt`) — **reviewed and
   closed.** It is the value ASCE 7 §12.8.7 prescribes when the shear
   demand/capacity ratio is not calculated, and it is the conservative end.
   `Ie` is now carried explicitly in the Eq. 12.8-16 numerator.
4. **Wall torsion constant** (`Assembly.kt`) — **reviewed and closed.**
   Thin-rectangle St. Venant `J ≈ (1/3)·b·t³` is correct here: b/t ≈ 17,
   comfortably inside the validity range, and warping restraint is rightly
   neglected for a squat single-story pier.
5. **Missing `T_L` branch** (`SeismicParams.kt`) — **closed by assertion.**
   Still not implemented, but the harness now asserts no modal period exceeds
   `TS`, so reusing this for a taller structure fails loudly instead of
   silently taking the wrong branch.
6. **Strut panel mass** (`Assembly.kt`) — **fixed.** Now the real panel mass
   (`ρ·t·span·h_inf`), charged a quarter per strut so half the panel lands at
   roof level split across the bay's two roof nodes. The old strut-cross-
   section proxy captured ~38% of it.

### Still open, and worth your attention

- **`FrameElement.localAxes()`** handles only three axis-aligned orientations
  and throws on anything else. Still true, still load-bearing. Verify that
  assumption holds for any geometry change.
- **Masonry Poisson's ratio** is defaulted to 0.2 in `Materials.kt` because
  the spec gives none. `G` is unused for the axial-only pinned struts, so it
  changes nothing today — but it would the moment a strut gains bending DOF.
- **`verify.sh` is missing** while four places still reference it (see above).
- **Stale comments to be aware of:** `addMass()` says the unit bug was "~21x"
  (it is 31.6×); the root `build.gradle.kts` says KGP "2.4.10" (the catalog
  pins 2.4.20). Both verified still present.
- **`setout.py --pad-foot` defaults to a placeholder.** The real parcel and
  west boundary are not in the repo, so the outdoor gnomon pad's position is
  unconfirmed until someone supplies it.
- **`gradle.properties` sets `android.defaults.buildfeatures.buildconfig=false`
  while `app/build.gradle.kts` sets `buildConfig = true`.** The module-level
  setting wins; the pair is confusing rather than broken.

### On the JNI layer specifically

Check that array lengths passed across the boundary match what the native
side expects (`n + n*n` for the packed return), that an empty return is
treated as rejection rather than zero modes, and that nothing assumes the
native side sorts or normalizes differently than documented. These are
silent-wrong-answer bugs, not crashes. `DeviceVerificationTest` covers both
halves of the rejection contract — the Kotlin-only `unpack(DoubleArray(0))`
path *and* `nativeRejectsMismatchedArrays`, which actually crosses JNI.

### Hard rules

**Do not** rewrite verified numerical formulas for style — this now includes
the `lapack_port` namespace, whose whole value is being a faithful transcript.
**Do not** weaken or delete assertions in `Verification.kt` to make a build
pass. If a check fails, the code is wrong, or the check is wrong — say which,
and why. Report findings by severity; do not change behavior without
flagging it.
