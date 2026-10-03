#!/usr/bin/env python3
"""
Qibla-oriented setout for the Griya Sakha house (6.0 x 10.3 m, grid from
/WIN_D/protek/dsygv src/Geometry.kt).

Takes the two GNSS-derived points -- A (benchmark / instrument station) and
B (backsight) -- and emits the four corner points plus the central line, in:
  * the local ENU frame (Karney LocalCartesian about A),
  * geodetic lat/lon/h,
  * polar setout (horizontal angle off the backsight + horizontal distance),
    which is what a total station actually wants.

House frame: +y runs along the 10.3 m central line and is aimed at the Kaaba;
+x is 90 deg clockwise from it (to your right when you face the qibla).
All house coordinates are COLUMN / WALL CENTRELINES, per Geometry.kt.

It also stakes the two solar gnomons of the field sheet (part two, stages 8-12 of
http://183.81.158.231:8080/qibla-setout-field-sheet.html ,
qibla-setout-field-sheet.html in the quarkus repo):
the meridian line under the roof aperture (Dhuhr) and the outdoor ball-nodus
pad (Asr). Both run on TRUE north, not on the house axes, so their points come
out of the same ENU frame as the corners and share the same backsight.

And it prices the total station that carries all of this onto the ground
(tsspec.py): what the central line's azimuth costs through the instrument's
angle class, its centring, and the way the two ends are marked, and the
whole-arcsecond value each angle has to be dialled as.
"""
import argparse, math, sys
from geolib import (vincenty_inverse, geodetic_to_ecef, ecef_to_enu,
                    enu_to_geodetic, local_enu_azimuth, utm_convergence,
                    spherical_azimuth, dms)
import cors
import gnomon
import tsspec

# The Kaaba as the sibling projects have it: com.quran.kiblat.salat.Geodesic
# (Karney) encodes latitude 21.4225395 in its SIN_BETA_2 / COS_BETA_2, and
# coba.IntegerArcsecondQiblaDirection (etcetera repo) found the 38 whole-
# arcsecond sites on 110.9274 E against the same point. This file used 21.4225
# until 2026-09-23, 4.4 m south: that moved every qibla azimuth by ~0.14", which
# the self-test had been reading as "Vincenty and Karney agree to 0.14"" -- it
# was the coordinate, not the formulation, and it put the chosen 294d15'18"
# site at 294d15'17.86". The two agree to ~1e-9 deg on the same point.
KAABA = (21.4225395, 39.8262)

# The integer-arcsecond site the house is planned on (the field sheet, "Choosing the parcel").
INTEGER_SITE = (-6.96595767, 110.9274)
INTEGER_QIBLA = 294 + 15 / 60 + 18 / 3600    # 294d15'18"

# --- from /WIN_D/protek/dsygv src/Geometry.kt : Grid(x=[0,3,6], y=[0,3.5,7,10.3])
X_LINES = [0.0, 3.0, 6.0]
Y_LINES = [0.0, 3.5, 7.0, 10.3]
WIDTH   = X_LINES[-1] - X_LINES[0]      # 6.0  m  -- shear walls on these two faces
DEPTH   = Y_LINES[-1] - Y_LINES[0]      # 10.3 m  -- the central-line direction
WALL_T  = 0.200                          # shear wall thickness, x = 0 and x = 6
COL_D   = 0.230                          # column, on the y = 0 / y = 10.3 frames
INFILL_INT_T = 0.100                     # interior brick infill, x = 3 / y = 3.5 / y = 7

# Half-thickness of whatever stands on each grid line, for "clear room" faces.
# The y = 0 / 10.3 lines use the column (the widest thing on them), as the field sheet does.
X_HALF = {0.0: WALL_T / 2, 3.0: INFILL_INT_T / 2, 6.0: WALL_T / 2}
Y_HALF = {0.0: COL_D / 2, 3.5: INFILL_INT_T / 2, 7.0: INFILL_INT_T / 2, 10.3: COL_D / 2}

# Defaults from the field sheet's gnomon stages (9 and 10).
MERIDIAN_FOOT = (1.95, 8.80)             # qibla-end room, grid x 0-3 / y 7-10.3
MERIDIAN_H    = 3.20                     # aperture above the floor line, m
PAD_FOOT      = (3.0, 14.3)              # PLACEHOLDER: 4 m past the qibla wall on the central line
PAD_H         = 1.50                     # ball centre above the pad, m
POST_OFFSET   = 0.30                     # post is this far WEST of the ball; the arm points east

def parse_pt(s):
    v = [float(x) for x in s.split(",")]
    if len(v) == 2: v.append(0.0)
    return tuple(v)

def house_to_enu(hx, hy, theta, e0, n0):
    """+y at azimuth theta; +x at theta+90 (clockwise)."""
    t = math.radians(theta)
    return (e0 + hx * math.cos(t) + hy * math.sin(t),
            n0 - hx * math.sin(t) + hy * math.cos(t))

def enu_to_house(e, n, theta, e0, n0):
    """Inverse of house_to_enu (the rotation's transpose)."""
    t = math.radians(theta)
    de, dn = e - e0, n - n0
    return (de * math.cos(t) - dn * math.sin(t), de * math.sin(t) + dn * math.cos(t))

def north_bearing_in_house(theta):
    """Angle from house +x toward +y at which true north lies, degrees."""
    t = math.radians(theta)
    return math.degrees(math.atan2(math.cos(t), -math.sin(t))) % 360.0

def room_of(hx, hy):
    """Clear faces (x0, x1, y0, y1) of the grid bay containing a house point, or None."""
    xs, ys = sorted(X_HALF), sorted(Y_HALF)
    for a, b in zip(xs, xs[1:]):
        for c, d in zip(ys, ys[1:]):
            if a < hx < b and c < hy < d:
                return (a + X_HALF[a], b - X_HALF[b], c + Y_HALF[c], d - Y_HALF[d])
    return None

