# Qibla-oriented setout

**The plan these tools serve is one page in another repo:**
`src/main/resources/META-INF/resources/qibla-setout-field-sheet.html` in the
quarkus repo, served at <http://183.81.158.231:8080/qibla-setout-field-sheet.html>.
It runs end to end -- choosing the parcel, the GNSS baseline and total-station
setout (stages 0-7), and the solar gnomons (stages 8-12). The gnomon half used to
be `griya-sakha-gnomons.html` at this repo's root; it was merged there on
2026-09-24 and deleted here.

Turns two GNSS-observed marks into the stakeout table for the 6.0 x 10.3 m
house in `src/Geometry.kt`, with its central line aimed at the Kaaba.

```bash
python3 setout.py --selftest                 # 27 checks, no arguments needed
python3 setout.py --cors                     # rank the Ina-CORS stations
python3 setout.py --a <lat,lon,h> --b <lat,lon,h> --cm 111.0
python3 setout.py --a <lat,lon,h> --b <lat,lon,h> --anchor-house 3,-4 --anchor-enu 0,0   # A on the central line
python3 setout.py --ts-table 12x25 20x40     # which total-station class a parcel needs
python3 setout.py --a <lat,lon,h> --b <lat,lon,h> --pad-foot <x,y>   # + solar gnomons
bun observe.js --from 2027-06-21             # praytime.js v3.2 vs what the gnomons should show
```

The Python side has no dependencies beyond the standard library. `geolib.py`,
`gnomon.py` and `tsspec.py` are Android-free and Gradle-free for the same reason `PrayTime`
and `BayanganKiblat` are in the sibling Qur'an project: the astronomy and the
geodesy have to be checkable without a device. `observe.js` needs Bun (tested
with 1.2) and nothing else: `praytime.js` sits next to it.

## What it computes

```
A (benchmark)  ─┐
B (backsight)  ─┴─► baseline azimuth ──┐
                                       ├─► rotate house frame ──► 4 corners + central line
site lat/lon ──► Karney qibla azimuth ─┘                          in ENU, in lat/lon,
                                                                  and as polar setout
```

`+y` in house coordinates runs along the 10.3 m central line and is aimed at
the Kaaba; `+x` is 90 deg clockwise from it. Every coordinate emitted is a
**column / wall centreline**, matching the `Grid(x=[0,3,6], y=[0,3.5,7,10.3])`
that `Geometry.kt` analyses -- not a wall face. The 200 mm shear walls at
x=0 and x=6 put outer faces at -0.100 and 6.100; the 230 mm columns on the
y=0 and y=10.3 frames put faces at -0.115 and 10.415. Staking the wrong one
is a 100-115 mm error, an order of magnitude larger than anything the azimuth
can do to you.

## Why the baseline length is the only thing that matters

Azimuth error is `sigma_az = sqrt(2) * sigma_perp / D`. Every millimetre at
the two marks is divided by the baseline length `D`, so `D` dominates -- not
the receiver, not the antenna, not the CORS distance. With the nearest
station 7 km away, `sigma_perp` is about 4 mm for a single receiver run as
two sequential static sessions, and about 2.4 mm for two receivers observing
simultaneously:

| D | 1 receiver, sequential | 2 receivers, simultaneous |
| --- | --- | --- |
| 5 m | 3.80' | 2.38' |
| 15 m | 1.27' | 0.79' |
| 25 m | 0.76' | 0.48' |
| 40 m | 0.47' | 0.30' |

For scale: **1 degree is about 107 km at the Kaaba** (`R*sin(sigma)*delta`,
with sigma = 75.3 deg of arc; the flat `s*tan` form overstates it by ~35%).
And a normal 10 mm masonry tolerance over 10.3 m is *itself* 3.34' of angle.
So any baseline of 15 m or more puts the azimuth below what the carpentry can
hold, and 5 m does not. **Use the longest line the parcel allows.** The two
marks do not have to sit inside the footprint -- they only have to be
intervisible for the total station's backsight.

## Absolute position barely matters

Measured sensitivity of the qibla bearing to position error:
**2.1e-6 deg per metre**. A 5 m absolute error moves the bearing by 1e-5 deg.
What the GNSS has to deliver is the *relative vector* A->B to millimetres;
the absolute fix only has to be good to about a metre. That is why the CORS
half of the job is the easy half.

## The three traps

| north reference | error vs. Karney geodesic |
| --- | --- |
| Spherical great circle | **+7.42'** |
| Rhumb line (constant bearing) | **-125'** -- never |
| BPN TM-3 grid north (CM 109.5 / 112.5) | **-10.75' / +11.34'** |
| UTM 49S grid north | +0.29' (near CM 111, lucky) |

The TM-3 row is the live hazard, because that is the frame a parcel
certificate is in. ENU north from `LocalCartesian` *is* geodetic north, which
is why the tool works in ENU and never touches a map projection.

## Verification

`--selftest` proves the two claims the house rests on, against an independent
geodesic solution rather than against itself:

