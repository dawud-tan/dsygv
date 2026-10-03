"""What a rented total station has to be good enough for, on this job.

The GNSS half hands over one number: the azimuth of the A->B baseline, good to
sqrt(2) * sigma_perp / D. The total station's whole task is to carry that
azimuth from B onto the ground -- to the central line, the corners and the
gnomon lines -- without adding more error than the baseline already has. This
module prices that carry, so an instrument can be judged against the parcel it
will work on instead of against a spec sheet.

WHY THIS IS A FILE AND NOT A PARAGRAPH. The intuitive answer ("rent the best
arcsecond class you can afford") is wrong here in two ways that only show up
in the numbers, and both are cheap to get wrong on site:

  * Inside a house parcel the angle class is rarely the weak link. The error
    terms that actually move the central line are how the two ends are MARKED
    (divided by only 10.3 m) and how the instrument and the backsight target
    are CENTRED (divided by the backsight length). Either can exceed the whole
    difference between a 1" and a 5" instrument.
  * A small parcel cannot use a good instrument. A short parcel means a short
    GNSS baseline, whose error then swamps anything the instrument adds; the
    better the parcel (longer baseline), the more the instrument class matters.

Conventions, all 1-sigma:
  * sigma_iso is the ISO 17123-3 / DIN 18723 figure on the spec sheet: one
    horizontal DIRECTION measured in BOTH faces. Staking is single-face, so one
    pointing is sqrt(2) * sigma_iso, and an angle (two pointings) is 2 * sigma_iso.
  * EDM is ISO 17123-4 style, a + b*D, taken as a linear sum like the sheets do.
  * Centring and marking errors are per axis, in mm.
  * The circle takes whole arcseconds, so a dialled angle carries a uniform
    +/-0.5" rounding: 1/sqrt(12) = 0.29" 1-sigma.
"""
import math
import random

RHO = 206264.806                      # arcsec per radian

# sigma_perp at each mark, mm, as the baseline-length table in README.md was
# built from (they reproduce its 5/15/25/40 m rows to 0.01'). The one-receiver
# figure was estimated for the 7 km centroid-to-Purwodadi base; the integer-
# arcsecond site is 14.5 km out, so treat it as optimistic there. The two-
# receiver figure is the direct A->B baseline and does not depend on the CORS.
SIGMA_PERP = {1: 3.91, 2: 2.45}

# Centring, per axis, mm. "forced": the total station drops onto the tribrach
# the GNSS antenna sat in, untouched since -- what is left is the tribrach's
# seating repeatability. "reset": the tribrach was lifted and re-centred over
# the nail with an optical or laser plummet.
CENTRING = {"forced": 0.2, "reset": 1.0}

# Marking, per axis, mm: how well a staked point ends up where the crosshair
# or the prism said. "board": a pencil line on a bouwplank or a nail head, set
# under the crosshair, no pole involved. "tripod": prism on a tripod and
# tribrach over the point. "bipod": prism pole held by a bipod. "pole": prism
# pole held by hand on its circular bubble (8-10' of tilt on a 1.5 m pole).
MARK = {"board": 0.5, "tripod": 0.5, "bipod": 1.5, "pole": 3.0}

ROUND = 1.0 / math.sqrt(12.0)         # arcsec, 1-sigma of a whole-arcsecond input

CLASSES = (1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 20.0)   # arcsec, the classes a rental list shows


def sigma_gnss(D, receivers=2):
    """Azimuth error of the GNSS baseline A->B, arcsec."""
    return math.sqrt(2.0) * SIGMA_PERP[receivers] / (D * 1000.0) * RHO


def pointing(sigma_iso, faces=1):
    """One horizontal pointing, arcsec."""
    return sigma_iso * math.sqrt(2.0 / faces)


def orientation(sigma_iso, D_bs, e_centre, faces=1):
    """Orienting the circle on B: one pointing plus both centrings, arcsec.
    A's centring and B's target centring each move the direction to B by e/D."""
    c = math.sqrt(2.0) * e_centre / (D_bs * 1000.0) * RHO
    return math.hypot(pointing(sigma_iso, faces), c)