def convex_overlap(p, q):
    """Separating-axis test for two convex polygons given as vertex lists."""
    for poly in (p, q):
        for i in range(len(poly)):
            (x1, y1), (x2, y2) = poly[i], poly[(i + 1) % len(poly)]
            ax, ay = y1 - y2, x2 - x1
            pa = [ax * x + ay * y for x, y in p]
            qa = [ax * x + ay * y for x, y in q]
            if max(pa) < min(qa) or max(qa) < min(pa):
                return False
    return True

def house_outline():
    """Outer faces of the building in house coordinates."""
    x0, x1 = X_LINES[0] - WALL_T / 2, X_LINES[-1] + WALL_T / 2
    y0, y1 = Y_LINES[0] - COL_D / 2, Y_LINES[-1] + COL_D / 2
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]

def gnomon_points(lat, theta, e0, n0, mfoot, mh, pfoot, ph):
    """ENU points for the meridian line and the outdoor pad, plus what the report needs.
    Both instruments are laid out on TRUE north, which is ENU north at A."""
    n_end, s_end = gnomon.meridian_extent(lat, mh)
    fe, fn = house_to_enu(*mfoot, theta, e0, n0)
    mer = [("MF", fe, fn, "meridian foot: plumb point of the roof aperture"),
           ("MN", fe, fn + n_end, f"meridian line north end ({n_end:+.3f} m)"),
           ("MS", fe, fn + s_end, f"meridian line south end ({s_end:+.3f} m)")]
    emin, emax, nmin, nmax = gnomon.pad_extent(lat, ph)
    ge, gn = house_to_enu(*pfoot, theta, e0, n0)
    pad = [("GF", ge, gn, "gnomon foot pin: plumb point of the ball centre"),
           ("GP", ge - POST_OFFSET, gn, f"gnomon post centre ({POST_OFFSET:.2f} m west, arm points east)"),
           ("GN", ge, gn + nmax, "noon line north end"),
           ("GS", ge, gn + nmin, "noon line south end"),
           ("P1", ge + emin, gn + nmax, "pad corner NW"),
           ("P2", ge + emax, gn + nmax, "pad corner NE"),
           ("P3", ge + emax, gn + nmin, "pad corner SE"),
           ("P4", ge + emin, gn + nmin, "pad corner SW")]
    return mer, pad, (n_end, s_end), (emin, emax, nmin, nmax)