- LocalCartesian ENU azimuth vs. geodesic inverse: **0.0097"** over 46.9 m
- staked central line re-derived from its own lat/lon, azimuth to the Kaaba:
  **0.007"** residual
- Vincenty inverse vs. `com.quran.kiblat.salat.Geodesic` (Karney) in the
  sibling project: **294.267388007 deg from both**, on the same Kaaba
  coordinate -- two different formulations agreeing to ~1e-9 deg
- the integer-arcsecond site `IntegerArcsecondQiblaDirection` chose
  (-6.96595767, 110.9274) reads **294d15'18.00000"**

**The Kaaba coordinate is 21.4225395, 39.8262**, the one `Geodesic.java`
encodes in `SIN_BETA_2` and `IntegerArcsecondQiblaDirection` searched against.
Until 2026-09-23 this tool used 21.4225, 4.4 m south. That moved every qibla
azimuth by ~0.14", which the self-test read as "Vincenty and Karney agree to
0.14"" under a 0.2" tolerance -- it was the coordinate, not the formulation,
and it put the 294d15'18" site at 294d15'17.86". The tolerance is now 0.001".

It also asserts that the spherical formula and TM-3 grid north are *not* good
enough, so those traps cannot silently stop being traps.

For the gnomons it asserts that `gnomon.py`'s declination sweep reproduces the
dated 2027 numbers in the field sheet's part two (meridian ends +0.971 / -1.902
m at H 3.20, pad E -0.04..2.03 / N -1.35..0.84 m at H 1.50, June Asr shadow
2.380 m toward 123.4 deg). It also checks that a meridian line staked through
the house frame points at true north (0.015" from its own lat/lon), and that
the default line fits its room and the default pad clears the house.

## The total station (`tsspec.py`)

The GNSS hands over one azimuth, A->B. The total station has to carry it onto
the ground without adding more than the baseline already has, and the main run
now prints what it adds, for the instrument given with `--ts ISO,a,b`
(default `5,2,2`: a 5" ISO 17123-3 class, EDM 2 mm + 2 ppm), `--centring
forced|reset` and `--mark board|tripod|bipod|pole`:

```
  central line as marked: 13.30 m (bouwplank, 1.5 m past each end)
    GNSS baseline A->B (2 rx, 25.0 m)    28.6"
    orientation on B (pointing + centring)      7.4"
    marking both ends (0.5 mm each)             11.0"
    instrument, centring and marks together    15.0"
    TOTAL central-line azimuth                 32.3"  = 0.54'
```

Every polar angle is also printed as the **whole-arcsecond value to key in**,
next to the rounding that costs (never more than 0.5").

What the model says, and the self-test pins:

- **Put A on the central line's extension** (`--anchor-house 3,-4 --anchor-enu
  0,0`, 4 m behind the back wall). Then one dialled angle sets M0, M1 and both
  bouwplank marks, and EDM error runs along the line where it cannot turn it.
  Beside the line, the same 2 mm EDM matters.
- **The angle class is rarely the weak link.** 5" -> 1" buys 1.5" on a 25 m
  baseline, 0.08 mm over 10.3 m. Forced centring (the TS drops onto the GNSS
  tribrach) is worth more: a 5" instrument on it beats a 1" one re-centred over
  the nail. A hand-held prism pole at M0/M1 is 85" on its own and fails the 1'
  target with any instrument; mark lines by crosshair on the boards.
- **A small parcel cannot use a good instrument**: a short baseline swamps
  whatever the instrument adds. `--ts-table` gives, per parcel, the coarsest
  class that adds <= 12% and the class below which nothing is visible. With two
  receivers 5" is enough from 8x15 to 20x40 m, 1-2" is overkill everywhere, 20"
  is the weakest link from 10x20 up.
- **The meridian line is set from its own foot**: occupy MF, backsight B, turn
  to 0d00'00". Staking MN/MS as polar points from A with a pole is ~6': a
  2.87 m line cannot take the EDM's 2 mm.

The analytic line-azimuth error is checked against a Monte Carlo of the staking
itself (perturb orientation, pointings, distances, marks; place both ends by
polar setout; take the spread), which shares none of its algebra: 0.1% apart.
The one-receiver sigma_perp (3.91 mm) was estimated for the 7 km centroid base
and is optimistic at the integer site, 14.46 km from Purwodadi; the two-receiver
figure (2.45 mm) is the direct A->B baseline and does not depend on the CORS.
The field sheet carries the full write-up, the rental price tiers and the
attribute checklist.

## Solar gnomons

The field sheet's part two is the plan: a pinhole
meridian line under the roof for Dhuhr, a ball-nodus pad outdoors for Asr, a
measured skyline for Maghrib. Both instruments run on **true north**, not on
the house axes. True north lies 24deg15'18" from +x toward +y, a 65deg44'42"
clockwise turn from the central line on the 294deg15'18" site. So their points
come out of the same ENU frame and polar table as the corners:

| pts | what | flags |
| --- | --- | --- |
| MF, MN, MS | meridian foot (plumb of the roof aperture) and line ends | `--meridian-foot x,y` (1.95,8.80), `--meridian-h` (3.20) |
| GF, GP | ball foot pin, post 0.30 m west of it | `--pad-foot x,y`, `--pad-h` (1.50) |
| GN, GS, P1-P4 | noon-line ends, pad corners | |

`--pad-foot` defaults to a **placeholder** 4 m past the qibla wall on the
central line, because the parcel boundary is not in this repo. The tool warns
if a meridian end leaves its room or the pad overlaps the house. It does not
stake Asr arcs: their radius changes by up to 11 mm a day, so they are set
daily as H + the measured noon shadow.

`observe.js` prints praytime.js v3.2's times unrounded, next to what each
instrument should show on that date and the expected observed-minus-code offset.
`--at HH:MM:SS` gives the sun's depression for a Fajr/Isha observation. It was
validated against the NREL SPA (the sibling project's `SolarPosition`) over
every day of 2027, to within 1.6 s for every event.

`praytime.js` is npm `praytime@3.2.0` `src/praytime.js`, **byte-identical and
vendored on purpose**: it is the code under test (tarball SHA-1
`aeb2c93e9358bf076f3ddd631181bff40db844cc`, MIT, see `praytime.LICENSE`). Do
not edit or upgrade it in place; a new version is a new comparison.

## Drawing sheets for the contractor (`sheets.py`)

```bash
python3 sheets.py                          # out/SO-01.dxf SO-02.dxf SO-03.dxf + out/griya-sakha-SO.pdf
python3 sheets.py --pad-foot 3.0,14.3      # once the pad has a real place
python3 sheets.py --selftest               # 21 checks
```

The owner supplies the setout and the two instruments, so the owner supplies
their drawings too, in Indonesian, for the drafter and the site crew:

| sheet | what | scale |
| --- | --- | --- |
| SO-01 | setout plan: axes 1-3 / A-D, centrelines vs faces, bouwplank and its axis nails, A, M0, M1, C1-C4, the meridian line and the pad (placeholder), the coordinate table, the tape-check table, the handover notes | 1:100 |
| SO-02 | meridian room: plan, a section along TRUE north through MF with the solstice rays and the clear-sheet fan, the plate and angle details, where MF may move | 1:25, 1:5 |
| SO-03 | Asr gnomon: pad plan on true north with noon and Asr shadow points, elevation, post-top detail | 1:20, 1:5 |

Every number comes from `setout.py` and `gnomon.py`; nothing is retyped, because
retyping is where the centreline-vs-face and grid-north mistakes get in. The DXF
is R12 in millimetres at TRUE size, with SO-01's and SO-02's plans at real house
coordinates, so a drafter can snap to them and paste them into a site plan;
every setout point is also a DXF `POINT`. The PDF is the A3 print, one page per
sheet: print at 100%, and check the scale bar. `drawing.py` renders both from
one set of primitives, so they cannot disagree.

The self-test re-derives the field sheet's figures through this path (line ends,
pad size, the MF window 1.83-2.07 / 7.83-9.79, the 0.30 G / 0.59 G / 0.05 G clear
sheet, the Asr sky 246-303 deg at 32-45 deg), checks that the DXF closes and
defines its layers and that SO-01 carries every point at its coordinate, that the
PDF's xref is sound and every glyph encodes, and that no text leaves the frame.
It does not check how the sheets look: render the PDF and read it after a change.

What the sheets leave to others, and say so: anchor sizes (the engineer), the
post footing and the pad slab (contractor or engineer), doors and windows (the
architect), foundations (the structural drawings).

## Independent field check

`BayanganKiblat` in the sibling Qur'an project finds the moments when the
sun's azimuth equals the qibla azimuth, so a plumb line's shadow falls on the
qibla line with no magnetometer and no GNSS involved. At the 294d15'18" site
**289 of 365 days** of 2027 have a crossing and **121 moments** fall in the 12-45 deg
sun-altitude window that gives a long, sharp shadow. A plumb-bob shadow is
penumbra-limited to roughly 3', so it will not refine a 25 m GNSS baseline --
but it catches a 1 degree blunder, a sign error or a TM-3 mix-up instantly,
and it costs nothing.

## Questions for the structural engineer (`engineer/`)

`engineer/pertanyaan-insinyur-struktur.html` is the owner's one-page A4 checklist,
in Bahasa Indonesia, for the meeting with the structural engineer: who they are
and what they deliver, the ground, the seismic inputs, the detailing, the roof,
and the meridian-room anchors. The PDF beside it is rendered from the HTML with
headless Chrome; the command is in the file's header comment. It shares no code
with the rest of `setout/`. Its numbers are copied from `src/SeismicParams.kt`,
`src/SectionCapacity.kt`, `src/DesignVariant.kt` and the SO-02 notes in
`sheets.py`, so a change there needs a matching edit here. After any edit,
re-render it and check that `pdfinfo` still reports `Pages: 1`.