def edm(a_mm, b_ppm, r):
    """EDM distance error at r metres, mm."""
    return a_mm + b_ppm * 1e-6 * r * 1000.0


def line_azimuth_sigma(A, P0, P1, sigma_iso, edm_ab, D_bs, e_centre, e_mark,
                       faces=1, shared_pointing=None, rounding=True):
    """1-sigma azimuth error, arcsec, of the line P0->P1 as staked by polar
    setout from A, EXCLUDING the GNSS baseline (add that in quadrature).

    Points are (E, N) in metres in the ENU frame about A. Terms:
      * orientation on B: a common rotation about A, so it rotates the line by
        exactly itself;
      * one pointing per point, transverse at r_i, projected on the line normal;
      * EDM, radial at each point, projected on the line normal -- which is zero
        when A sits on the line, the whole reason to put it there;
      * marking at each end, per axis.
    shared_pointing: True when both ends are set on one dialled angle (A on the
    line), so a single pointing error rotates the line by exactly itself.
    None means "decide from the geometry"."""
    t = (P1[0] - P0[0], P1[1] - P0[1])
    L = math.hypot(*t)
    n = (-t[1] / L, t[0] / L)
    sp = math.hypot(pointing(sigma_iso, faces), ROUND if rounding else 0.0) / RHO   # rad
    so = orientation(sigma_iso, D_bs, e_centre, faces) / RHO
    if shared_pointing is None:
        shared_pointing = on_line(A, P0, P1)
    var = so * so + 2.0 * (e_mark / 1000.0) ** 2 / (L * L)
    if shared_pointing:
        var += sp * sp
    else:
        for P in (P0, P1):
            r = math.hypot(P[0] - A[0], P[1] - A[1])
            u = ((P[0] - A[0]) / r, (P[1] - A[1]) / r)
            v = (-u[1], u[0])
            nv = n[0] * v[0] + n[1] * v[1]
            nu = n[0] * u[0] + n[1] * u[1]
            var += (r * sp * nv / L) ** 2 + (edm(*edm_ab, r) / 1000.0 * nu / L) ** 2
    return math.sqrt(var) * RHO


def line_offset(A, P0, P1):
    """(across, along) of A relative to the P0->P1 line, metres: `across` is the
    perpendicular distance (+ to the right of P0->P1), `along` is measured from P0."""
    t = (P1[0] - P0[0], P1[1] - P0[1])
    L = math.hypot(*t)
    across = ((A[0] - P0[0]) * t[1] - (A[1] - P0[1]) * t[0]) / L
    along = ((A[0] - P0[0]) * t[0] + (A[1] - P0[1]) * t[1]) / L
    return across, along


def on_line(A, P0, P1, tol=0.005):
    """True when A lies on the P0-P1 line's EXTENSION (within tol metres), so both
    ends sit on one ray and one dialled angle sets them both. A between the ends
    is on the line too, but the two ends are then two pointings, 180 deg apart."""
    across, along = line_offset(A, P0, P1)
    return abs(across) < tol and (along < 0 or along > math.dist(P0, P1))


def point_sigma(r, sigma_iso, edm_ab, D_bs, e_centre, e_mark, faces=1):
    """(transverse, radial) 1-sigma of one staked point, mm: the error in the
    house's SHAPE. The orientation on B and the GNSS baseline turn every point
    about A together, which changes the qibla but not the shape, so they are in
    the azimuth budget instead of here."""
    ang = math.hypot(pointing(sigma_iso, faces), ROUND) / RHO
    tr = math.hypot(r * 1000.0 * ang, e_mark)
    ra = math.hypot(edm(*edm_ab, r), e_mark)
    return tr, ra