def ts_budget(args, ts_iso, ts_a, ts_b, rows, hrow, az_b_enu, d_b, theta, e0, n0,
              latA, lonA, hA, latB, lonB, hB, mfoot, m_n, m_s):
    """What the total station adds on the way from B to the ground (tsspec.py)."""
    W = 78
    e_c, e_m = tsspec.CENTRING[args.centring], tsspec.MARK[args.mark]
    edm_ab = (ts_a, ts_b)
    print()
    print("-" * W)
    print(f"TOTAL STATION BUDGET  --  {ts_iso:g}\" ISO 17123-3, EDM {ts_a:g} mm + {ts_b:g} ppm, "
          f"{args.centring} centring, marked by {args.mark}")
    print("-" * W)
    A = (0.0, 0.0)
    ext = args.bouwplank if args.mark == "board" else 0.0
    p0 = house_to_enu(WIDTH / 2, -ext, theta, e0, n0)
    p1 = house_to_enu(WIDTH / 2, DEPTH + ext, theta, e0, n0)
    across, along = tsspec.line_offset(A, p0, p1)
    Lm = math.dist(p0, p1)
    s_g = tsspec.sigma_gnss(d_b, args.receivers)
    s_o = tsspec.orientation(ts_iso, d_b, e_c)
    s_line = tsspec.line_azimuth_sigma(A, p0, p1, ts_iso, edm_ab, d_b, e_c, e_m)
    s_mark = math.sqrt(2.0) * e_m / (Lm * 1000.0) * tsspec.RHO
    total = math.hypot(s_g, s_line)
    where = ("on the extension" if tsspec.on_line(A, p0, p1)
             else "between the ends" if abs(across) < 0.005 else f"{abs(across):.3f} m off it")
    print(f"  A is {where} of the central line ({along - ext:+.3f} m along it from M0).")
    if tsspec.on_line(A, p0, p1):
        print("  Both ends sit on one ray: dial one angle, lock the circle, mark both. EDM error")
        print("  moves them along the line and cannot turn it.")
    elif abs(across) < 0.005:
        print("  The EDM cannot turn the line, but the two ends are two pointings 180 deg apart.")
    else:
        print("  EDM error at each end now has a component across the line. To put A on the")
        print(f"  central line's extension, pin the house to it:  --anchor-house {WIDTH/2:g},-4 --anchor-enu 0,0")
        print("  (A then sits 4 m behind the back wall: 2.5 m clear of a bouwplank 1.5 m out, which is")
        print("  past any instrument's minimum focus, and outside the trench it would otherwise lose A to).")
    print(f"  central line as marked: {Lm:.2f} m ({'bouwplank, %.1f m past each end' % ext if ext else 'M0 to M1'})")
    print(f"    GNSS baseline A->B ({args.receivers} rx, {d_b:.1f} m) {s_g:7.1f}\"")
    print(f"    orientation on B (pointing + centring)  {s_o:7.1f}\"")
    print(f"    marking both ends ({e_m:g} mm each)          {s_mark:7.1f}\"")
    print(f"    instrument, centring and marks together {s_line:7.1f}\"")
    print(f"    TOTAL central-line azimuth              {total:7.1f}\"  = {total/60:.2f}'   "
          f"({'within' if total <= 60 else 'OVER'} the 1' target; masonry holds 3.34')")
    far = max(rows[:6], key=lambda r: r[10])
    tr, ra = tsspec.point_sigma(far[10], ts_iso, edm_ab, d_b, e_c, e_m)
    print(f"  shape: farthest corner {far[0]} at {far[10]:.1f} m is good to {tr:.1f} mm across / {ra:.1f} mm along")
    print(f"  the ray -- against a 10 mm masonry tolerance. The EDM's ppm term is "
          f"{ts_b * far[10] / 1000:.2f} mm here; compare instruments on the fixed part.")
    need, over = tsspec.thresholds(math.hypot(s_g, s_mark), d_b, e_c)
    perfect = math.hypot(s_g, tsspec.line_azimuth_sigma(A, p0, p1, 0.0, (0.0, 0.0), d_b, e_c, e_m,
                                                          rounding=False))
    if perfect > 60.0:
        print(f"  A PERFECT instrument would still give {perfect/60:.2f}' here: what fails is the "
              f"{'marking' if s_mark > s_g else 'baseline'},")
        print("  not the angle class. Renting better cannot fix it; "
              + ("marking the line on the bouwplank by crosshair can." if s_mark > s_g
                 else "a longer A->B baseline can."))
    elif need is None:
        print(f"  With {args.centring} centring no angle class keeps the instrument under half the rest")
        print("  of the budget: fix the centring (drop the TS onto the GNSS tribrach) before renting better.")
    else:
        print(f"  For THIS baseline, any instrument of {need:.1f}\" or better is enough; below "
              f"{over:.1f}\" it buys nothing")
        print("  you could see. (python3 setout.py --ts-table for the same question by parcel size.)")

    # The meridian line is re-set from the plumb of the real aperture, so the station
    # is MF itself: the backsight is B, not the 10.3 m central line, so the line
    # inherits the baseline's azimuth and not the staked central line's marking error.
    mfe, mfn = house_to_enu(*mfoot, theta, e0, n0)
    eB, nB, _ = ecef_to_enu(*geodetic_to_ecef(latB, lonB, hB), latA, lonA, hA)
    az_mb = math.degrees(math.atan2(eB - mfe, nB - mfn)) % 360.0
    d_mb = math.hypot(eB - mfe, nB - mfn)
    reach = 3.0                            # boards this far north and south of the foot
    mer = tsspec.line_azimuth_sigma((0.0, 0.0), (0.0, -reach), (0.0, reach), ts_iso, edm_ab,
                                    d_mb, tsspec.CENTRING["reset"], e_m)
    mer_t = math.hypot(s_g, mer)
    print()
    print(f"  MERIDIAN LINE from its own foot: occupy MF, sight B ({d_mb:.2f} m), set HZ = "
          f"{tsspec.fmt_dial(az_mb)[0].strip()},")
    print(f"    then 0°00'00\" is true north and 180°00'00\" true south. Mark both on boards about")
    print(f"    {reach:g} m out, past the minimum focus (1-1.5 m) and past the line's ends "
          f"(+{m_n:.2f} / {m_s:.2f} m).")
    print(f"    azimuth {mer_t:.1f}\" = {mer_t/60:.2f}' against the page's 1' allowance "
          f"({'fits' if mer_t <= 60 else 'DOES NOT FIT'}). Staking MN/MS as two polar points")
    bad = tsspec.line_azimuth_sigma((0.0, 0.0), hrow["MS"][4:6], hrow["MN"][4:6], ts_iso, edm_ab,
                                    d_b, e_c, tsspec.MARK["pole"])
    print(f"    from A with a prism pole would be {math.hypot(s_g, bad)/60:.2f}' -- a {m_n - m_s:.2f} m line cannot "
          f"take the EDM's {ts_a:g} mm.")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--a", required=True, help="benchmark lat,lon[,h]  (instrument station)")
    p.add_argument("--b", required=True, help="backsight lat,lon[,h]")
    p.add_argument("--anchor-house", default=f"{WIDTH/2},{DEPTH/2}",
                   help="house-frame point to pin (default: centroid 3.0,5.15)")
    p.add_argument("--anchor-enu", default="0,0",
                   help="where that point lands in the ENU frame about A (default 0,0 = over A)")
    p.add_argument("--flip", action="store_true",
                   help="put the qibla wall at y=0 instead of y=10.3")
    p.add_argument("--cm", type=float, default=None, help="UTM/TM-3 central meridian, for the convergence warning")
    p.add_argument("--meridian-foot", default="%g,%g" % MERIDIAN_FOOT,
                   help="house x,y of the roof aperture's plumb point (default %g,%g)" % MERIDIAN_FOOT)
    p.add_argument("--meridian-h", type=float, default=MERIDIAN_H,
                   help="aperture height above the floor line, m (default %.2f)" % MERIDIAN_H)
    p.add_argument("--pad-foot", default=None,
                   help="house x,y of the outdoor ball's plumb point (default %g,%g is a PLACEHOLDER)" % PAD_FOOT)
    p.add_argument("--pad-h", type=float, default=PAD_H,
                   help="ball-centre height above the pad, m (default %.2f)" % PAD_H)
    p.add_argument("--ts", default="5,2,2",
                   help="total station: ISO 17123-3 angle arcsec, EDM a mm, b ppm (default 5,2,2)")
    p.add_argument("--centring", choices=sorted(tsspec.CENTRING), default="forced",
                   help="forced = TS drops onto the GNSS tribrach untouched (default); reset = re-centred over the nail")
    p.add_argument("--mark", choices=sorted(tsspec.MARK), default="board",
                   help="how line ends are marked: board (crosshair on a bouwplank, default), tripod, bipod, pole")
    p.add_argument("--bouwplank", type=float, default=1.5,
                   help="how far beyond M0/M1 the central line is carried on the bouwplank, m (default 1.5)")
    p.add_argument("--receivers", type=int, choices=(1, 2), default=2,
                   help="GNSS receivers used for A->B: 2 simultaneous (default) or 1 sequential")
    args = p.parse_args()
    ts_iso, ts_a, ts_b = (float(v) for v in args.ts.split(","))

    latA, lonA, hA = parse_pt(args.a)
    latB, lonB, hB = parse_pt(args.b)
    ahx, ahy = [float(x) for x in args.anchor_house.split(",")]
    ae, an   = [float(x) for x in args.anchor_enu.split(",")]

    # ---- 1. the two azimuths -------------------------------------------------
    _, az_q, _ = vincenty_inverse(latA, lonA, *KAABA)
    az_b_enu, d_b = local_enu_azimuth(latA, lonA, hA, latB, lonB, hB)
    d_geo, az_b_geo, _ = vincenty_inverse(latA, lonA, latB, lonB)

    theta = (az_q + 180.0) % 360.0 if args.flip else az_q
    e0, n0 = ae - (ahx * math.cos(math.radians(theta)) + ahy * math.sin(math.radians(theta))), \
             an + (ahx * math.sin(math.radians(theta)) - ahy * math.cos(math.radians(theta)))

    W = 78
    print("=" * W)
    print("QIBLA-ORIENTED SETOUT  --  Griya Sakha, %.1f x %.1f m (centrelines)" % (WIDTH, DEPTH))
    print("=" * W)
    print(f"A  benchmark / instrument  {latA:.9f}, {lonA:.9f}, h={hA:.3f} m")
    print(f"B  backsight               {latB:.9f}, {lonB:.9f}, h={hB:.3f} m")
    print()
    print(f"  qibla azimuth at A (Karney/Vincenty, WGS84) : {az_q:12.6f}  = {dms(az_q)}")
    qd, qres = tsspec.fmt_dial(az_q)
    print(f"    on a 1\" circle this reads {qd.strip()}  ({abs(qres):.2f}\" from the whole arcsecond"
          + ("; an integer-arcsecond site)" if abs(qres) < 0.05 else ")"))
    print(f"  baseline A->B, LocalCartesian ENU atan2(E,N): {az_b_enu:12.6f}  = {dms(az_b_enu)}")
    print(f"  baseline A->B, geodesic inverse             : {az_b_geo:12.6f}  (diff {(az_b_enu-az_b_geo)*3600:+.4f}\")")
    print(f"  baseline length: {d_b:.4f} m horizontal / {d_geo:.4f} m geodesic")
    sig = tsspec.sigma_gnss(d_b, args.receivers) / 3600.0 if d_b > 0 else float('nan')
    print(f"  -> GNSS azimuth sigma, {args.receivers} receiver(s): {sig*60:.2f}'  "
          f"({DEPTH*math.tan(math.radians(sig))*1000:.1f} mm across the {DEPTH:.1f} m central line)")
    if args.cm is not None:
        g = utm_convergence(latA, lonA, args.cm)
        print(f"  grid convergence vs CM {args.cm}: {g:+.6f} deg = {dms(abs(g))} "
              f"-- do NOT set out from grid north")
    print(f"  house +y (central line) aimed at azimuth {theta:.6f}"
          f"{'  [FLIPPED: qibla wall at y=0]' if args.flip else ''}")
    print()

    pts = [("M0", WIDTH / 2, 0.0,    "central line, back end"),
           ("M1", WIDTH / 2, DEPTH,  "central line, QIBLA end"),
           ("C1", 0.0,   0.0,   "corner  x=0    y=0     (shear wall / back)"),
           ("C2", WIDTH, 0.0,   "corner  x=6.0  y=0     (shear wall / back)"),
           ("C3", WIDTH, DEPTH, "corner  x=6.0  y=10.3  (shear wall / QIBLA)"),
           ("C4", 0.0,   DEPTH, "corner  x=0    y=10.3  (shear wall / QIBLA)")]

    def row(name, hx, hy, note, e, n):
        la, lo, ht = enu_to_geodetic(e, n, 0.0, latA, lonA, hA)
        return (name, hx, hy, note, e, n, la, lo, ht, math.degrees(math.atan2(e, n)) % 360.0, math.hypot(e, n))

    rows = []
    for name, hx, hy, note in pts:
        e, n = house_to_enu(hx, hy, theta, e0, n0)
        rows.append(row(name, hx, hy, note, e, n))

    # ---- 2. the solar gnomons (field sheet, stages 8-12) ----------------------
    mfoot = tuple(float(v) for v in args.meridian_foot.split(","))
    pfoot = tuple(float(v) for v in args.pad_foot.split(",")) if args.pad_foot else PAD_FOOT
    mer, pad, (m_n, m_s), (emin, emax, nmin, nmax) = gnomon_points(
        latA, theta, e0, n0, mfoot, args.meridian_h, pfoot, args.pad_h)
    for name, e, n, note in mer + pad:
        rows.append(row(name, *enu_to_house(e, n, theta, e0, n0), note, e, n))
    hrow = {r[0]: r for r in rows}

    nb = north_bearing_in_house(theta)
    print("-" * W)
    print("SOLAR GNOMONS  --  field sheet stages 8-12; both run on TRUE north, not the house axes")
    print("-" * W)
    print(f"  true north in the house frame: {dms(nb)} from +x toward +y"
          f"  = turn {dms((360.0 - theta) % 360.0)} clockwise from +y (the central line)")
    print(f"  MERIDIAN LINE (Dhuhr): aperture {args.meridian_h:.3f} m above the line, foot at house "
          f"({mfoot[0]:.3f}, {mfoot[1]:.3f})")
    print(f"    noon image travels {m_n - 0.025:+.3f} m .. {m_s + 0.025:+.3f} m (+N) of the foot; "
          f"line {m_n:+.3f} .. {m_s:+.3f} m = {m_n - m_s:.3f} m with half an image each end")
    room = room_of(*mfoot)
    if room is None:
        print("    ! the foot is not inside any room of the grid")
    else:
        x0, x1, y0, y1 = room
        inside = all(x0 <= hrow[k][1] <= x1 and y0 <= hrow[k][2] <= y1 for k in ("MN", "MS"))
        print(f"    room clear faces x {x0:.3f}..{x1:.3f}, y {y0:.3f}..{y1:.3f}: "
              + ("both ends inside" if inside else "! AN END FALLS OUTSIDE THE ROOM -- move --meridian-foot"))
    print(f"    fix the aperture to steel spanning the ring beams, never to the light-steel truss")
    print(f"  OUTDOOR PAD (Asr): ball centre {args.pad_h:.3f} m above the pad, foot at house "
          f"({pfoot[0]:.3f}, {pfoot[1]:.3f})"
          + ("" if args.pad_foot else "  <-- PLACEHOLDER, pass --pad-foot x,y"))
    print(f"    pad from the foot: east {emin:+.2f} .. {emax:+.2f} m, north {nmin:+.2f} .. {nmax:+.2f} m "
          f"= {emax - emin:.2f} x {nmax - nmin:.2f} m, level to +/-0.5 mm")
    pad_poly = [(hrow[k][1], hrow[k][2]) for k in ("P1", "P2", "P3", "P4")]
    if convex_overlap(pad_poly, house_outline()):
        print("    ! THE PAD OVERLAPS THE HOUSE -- move --pad-foot")
    print(f"    Asr arcs are NOT staked: the radius changes up to 11 mm a day. Set it daily as H + noon shadow.")
    print()

    print("-" * W)
    print("LOCAL CARTESIAN (ENU about A)          and  WGS84")
    print("-" * W)
    print(f"{'pt':<4}{'house x':>9}{'house y':>9}{'E (m)':>11}{'N (m)':>11}   {'latitude':>14} {'longitude':>15}")
    for r in rows:
        print(f"{r[0]:<4}{r[1]:>9.3f}{r[2]:>9.3f}{r[4]:>11.4f}{r[5]:>11.4f}   {r[6]:>14.9f} {r[7]:>15.9f}")
    print()
    for r in rows:
        print(f"   {r[0]}  {r[3]}")

    print()
    print("-" * W)
    print("POLAR SETOUT  --  instrument over A, backsight B, HORIZONTAL CIRCLE ZEROED ON B")
    print("-" * W)
    print(f"{'pt':<4}{'turn right by':>16}{'dial (1\")':>14}{'rounding':>10}{'horiz. dist':>13}")
    for r in rows:
        ang = (r[9] - az_b_enu) % 360.0
        dd, res = tsspec.fmt_dial(ang)
        print(f"{r[0]:<4}{ang:>16.6f}{dd:>14}{res:>+9.2f}\"{r[10]:>13.4f}")
    print()
    hz, hres = tsspec.fmt_dial(az_b_enu)
    print(f"   (If you prefer to orient the circle instead: set HZ = {hz.strip()} while sighting B")
    print(f"    ({hres:+.2f}\" of rounding, once, for every point), and every reading becomes a true azimuth:")
    print("    " + ", ".join(f"{r[0]}={tsspec.fmt_dial(r[9])[0].strip()}" for r in rows) + ")")
    print(f"    The keypad takes whole arcseconds, so each dialled angle carries up to 0.5\" of")
    print(f"    rounding. On an integer-arcsecond site the qibla itself carries none -- the central")
    print(f"    line reads {tsspec.fmt_dial(theta)[0].strip()} exactly -- but the backsight still does.")

    ts_budget(args, ts_iso, ts_a, ts_b, rows, hrow, az_b_enu, d_b, theta, e0, n0,
              latA, lonA, hA, latB, lonB, hB, mfoot, m_n, m_s)

    print()
    print("-" * W)
    print("INDEPENDENT CHECKS AFTER STAKING  (tape only -- no instrument)")
    print("-" * W)
    d = {r[0]: (r[4], r[5]) for r in rows}
    def L(a, b): return math.dist(d[a], d[b])
    print(f"   C1-C2 (back wall)     {L('C1','C2'):9.4f} m   expect {WIDTH:.4f}")
    print(f"   C4-C3 (qibla wall)    {L('C4','C3'):9.4f} m   expect {WIDTH:.4f}")
    print(f"   C1-C4 (side/shear)    {L('C1','C4'):9.4f} m   expect {DEPTH:.4f}")
    print(f"   C2-C3 (side/shear)    {L('C2','C3'):9.4f} m   expect {DEPTH:.4f}")
    diag = math.hypot(WIDTH, DEPTH)
    print(f"   C1-C3 diagonal        {L('C1','C3'):9.4f} m   expect {diag:.4f}")
    print(f"   C2-C4 diagonal        {L('C2','C4'):9.4f} m   expect {diag:.4f}")
    print(f"   M0-M1 central line    {L('M0','M1'):9.4f} m   expect {DEPTH:.4f}")
    print("   The two diagonals being equal is what proves the box is square;")
    print("   it says nothing about the orientation, which only A->B fixes.")
    print()
    print(f"   MS-MN meridian line   {L('MS','MN'):9.4f} m   expect {m_n - m_s:.4f}")
    print(f"   MF-MN                 {L('MF','MN'):9.4f} m   expect {m_n:.4f}")
    print(f"   GS-GN noon line       {L('GS','GN'):9.4f} m   expect {nmax - nmin:.4f}")
    pd = math.hypot(emax - emin, nmax - nmin)
    print(f"   P1-P3 / P2-P4 pad     {L('P1','P3'):9.4f} / {L('P2','P4'):.4f} m   expect {pd:.4f}")
    g = {r[0]: r for r in rows}
    for a, b in (("MS", "MN"), ("GS", "GN")):
        _, az_line, _ = vincenty_inverse(g[a][6], g[a][7], g[b][6], g[b][7])
        dev = (az_line + 180.0) % 360.0 - 180.0
        print(f"   {a}->{b} bearing from its own lat/lon (Vincenty): {dev * 3600:+.3f}\"   expect 0 (true north)")
    print("   The meridian line is also checkable with no instrument at all: the")
    print("   Indian circle with the aperture image (field sheet, stage 9).")

    print()
    print("-" * W)
    print("WHAT THESE POINTS ARE  --  read this before driving a single peg")
    print("-" * W)
    print(f"   M0..C4 are COLUMN / WALL CENTRELINES, matching the")
    print(f"   Grid(x=[0,3,6], y=[0,3.5,7,10.3]) that Geometry.kt analyses.")
    print(f"   MF..P4 are the gnomon marks themselves. MF is where the aperture must")
    print(f"   hang; once it is fixed, the plumb of the REAL hole is the foot, and")
    print(f"   MN/MS are re-set from it along true north. Stake them while the floor")
    print(f"   is still open, before the qibla-room walls hide the backsight.")
    print(f"   Outside faces, if you need them:")
    print(f"     x = 0 and x = 6.0 are {WALL_T*1000:.0f} mm shear walls")
    print(f"        -> outer faces at x = {-WALL_T/2:+.3f} and x = {WIDTH+WALL_T/2:.3f}  "
          f"(overall {WIDTH+WALL_T:.3f} m)")
    print(f"     y = 0 and y = 10.3 carry {COL_D*1000:.0f} mm columns")
    print(f"        -> column faces at y = {-COL_D/2:+.3f} and y = {DEPTH+COL_D/2:.3f}  "
          f"(overall {DEPTH+COL_D:.3f} m)")
    print(f"   Staking the wrong one is a {WALL_T/2*1000:.0f}-{COL_D/2*1000:.0f} mm error --")
    print(f"   an order of magnitude larger than anything the azimuth can do to you.")