def monte_carlo(A, P0, P1, sigma_iso, edm_ab, D_bs, e_centre, e_mark, faces=1,
                shared_pointing=None, n=20000, seed=1):
    """The same line azimuth error by simulating the staking itself: perturb the
    orientation, the pointings, the distances and the marks, place both points by
    polar setout, and take the spread of the azimuth of what was staked. It shares
    no algebra with line_azimuth_sigma, which is the point of it."""
    rnd = random.Random(seed)
    if shared_pointing is None:
        shared_pointing = on_line(A, P0, P1)
    sp = math.hypot(pointing(sigma_iso, faces), ROUND) / RHO
    sd = pointing(sigma_iso, faces) / RHO
    D = D_bs * 1000.0
    az0 = math.atan2(P1[0] - P0[0], P1[1] - P0[1])
    out = []
    for _ in range(n):
        # orientation: pointing on B, and the two centrings seen from D_bs away
        o = rnd.gauss(0, sd) + (rnd.gauss(0, e_centre) - rnd.gauss(0, e_centre)) / D
        p_shared = rnd.gauss(0, sp)
        pts = []
        for P in (P0, P1):
            r = math.hypot(P[0] - A[0], P[1] - A[1])
            psi = math.atan2(P[0] - A[0], P[1] - A[1])
            p = p_shared if shared_pointing else rnd.gauss(0, sp)
            rr = r + rnd.gauss(0, edm(*edm_ab, r)) / 1000.0
            psi += o + p
            pts.append((A[0] + rr * math.sin(psi) + rnd.gauss(0, e_mark) / 1000.0,
                        A[1] + rr * math.cos(psi) + rnd.gauss(0, e_mark) / 1000.0))
        az = math.atan2(pts[1][0] - pts[0][0], pts[1][1] - pts[0][1])
        out.append((az - az0 + math.pi) % (2 * math.pi) - math.pi)
    m = sum(out) / n
    return math.sqrt(sum((x - m) ** 2 for x in out) / (n - 1)) * RHO


def dial(angle_deg):
    """What the keypad takes: (d, m, s) in whole arcseconds, and the rounding in arcsec."""
    total = angle_deg % 360.0 * 3600.0
    s = int(round(total))
    return (s // 3600 % 360, s // 60 % 60, s % 60), (s - total)


def fmt_dial(angle_deg):
    (d, m, s), res = dial(angle_deg)
    return f"{d:3d}°{m:02d}'{s:02d}\"", res


# ---- the parcel question ----------------------------------------------------

def baseline_for_parcel(L, W, inset=1.0):
    """Longest GNSS baseline inside an L x W parcel with both marks `inset` m
    in from both boundaries, at opposite corners."""
    return math.hypot(L - 2 * inset, W - 2 * inset)


def thresholds(sigma_rest, D_bs, e_centre, faces=1):
    """(needed, overkill) sigma_iso in arcsec for a line whose error without the
    instrument is sigma_rest arcsec, staked on one dialled angle (A on the line).
      needed   : the whole instrument term, centring included, <= sigma_rest / 2,
                 so it adds at most 12% to the total. None when centring alone
                 already breaks that: no angle class can rescue it.
      overkill : the angle part alone buys < 2% against everything else, a
                 perfect instrument included -- a better class cannot be seen."""
    c = math.sqrt(2.0) * e_centre / (D_bs * 1000.0) * RHO
    room = (sigma_rest / 2.0) ** 2 - c * c - ROUND ** 2
    needed = math.sqrt(room * faces / 4.0) if room > 0 else None
    floor = math.sqrt(sigma_rest ** 2 + c * c + ROUND ** 2)
    overkill = 0.1 * floor * math.sqrt(faces)
    return needed, overkill


def instrument_term(sigma_iso, D_bs, e_centre, faces=1):
    """Instrument's share of the central-line azimuth, one dialled angle, arcsec."""
    c = math.sqrt(2.0) * e_centre / (D_bs * 1000.0) * RHO
    return math.sqrt(4.0 / faces * sigma_iso ** 2 + ROUND ** 2 + c * c)


def verdicts(sigma_rest, D_bs, e_centre, faces=1, classes=CLASSES):
    """{class: verdict} for one parcel.
      unacceptable : the instrument term exceeds everything else -- it, not the
                     GNSS, is now the weakest link, and the baseline is wasted
      marginal     : it adds 12-41%
      enough       : it adds <= 12%
      overkill     : enough, AND a coarser class on the list is enough too, AND
                     this class's angle part buys < 2%"""
    needed, over = thresholds(sigma_rest, D_bs, e_centre, faces)
    out = {}
    for c in classes:
        term = instrument_term(c, D_bs, e_centre, faces)
        if term > sigma_rest:
            out[c] = "unacceptable"
        elif term > sigma_rest / 2.0:
            out[c] = "marginal"
        else:
            out[c] = "enough"
    for c in classes:
        coarser_ok = any(out[k] == "enough" for k in classes if k > c)
        if out[c] == "enough" and coarser_ok and c <= over:
            out[c] = "overkill"
    return out


PARCELS = ((8, 15), (10, 20), (12, 25), (15, 30), (20, 40))
CL_MARKED = 10.3 + 2 * 1.5            # central line as carried on the bouwplank, 1.5 m out each end


def report(parcels=PARCELS, receivers=2, centring="forced", mark="board",
           line=CL_MARKED, faces=1):
    lines = []
    e_c, e_m = CENTRING[centring], MARK[mark]
    sm = math.sqrt(2.0) * e_m / (line * 1000.0) * RHO
    lines.append(f"CENTRAL-LINE AZIMUTH vs PARCEL  --  {receivers} GNSS receiver(s), "
                 f"{centring} centring ({e_c} mm), marked by {mark} ({e_m} mm) over {line:.1f} m, "
                 f"{faces} face(s)")
    lines.append("A on the central line's extension, both ends set on one dialled angle,")
    lines.append("B at the far corner. 'needed' = the coarsest ISO 17123-3 class that adds <= 12%;")
    lines.append("'overkill' = this good or better buys < 2%; @5\" = the total with a 5\" instrument.")
    lines.append("")
    hdr = f"{'parcel':>8}{'D (m)':>8}{'GNSS':>8}{'mark':>7}{'rest':>7}{'needed':>9}{'overkill':>10}{'@5\"':>8}   "
    hdr += "  ".join(f"{c:>4g}\"" for c in CLASSES)
    lines.append(hdr)
    lines.append("-" * len(hdr))
    for L, W in parcels:
        D = baseline_for_parcel(L, W)
        sg = sigma_gnss(D, receivers)
        rest = math.hypot(sg, sm)
        need, over = thresholds(rest, D, e_c, faces)
        tags = verdicts(rest, D, e_c, faces)
        v = [{"unacceptable": "  NO ", "marginal": " marg", "overkill": " over",
              "enough": "  ok "}[tags[c]] for c in CLASSES]
        tot5 = math.hypot(rest, instrument_term(5.0, D, e_c, faces))
        f = lambda x: "  --  " if x is None else f"{x:6.1f}\""
        lines.append(f"{f'{L:g}x{W:g}':>8}{D:>8.1f}{sg:>7.1f}\"{sm:>6.1f}\"{rest:>6.1f}\"{f(need):>9}{f(over):>10}"
                     f"{tot5:>7.1f}\"   " + " ".join(v))
    lines.append("")
    lines.append("GNSS = baseline azimuth, mark = the two line marks, rest = both, before the instrument.")
    lines.append("Verdicts: ok adds <= 12%, marg 12-41%, NO = the instrument is the weakest link, over = a")
    lines.append("coarser class is already ok and this one's angle part buys < 2%.")
    lines.append("Instrument term = sqrt(4*sigma_iso^2 + 0.29^2 + 2*centring^2/D^2), single face.")
    return "\n".join(lines)


if __name__ == "__main__":
    print(report())