# ---------------------------------------------------------------------------
# SELF TEST
#
# Every number this tool emits rests on two claims that are easy to get wrong
# and impossible to notice in the field: that the ENU frame's north really is
# geodetic north, and that the rotation into house coordinates preserves the
# azimuth. Both are checked here against an independent geodesic solution, in
# the same spirit as Verification.kt -- a tool that stakes concrete should be
# able to prove itself before anyone drives a peg.
# ---------------------------------------------------------------------------

def selftest():
    ok = True
    def check(name, cond, detail=""):
        nonlocal ok
        ok = ok and cond
        print(f"  [{'PASS' if cond else 'FAIL'}] {name}{('  ' + detail) if detail else ''}")

    latA, lonA, hA = -7.05, 110.96, 90.0

    # 1. LocalCartesian ENU azimuth vs the geodesic inverse, over a realistic
    #    baseline. These are different computations (3D chord in a local frame
    #    vs. an integral along the ellipsoid) and must agree far below the
    #    0.3 arcmin the GNSS itself can deliver.
    latB, lonB, hB = -7.0497, 110.9603, 90.2
    az_enu, d_enu = local_enu_azimuth(latA, lonA, hA, latB, lonB, hB)
    d_geo, az_geo, _ = vincenty_inverse(latA, lonA, latB, lonB)
    da = abs(az_enu - az_geo) * 3600.0
    check("ENU azimuth == geodesic azimuth", da < 0.1, f"diff {da:.4f}\" over {d_enu:.1f} m")

    # 2. Round trip: push a point out into ENU, bring it back to geodetic,
    #    and confirm it lands where it started.
    for e, n in ((0, 0), (5.15, 0), (-3.0, 7.2), (100.0, -100.0)):
        la, lo, ht = enu_to_geodetic(e, n, 0.0, latA, lonA, hA)
        e2, n2, u2 = ecef_to_enu(*geodetic_to_ecef(la, lo, ht), latA, lonA, hA)
        err = math.hypot(e2 - e, n2 - n) * 1000.0
        check(f"ENU->geodetic->ENU round trip ({e},{n})", err < 0.01, f"{err:.6f} mm")

    # 3. End to end: build the central line at the qibla azimuth, convert it to
    #    lat/lon, then ask the geodesic solver what azimuth that line actually
    #    has to the Kaaba. This is the claim the whole house rests on.
    _, azq, _ = vincenty_inverse(latA, lonA, *KAABA)
    e0, n0 = -(WIDTH / 2) * math.cos(math.radians(azq)) - (DEPTH / 2) * math.sin(math.radians(azq)), \
             (WIDTH / 2) * math.sin(math.radians(azq)) - (DEPTH / 2) * math.cos(math.radians(azq))
    m0 = house_to_enu(WIDTH / 2, 0.0, azq, e0, n0)
    m1 = house_to_enu(WIDTH / 2, DEPTH, azq, e0, n0)
    g0 = enu_to_geodetic(m0[0], m0[1], 0.0, latA, lonA, hA)
    g1 = enu_to_geodetic(m1[0], m1[1], 0.0, latA, lonA, hA)
    s_line, az_line, _ = vincenty_inverse(g0[0], g0[1], g1[0], g1[1])
    _, az_kaaba, _ = vincenty_inverse(g0[0], g0[1], *KAABA)
    resid = abs(az_line - az_kaaba) * 3600.0
    check("staked central line points at the Kaaba", resid < 0.1, f"residual {resid:+.3f}\"")
    check("central line is 10.3 m long", abs(s_line - DEPTH) < 1e-3, f"{s_line:.4f} m")

    # 4. Geometry matches Geometry.kt's Grid(x=[0,3,6], y=[0,3.5,7,10.3]).
    check("footprint 6.0 x 10.3", (WIDTH, DEPTH) == (6.0, 10.3), f"{WIDTH} x {DEPTH}")
    corners = [house_to_enu(x, y, azq, e0, n0) for x, y in
               ((0, 0), (WIDTH, 0), (WIDTH, DEPTH), (0, DEPTH))]
    d1 = math.dist(corners[0], corners[2]); d2 = math.dist(corners[1], corners[3])
    check("diagonals equal", abs(d1 - d2) < 1e-9, f"{d1:.6f} / {d2:.6f} m")
    check("diagonal == hypot(6.0, 10.3)", abs(d1 - math.hypot(WIDTH, DEPTH)) < 1e-9)

    # 5. Regression on the qibla azimuth itself. The reference is this repo's
    #    sibling project: com.quran.kiblat.salat.Geodesic (Karney) returns
    #    294.267388007 deg at this point. Vincenty is an independent
    #    formulation, so agreement is a real cross-check and not a tautology --
    #    and on the SAME Kaaba coordinate the two agree to ~1e-9 deg. The old
    #    0.2" tolerance hid a 4.4 m difference in the Kaaba itself (see KAABA).
    check("qibla azimuth vs Karney implementation",
          abs(azq - 294.267388007) * 3600.0 < 0.001, f"{azq:.9f} deg")

    # 5b. The house site was chosen by coba.IntegerArcsecondQiblaDirection for a
    #     qibla of exactly 294d15'18". If this tool disagrees with that search,
    #     every "integer-arcsecond" claim downstream is off by the difference.
    _, az_int, _ = vincenty_inverse(*INTEGER_SITE, *KAABA)
    check("integer-arcsecond site reads exactly 294d15'18\"",
          abs(az_int - INTEGER_QIBLA) * 3600.0 < 0.01, f"{(az_int - INTEGER_QIBLA) * 3600:+.5f}\"")

    # 6. The traps, asserted so they cannot silently stop being true.
    sph = spherical_azimuth(latA, lonA, *KAABA)
    check("spherical formula is NOT good enough", abs(sph - azq) * 60.0 > 5.0,
          f"costs {(sph - azq) * 60:+.2f} arcmin")
    g_tm3 = utm_convergence(latA, lonA, 109.5)
    check("TM-3 grid north is NOT true north", abs(g_tm3) * 60.0 > 5.0,
          f"costs {g_tm3 * 60:+.2f} arcmin")

    # 7. The gnomon geometry reproduces the field sheet's part two. Those numbers
    #    came from a dated 2027 run (observe.js, cross-checked against the NREL
    #    SPA) at the 294d15'18" integer-arcsecond site; gnomon.py gets them from
    #    a declination sweep instead, so agreement is a real cross-check.
    lat_g = -6.96595767
    m_n, m_s = gnomon.meridian_extent(lat_g, MERIDIAN_H)
    check("meridian line ends match the field sheet (+0.971 / -1.902 m at H 3.20)",
          abs(m_n - 0.971) < 0.002 and abs(m_s + 1.902) < 0.002, f"{m_n:+.4f} / {m_s:+.4f} m")
    ext = gnomon.pad_extent(lat_g, PAD_H)
    check("pad extent matches the field sheet (E -0.04..2.03, N -1.35..0.84)",
          all(abs(a - b) < 0.01 for a, b in zip(ext, (-0.04, 2.03, -1.35, 0.84))),
          "E %+.3f..%+.3f  N %+.3f..%+.3f" % ext)
    _, _, L_jun, d_jun = gnomon.asr_point(lat_g, gnomon.OBLIQUITY, PAD_H)
    check("June-solstice Asr radius and direction match observe.js (2.380 m, 123.4 deg)",
          abs(L_jun - 2.380) < 0.003 and abs(d_jun - 123.4) < 0.15, f"{L_jun:.4f} m, {d_jun:.2f} deg")
    check("true north sits 24d15'18\" from +x on the 294d15'18\" site",
          abs(north_bearing_in_house(294 + 15 / 60 + 18 / 3600) - (24 + 15 / 60 + 18 / 3600)) * 3600 < 1e-6)

    # 8. A meridian line staked through the house frame really runs to true north:
    #    build it at the test site, carry both ends to lat/lon, and ask the
    #    geodesic solver for the bearing between them.
    mer, pad, _, _ = gnomon_points(latA, azq, e0, n0, MERIDIAN_FOOT, MERIDIAN_H, PAD_FOOT, PAD_H)
    ends = {k: enu_to_geodetic(e, n, 0.0, latA, lonA, hA) for k, e, n, _ in mer}
    _, az_mer, _ = vincenty_inverse(ends["MS"][0], ends["MS"][1], ends["MN"][0], ends["MN"][1])
    dev = ((az_mer + 180.0) % 360.0 - 180.0) * 3600.0
    check("staked meridian line points at true north", abs(dev) < 0.1, f"{dev:+.4f}\"")

    # 9. The defaults actually fit: meridian ends inside the qibla-end room, pad clear of the house.
    x0, x1, y0, y1 = room_of(*MERIDIAN_FOOT)
    hx = {k: enu_to_house(e, n, azq, e0, n0) for k, e, n, _ in mer + pad}
    check("default meridian line lies inside its room",
          all(x0 <= hx[k][0] <= x1 and y0 <= hx[k][1] <= y1 for k in ("MN", "MS")),
          f"room x {x0:.3f}..{x1:.3f} y {y0:.3f}..{y1:.3f}")
    check("default pad does not overlap the house",
          not convex_overlap([hx[k] for k in ("P1", "P2", "P3", "P4")], house_outline()))
    check("overlap test is live (a pad on the house is caught)",
          convex_overlap([(2, 2), (4, 2), (4, 4), (2, 4)], house_outline()))

    # 10. The total-station model (tsspec.py) against a simulation of the staking
    #     itself, which shares none of its algebra: perturb orientation, pointings,
    #     distances and marks, place both ends by polar setout, and take the spread
    #     of what was staked. Once with A on the central line's extension (one
    #     dialled angle), once with A 5 m to the side (where the EDM bites).
    for label, P0, P1 in (("A on the line", (0.0, 3.0), (0.0, 13.3)),
                          ("A 5 m to the side", (5.0, -5.15), (5.0, 5.15))):
        an = tsspec.line_azimuth_sigma((0, 0), P0, P1, 5.0, (2.0, 2.0), 25.0, 0.2, 0.5)
        mc = tsspec.monte_carlo((0, 0), P0, P1, 5.0, (2.0, 2.0), 25.0, 0.2, 0.5)
        check(f"line-azimuth model == simulation, {label}", abs(mc / an - 1) < 0.03,
              f"{an:.2f}\" vs {mc:.2f}\"")

    # 11. The reason to put A on the line: with it there, no EDM error, however
    #     large, can turn the line. Off the line, the same EDM plainly does.
    on = [tsspec.line_azimuth_sigma((0, 0), (0.0, 3.0), (0.0, 13.3), 5.0, (a, 0.0), 25.0, 0.2, 0.5)
          for a in (0.0, 50.0)]
    off = [tsspec.line_azimuth_sigma((0, 0), (5.0, -5.15), (5.0, 5.15), 5.0, (a, 0.0), 25.0, 0.2, 0.5)
           for a in (0.0, 50.0)]
    check("EDM cannot turn a line A stands on; it does turn one A stands beside",
          abs(on[1] - on[0]) < 1e-9 and off[1] > off[0] + 100,
          f"on {on[1] - on[0]:.1e}\", off +{off[1] - off[0]:.0f}\"")

    # 12. Whole-arcsecond dialling never rounds by more than half an arcsecond,
    #     and the true-north turn from the integer-arcsecond central line is itself
    #     a whole number of arcseconds (field sheet, #integer).
    worst = max(abs(tsspec.dial(k * 0.0123457)[1]) for k in range(1, 30000))
    (d, m, sec), res = tsspec.dial(360.0 - INTEGER_QIBLA)
    check("dialled angles round by <= 0.5\"; the north turn is 65d44'42\" exactly",
          worst <= 0.5 + 1e-9 and (d, m, sec) == (65, 44, 42) and abs(res) < 1e-6,
          f"worst {worst:.3f}\"")

    # 13. The parcel verdicts keep their shape: a 5" instrument is enough on every
    #     parcel in the table, 1" is never needed, and a 20" one is the weakest
    #     link from 10 x 20 m up. If the model changes enough to flip one of these,
    #     the field sheet's parcel table is stale.
    v = {}
    for L, W in tsspec.PARCELS:
        D = tsspec.baseline_for_parcel(L, W)
        rest = math.hypot(tsspec.sigma_gnss(D, 2), math.sqrt(2) * 0.5 / (tsspec.CL_MARKED * 1000) * tsspec.RHO)
        v[(L, W)] = tsspec.verdicts(rest, D, tsspec.CENTRING["forced"])
    check("parcel table: 5\" enough everywhere, 1\" overkill everywhere, 20\" out from 10x20",
          all(x[5.0] in ("enough", "overkill") for x in v.values())
          and all(x[1.0] == "overkill" for x in v.values())
          and all(x[20.0] == "unacceptable" for k, x in v.items() if k != (8, 15)),
          " ".join(f"{L}x{W}:{x[5.0][:4]}/{x[20.0][:4]}" for (L, W), x in v.items()))

    print(f"\n  {'ALL CHECKS PASSED' if ok else 'FAILURES ABOVE'}")
    return 0 if ok else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    if "--ts-table" in sys.argv:
        i = sys.argv.index("--ts-table")
        sizes = [tuple(float(v) for v in a.lower().split("x")) for a in sys.argv[i + 1:]
                 if "x" in a.lower() and not a.startswith("-")]
        kw = {}
        for flag, key, conv in (("--receivers", "receivers", int), ("--centring", "centring", str),
                                ("--mark", "mark", str)):
            if flag in sys.argv:
                kw[key] = conv(sys.argv[sys.argv.index(flag) + 1])
        if kw.get("mark") not in (None, "board"):
            kw["line"] = DEPTH                # no bouwplank reach without a board
        print(tsspec.report(parcels=sizes or tsspec.PARCELS, **kw))
        sys.exit(0)
    if "--cors" in sys.argv:
        i = sys.argv.index("--cors")
        rest = [a for a in sys.argv[i + 1:] if not a.startswith("-")]
        if rest:
            la, lo = [float(x) for x in rest[0].split(",")[:2]]
            print(cors.report(la, lo))
        else:
            print(cors.report())
        sys.exit(0)
    main()
