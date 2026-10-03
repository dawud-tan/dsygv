#!/usr/bin/env python3
"""Drawing sheets for the owner-supplied items, as DXF (true-size CAD) and PDF (A3 print).

  SO-01  Denah uitzet & as bangunan   setout plan, 1:100, house frame at real coordinates
  SO-02  Ruang meridian               meridian room plan + meridian section 1:25, details 1:5
  SO-03  Gnomon Asr                   pad plan + elevation 1:20, detail 1:5

Why generated and not drawn: every number on these sheets already lives in
setout.py / gnomon.py, where --selftest pins it. A drafter retyping them is
exactly where the centreline-vs-face (100-115 mm), grid-north (+-11') and
decimal-separator mistakes get in. So the sheets are produced from the same
constants, and this file's --selftest checks that the PDF, the DXF and those
constants agree.

What these sheets are NOT: structural drawings. Anchor sizes, the post footing
and the pad slab are the engineer's; the sheets say so where it matters. The
owner supplies the setout (the contract says so; see CLAUDE.md "Who owns the
marks"), so SO-01 is the owner's document.

    python3 sheets.py                    # writes out/SO-01..03.dxf and out/griya-sakha-SO.pdf
    python3 sheets.py --out DIR --pad-foot 3.0,14.3
    python3 sheets.py --selftest
"""
import argparse
import datetime
import math
import os
import re
import subprocess
import sys
import zlib

import gnomon
import setout
from drawing import Sheet, text_width_mm, write_dxf, write_pdf, pdf_bytes, dxf_text

LAT = setout.INTEGER_SITE[0]
THETA = setout.INTEGER_QIBLA                     # house +y azimuth: 294d15'18"
NB = setout.north_bearing_in_house(THETA)        # true north, degrees from +x toward +y

# Sizes the field sheet specifies (stages 9 and 10) and that setout.py does not carry.
A_BEHIND = 4.0            # A on the central line's extension, this far behind M0 (setout.py's advice)
INFILL_EXT_T = 0.150      # AAC on y = 0 / 10.3: QuantityTakeoff T_INFILL_EXTERIOR
BEAM_B, BEAM_H = 0.230, 0.300                    # ring beam, Sections.BEAM
STOREY_H = 3.5            # Grid zRoof - zBase: ring beam top above the sloof
BOUWPLANK_OUT = 1.5       # ring of profile boards beyond the outer faces
APERTURE_D = 0.003        # 3 mm hole
PLATE = 0.150             # 150 x 150 mm aperture plate
ANGLE_LEG, ANGLE_T = 0.050, 0.005                # L50x50x5
ANGLE_KG_M = (2 * ANGLE_LEG - ANGLE_T) * ANGLE_T * 7850.0
HOLE_FROM_LEG = 0.075     # hole at the plate centre, the plate's edge on the angle's vertical leg
BALL_D = 0.060
POST_W = 0.060
FENCE_CLEAR = 0.10        # fence top at least this far below the ball centre
G_EXAMPLE = 1.0           # roof height above the aperture used for the worked example

FRAME = (10.0, 10.0, 410.0, 287.0)
TB_TOP = 36.0             # title block occupies FRAME bottom .. TB_TOP


# ------------------------------------------------------------------ numbers --

def dms_s(deg):
    """Whole-arcsecond d°mm'ss", the form a total station keypad takes."""
    s = int(round(abs(deg) * 3600.0))
    return f"{'-' if deg < 0 else ''}{s // 3600}°{(s % 3600) // 60:02d}'{s % 60:02d}\""


def house(e, n):
    """A true-north (east, north) vector or point about the house origin, in house x, y."""
    return setout.enu_to_house(e, n, THETA, 0.0, 0.0)


def geometry(pad_foot):
    """Everything the three sheets draw, in one place, from setout.py and gnomon.py."""
    g = {}
    g["xl"], g["yl"] = setout.X_LINES, setout.Y_LINES
    g["outline"] = setout.house_outline()
    mer, pad, (m_n, m_s), (emin, emax, nmin, nmax) = setout.gnomon_points(
        LAT, THETA, 0.0, 0.0, setout.MERIDIAN_FOOT, setout.MERIDIAN_H, pad_foot, setout.PAD_H)
    g["m_n"], g["m_s"] = m_n, m_s
    g["pad_ext"] = (emin, emax, nmin, nmax)
    pts = [("A", setout.WIDTH / 2, -A_BEHIND, "stasiun alat, perpanjangan garis tengah"),
           ("M0", setout.WIDTH / 2, 0.0, "garis tengah, ujung belakang"),
           ("M1", setout.WIDTH / 2, setout.DEPTH, "garis tengah, ujung KIBLAT"),
           ("C1", 0.0, 0.0, "sudut as 1 / as A"),
           ("C2", setout.WIDTH, 0.0, "sudut as 3 / as A"),
           ("C3", setout.WIDTH, setout.DEPTH, "sudut as 3 / as D (sisi kiblat)"),
           ("C4", 0.0, setout.DEPTH, "sudut as 1 / as D (sisi kiblat)")]
    desc = {"MF": "kaki meridian (rencana; ditetapkan dari lubang)",
            "MN": "ujung utara garis meridian", "MS": "ujung selatan garis meridian",
            "GF": "pin kaki gnomon (di bawah pusat bola)",
            "GP": f"pusat tiang ({setout.POST_OFFSET:.2f} m barat GF)",
            "GN": "ujung utara garis siang", "GS": "ujung selatan garis siang",
            "P1": "sudut pad barat laut", "P2": "sudut pad timur laut",
            "P3": "sudut pad tenggara", "P4": "sudut pad barat daya"}
    for name, e, n, _ in mer + pad:
        hx, hy = house(e, n)
        pts.append((name, hx, hy, desc[name]))
    g["pts"] = pts
    g["P"] = {p[0]: (p[1], p[2]) for p in pts}
    g["room"] = setout.room_of(*setout.MERIDIAN_FOOT)          # clear faces x0, x1, y0, y1
    # The meridian direction in the house frame, and where it leaves the room.
    ux, uy = math.cos(math.radians(NB)), math.sin(math.radians(NB))
    g["u"] = (ux, uy)
    fx, fy = setout.MERIDIAN_FOOT
    x0, x1, y0, y1 = g["room"]
    s_n = min((x1 - fx) / ux, (y1 - fy) / uy)
    s_s = -min((fx - x0) / ux, (fy - y0) / uy)
    g["s_room"] = (s_s, s_n)
    # Where the foot may sit so that both line ends stay in the room.
    g["foot_window"] = (x0 - m_s * ux, x1 - m_n * ux, y0 - m_s * uy, y1 - m_n * uy)
    # Noon image centres at the solstices, and the zenith angles behind them.
    H = setout.MERIDIAN_H
    g["img_dec"] = gnomon.noon_offset(LAT, -gnomon.OBLIQUITY, H)   # December, north end
    g["img_jun"] = gnomon.noon_offset(LAT, gnomon.OBLIQUITY, H)    # June, south end
    g["tan_n"] = math.tan(math.radians(gnomon.noon_zenith_app(LAT, gnomon.OBLIQUITY)))
    g["tan_s"] = -math.tan(math.radians(gnomon.noon_zenith_app(LAT, -gnomon.OBLIQUITY)))
    # East-west half-width of the noon +-10 min track per metre above the aperture.
    p = math.radians(LAT)
    ew = 0.0
    for i in range(181):
        d = math.radians(-gnomon.OBLIQUITY + 2.0 * gnomon.OBLIQUITY * i / 180.0)
        h = math.radians(2.5)
        up = math.sin(p) * math.sin(d) + math.cos(p) * math.cos(d) * math.cos(h)
        ew = max(ew, abs(math.cos(d) * math.sin(h) / up))
    g["tan_ew"] = ew
    # Clear distance the angle's 50 mm vertical leg needs from the hole, on the worse side.
    g["leg_clear"] = ANGLE_LEG * max(g["tan_n"], g["tan_s"]) * abs(ux)
    g["angle_len"] = (setout.Y_LINES[3] - BEAM_B / 2) - (setout.Y_LINES[2] + BEAM_B / 2)
    g["angle_kg"] = g["angle_len"] * ANGLE_KG_M
    # Asr at the pad: shadow points for three declinations, and sun geometry at Asr.
    g["asr"] = [(dec, gnomon.asr_point(LAT, dec, setout.PAD_H)) for dec in
                (gnomon.OBLIQUITY, 0.0, -gnomon.OBLIQUITY)]
    g["noon_pad"] = [(dec, gnomon.noon_offset(LAT, dec, setout.PAD_H)) for dec in
                     (gnomon.OBLIQUITY, 0.0, -gnomon.OBLIQUITY)]
    az, alt = [], []
    for i in range(361):
        dec = -gnomon.OBLIQUITY + 2.0 * gnomon.OBLIQUITY * i / 360.0
        _, _, L, direction = gnomon.asr_point(LAT, dec, setout.PAD_H)
        az.append((direction + 180.0) % 360.0)
        alt.append(math.degrees(math.atan(setout.PAD_H / L)))
    g["asr_az"], g["asr_alt"] = (min(az), max(az)), (min(alt), max(alt))
    # Fastest daily change of the Asr radius. The declination moves at
    # (2 pi / year) sin(eps) cos(lambda) / cos(dec) per day, fastest at the
    # equinoxes and standing still at the solstices; sweep the solar longitude.
    eps = math.radians(gnomon.OBLIQUITY)
    worst = 0.0
    for i in range(720):
        lam = 2.0 * math.pi * i / 720.0
        dec = math.degrees(math.asin(math.sin(eps) * math.sin(lam)))
        rate = 2.0 * math.pi / 365.2422 * math.sin(eps) * math.cos(lam) / math.cos(math.radians(dec))
        a = gnomon.asr_point(LAT, dec, setout.PAD_H)[2]
        b = gnomon.asr_point(LAT, dec + math.degrees(rate), setout.PAD_H)[2]
        worst = max(worst, abs(b - a))
    g["asr_dr_day"] = worst
    return g


# -------------------------------------------------------------- primitives --

def wrap(text, width, h, bold=False):
    out, cur = [], ""
    for w in text.split():
        trial = (cur + " " + w).strip()
        if cur and text_width_mm(trial, h, bold) > width:
            out.append(cur)
            cur = w
        else:
            cur = trial
    if cur:
        out.append(cur)
    return out


def notes(pv, x, y_top, width, items, title="CATATAN", h=1.8, lead=2.75, start=1):
    """Numbered notes with a hanging indent. Returns the y below the last line."""
    pv.text((x, y_top), title, h=2.2, bold=True)
    y = y_top - 4.2
    for i, item in enumerate(items, start):
        tag = f"{i}."
        for j, ln in enumerate(wrap(item, width - 6.0, h)):
            if j == 0:
                pv.text((x, y), tag, h=h)
            pv.text((x + 6.0, y), ln, h=h)
            y -= lead
        y -= 0.8
    return y


def table(pv, x, y_top, cols, rows, h=1.9, row_h=4.0, title=None):
    """cols: (header, width, align). Returns the y of the bottom edge."""
    if title:
        pv.text((x, y_top + 1.8), title, h=2.2, bold=True)
    width = sum(c[1] for c in cols)
    all_rows = [[c[0] for c in cols]] + rows
    y = y_top
    for r, row in enumerate(all_rows):
        cx = x
        for (hdr, w, align), val in zip(cols, row):
            tx = {"l": cx + 1.2, "r": cx + w - 1.2, "c": cx + w / 2}[align]
            pv.text((tx, y - row_h / 2), str(val), h=h, align=align, valign="m", bold=(r == 0))
            cx += w
        y -= row_h
        pv.line((x, y), (x + width, y), "TIPIS")
    pv.rect(x, y, x + width, y_top, "TIPIS")
    cx = x
    for _, w, _ in cols[:-1]:
        cx += w
        pv.line((cx, y), (cx, y_top), "TIPIS")
    pv.line((x, y_top - row_h), (x + width, y_top - row_h), "TIPIS")
    return y


def dim_h(v, x0, x1, y, y_ref, text=None, h=1.8):
    """Horizontal dimension at y, extension lines from y_ref. Text above the line."""
    t = v.m(1.2)
    for x in (x0, x1):
        v.line((x, y_ref), (x, y + math.copysign(v.m(1.2), y - y_ref)), "UKURAN")
        v.line((x - t, y - t), (x + t, y + t), "UKURAN")
    v.line((x0 - v.m(1.5), y), (x1 + v.m(1.5), y), "UKURAN")
    v.text(((x0 + x1) / 2, y + v.m(0.8)), text or f"{abs(x1 - x0) * 1000:.0f}", h=h, align="c",
           layer="UKURAN")


def dim_v(v, y0, y1, x, x_ref, text=None, h=1.8):
    """Vertical dimension at x, extension lines from x_ref. Text left of the line, rotated."""
    t = v.m(1.2)
    for y in (y0, y1):
        v.line((x_ref, y), (x + math.copysign(v.m(1.2), x - x_ref), y), "UKURAN")
        v.line((x - t, y - t), (x + t, y + t), "UKURAN")
    v.line((x, y0 - v.m(1.5)), (x, y1 + v.m(1.5)), "UKURAN")
    v.text((x - v.m(0.8), (y0 + y1) / 2), text or f"{abs(y1 - y0) * 1000:.0f}", h=h, align="c",
           rot=90.0, layer="UKURAN")


def bubble(v, p, label):
    v.circle(p, v.m(3.2), "AS")
    v.text(p, label, h=2.6, align="c", valign="m", bold=True)


def mark(v, p, name, dx=1.6, dy=1.4, align="l", layer="TITIK", h=2.0):
    s = v.m(1.6)
    v.line((p[0] - s, p[1]), (p[0] + s, p[1]), layer)
    v.line((p[0], p[1] - s), (p[0], p[1] + s), layer)
    v.circle(p, v.m(0.9), layer)
    v.point(p, layer)
    if name:
        v.text((p[0] + v.m(dx), p[1] + v.m(dy)), name, h=h, layer="TITIK", align=align, bold=True)


def arrow(v, p, angle_deg, length_mm, label, layer="TEKS", h=2.4):
    """Arrow from p, `angle_deg` from +x counter-clockwise, length in paper mm."""
    ux, uy = math.cos(math.radians(angle_deg)), math.sin(math.radians(angle_deg))
    L = v.m(length_mm)
    tip = (p[0] + ux * L, p[1] + uy * L)
    v.line(p, tip, layer)
    a, w = v.m(2.8), v.m(1.1)
    base = (tip[0] - ux * a, tip[1] - uy * a)
    v.poly([tip, (base[0] - uy * w, base[1] + ux * w), (base[0] + uy * w, base[1] - ux * w)],
           layer, closed=True)
    lab = (tip[0] + ux * v.m(2.5), tip[1] + uy * v.m(2.5))
    v.text(lab, label, h=h, align="c", valign="m", bold=True, layer=layer)


def scale_bar(pv, x, y, scale, length_m, step_m):
    k = 1000.0 / scale
    n = int(round(length_m / step_m))
    for i in range(n):
        x0, x1 = x + i * step_m * k, x + (i + 1) * step_m * k
        pv.rect(x0, y, x1, y + 1.2, "TIPIS", fill=False)
        if i % 2 == 0:
            pv.line((x0, y + 0.6), (x1, y + 0.6), "KERTAS")
        pv.text((x0, y - 2.6), f"{i * step_m:g}", h=1.6, align="c")
    pv.text((x + n * step_m * k, y - 2.6), f"{length_m:g} m", h=1.6, align="c")
    pv.text((x, y + 2.6), f"SKALA 1:{scale:g}", h=1.8, bold=True)


def title_block(sheet, pv, scale_text, status, date, source):
    x0, y0, x1, y1 = FRAME
    pv.rect(x0, y0, x1, y1, "KERTAS")
    pv.rect(x0, y0, x1, TB_TOP, "KERTAS")
    cols = [x0, 118.0, 256.0, 296.0, 346.0, x1]
    for c in cols[1:-1]:
        pv.line((c, y0), (c, TB_TOP), "KERTAS")

    def cell(i, label, value, vh=2.6, bold=True, sub=None):
        pv.text((cols[i] + 2.0, TB_TOP - 3.4), label, h=1.5)
        pv.text((cols[i] + 2.0, TB_TOP - 9.4), value, h=vh, bold=bold)
        if sub:
            for j, s in enumerate(sub):
                pv.text((cols[i] + 2.0, TB_TOP - 14.6 - 3.6 * j), s, h=1.7)

    cell(0, "PROYEK", "RUMAH GRIYA SAKHA", sub=["Sumber, Jatipohon, Grobogan",
                                                  f"kiblat {dms_s(THETA)} dari utara sejati"])
    cell(1, "GAMBAR", sheet.title, sub=wrap(status, cols[2] - cols[1] - 4.0, 1.7))
    cell(2, "NO. LEMBAR", sheet.number, vh=4.2)
    cell(3, "SKALA (A3)", scale_text, vh=2.2, sub=["satuan: mm", "koordinat: m"])
    cell(4, "TANGGAL", date, vh=2.2, bold=False,
         sub=["dibuat oleh: Pemilik"] + wrap(source, cols[5] - cols[4] - 4.0, 1.7))
    pv.text((x0 + 1.5, TB_TOP + 1.5), "Cetak 100% pada A3, bukan 'fit to page'; periksa batang skala. "
            "DXF: ukuran sebenarnya dalam mm.", h=1.6)


# ------------------------------------------------------------------- sheets --

def so01(g, meta):
    sh = Sheet("SO-01", "DENAH UITZET & AS BANGUNAN", 100)
    v = sh.view(100, (46.0, 92.0), main=True)
    pv = sh.paper()
    xl, yl = g["xl"], g["yl"]
    (ox0, oy0), (ox1, _), (_, oy1) = g["outline"][0], g["outline"][1], g["outline"][2]
    bx0, by0, bx1, by1 = ox0 - BOUWPLANK_OUT, oy0 - BOUWPLANK_OUT, ox1 + BOUWPLANK_OUT, oy1 + BOUWPLANK_OUT
    P = g["P"]

    # Axes and bubbles: numbers across (x), letters along (y).
    for x, lab in zip(xl, "123"):
        v.line((x, by0 - 0.3), (x, 15.6), "AS")
        bubble(v, (x, 15.6 + v.m(3.2)), lab)
    for y, lab in zip(yl, "ABCD"):
        v.line((bx0 - 0.3, y), (bx1 + 0.3, y), "AS")
        bubble(v, (bx0 - 0.3 - v.m(3.2), y), lab)

    # Structure: RC shear walls on x = 0 / 6, columns everywhere, infill between.
    wt, cd = setout.WALL_T, setout.COL_D
    for x in (xl[0], xl[-1]):
        v.rect(x - wt / 2, yl[0], x + wt / 2, yl[-1], "BETON", fill=True)
    for x in xl:
        for y in yl:
            v.rect(x - cd / 2, y - cd / 2, x + cd / 2, y + cd / 2, "BETON", fill=True)
    for y in yl:
        t = INFILL_EXT_T if y in (yl[0], yl[-1]) else setout.INFILL_INT_T
        for a, b in zip(xl, xl[1:]):
            v.rect(a + cd / 2, y - t / 2, b - cd / 2, y + t / 2, "DINDING")
    xm = xl[1]
    for a, b in zip(yl, yl[1:]):
        v.rect(xm - setout.INFILL_INT_T / 2, a + cd / 2, xm + setout.INFILL_INT_T / 2, b - cd / 2, "DINDING")

    # Bouwplank ring, and the axis nails on it.
    v.rect(bx0, by0, bx1, by1, "BOUWPLANK")
    for x in xl:
        for y in (by0, by1):
            v.line((x, y - v.m(1.2)), (x, y + v.m(1.2)), "TITIK")
    for y in yl:
        for x in (bx0, bx1):
            v.line((x - v.m(1.2), y), (x + v.m(1.2), y), "TITIK")
    v.text((bx0 + v.m(1.5), by1 + v.m(1.2)), "bouwplank, tanda paku as", h=1.8, layer="BOUWPLANK")

    # The central line: from A through M0 and M1 to the far board.
    A = P["A"]
    v.line(A, (A[0], by1), "GARIS-KIBLAT")
    v.text((A[0] + v.m(1.6), A[1] - v.m(6.6)), "as 2 = garis kiblat", h=1.8, layer="GARIS-KIBLAT")

    # Setout points of the house.
    offs = {"A": (1.6, -3.4), "M0": (1.6, -3.4), "M1": (1.6, 1.4), "C1": (-1.6, -3.4, "r"),
            "C2": (1.6, -3.4), "C3": (1.6, 1.4), "C4": (-1.6, 1.4, "r")}
    for name, o in offs.items():
        mark(v, P[name], name, o[0], o[1], o[2] if len(o) > 2 else "l")

    # Meridian room and its line.
    rx0, rx1, ry0, ry1 = g["room"]
    v.text(((rx0 + rx1) / 2, ry1 - v.m(2.8)), "RUANG MERIDIAN", h=1.6, align="c")
    v.text(((rx0 + rx1) / 2, ry1 - v.m(5.2)), "(SO-02)", h=1.6, align="c")
    v.line(P["MS"], P["MN"], "MERIDIAN")
    mark(v, P["MF"], "MF", 1.6, -3.2)

    # Every table point is also a DXF POINT at its exact coordinate, for snapping.
    for name in ("MN", "MS", "GP", "GN", "GS", "P1", "P2", "P3", "P4"):
        v.point(P[name])

    # Asr pad at its placeholder.
    pad = [P["P1"], P["P2"], P["P3"], P["P4"]]
    v.poly(pad, "SEMENTARA", closed=True)
    v.line(P["GS"], P["GN"], "SEMENTARA")
    mark(v, P["GF"], "GF", 1.6, 1.4, layer="SEMENTARA")
    v.rect(P["GP"][0] - POST_W / 2, P["GP"][1] - POST_W / 2, P["GP"][0] + POST_W / 2,
           P["GP"][1] + POST_W / 2, "SEMENTARA")
    top = max(p[1] for p in pad)
    v.text((min(p[0] for p in pad), top + v.m(1.5)), "PAD GNOMON ASR (SO-03): SEMENTARA", h=1.7,
           layer="SEMENTARA")

    # Wall labels.
    v.text((xl[0] - wt / 2 - v.m(2.2), (yl[0] + yl[-1]) / 2), "dinding geser beton t=200", h=1.7,
           align="c", valign="m", rot=90.0)
    v.text((xl[-1] + wt / 2 + v.m(2.2), (yl[0] + yl[-1]) / 2), "dinding geser beton t=200", h=1.7,
           align="c", valign="m", rot=90.0)
    v.text((xl[0] + 1.5, oy1 + v.m(2.4)), f"bata ringan t={INFILL_EXT_T * 1000:.0f}", h=1.6, align="c")
    v.text((xl[0] + 1.5, oy0 - v.m(3.8)), f"bata ringan t={INFILL_EXT_T * 1000:.0f}", h=1.6, align="c")

    # Dimensions: along x at the bottom, along y on the right.
    for a, b in zip(xl, xl[1:]):
        dim_h(v, a, b, -2.3, oy0 - 0.2)
    dim_h(v, xl[0], xl[-1], -2.9, oy0 - 0.2)
    dim_h(v, ox0, ox1, -3.5, oy0 - 0.2, f"{(ox1 - ox0) * 1000:.0f} (muka luar)")
    for a, b in zip(yl, yl[1:]):
        dim_v(v, a, b, bx1 + 0.6, ox1 + 0.2)
    dim_v(v, yl[0], yl[-1], bx1 + 1.2, ox1 + 0.2)
    dim_v(v, oy0, oy1, bx1 + 1.8, ox1 + 0.2, f"{(oy1 - oy0) * 1000:.0f} (muka luar)")

    # Orientation: true north and the qibla from one point.
    o = (bx0 + 0.2, 14.0)
    arrow(v, o, NB, 16.0, "U")
    arrow(v, o, 90.0, 16.0, "KIBLAT", layer="GARIS-KIBLAT")
    v.arc(o, v.m(7.0), NB, 90.0, "TIPIS")
    mid = math.radians(72.0)
    v.text((o[0] + math.cos(mid) * v.m(9.0), o[1] + math.sin(mid) * v.m(9.0)), dms_s(90.0 - NB),
           h=1.6, valign="m")
    v.text((o[0] - v.m(2.0), o[1] - v.m(5.0)), "U = utara SEJATI,", h=1.6)
    v.text((o[0] - v.m(2.0), o[1] - v.m(7.6)), f"{dms_s(NB)} dari +x ke +y", h=1.6)

    scale_bar(pv, 18.0, 46.0, 100, 5.0, 1.0)
    pv.text((16.0, 280.5), "DENAH UITZET", h=2.8, bold=True)
    pv.text((16.0, 276.0), "sistem koordinat rumah: +y = garis tengah ke kiblat, +x = 90° searah "
            "jarum jam", h=1.6)

    # Points table.
    rows = []
    for name, x, y, d in g["pts"]:
        star = "*" if name in ("GF", "GP", "GN", "GS", "P1", "P2", "P3", "P4") else ""
        rows.append([name + star, f"{x:.3f}", f"{y:.3f}", d])
    yb = table(pv, 152.0, 278.0, [("Titik", 12.0, "l"), ("x (m)", 17.0, "r"), ("y (m)", 17.0, "r"),
                                   ("Keterangan", 110.0, "l")], rows, row_h=3.9,
               title="KOORDINAT TITIK UITZET (as, sistem rumah)")
    pv.text((152.0, yb - 3.4), "* posisi SEMENTARA sampai batas tanah dan pagar barat diketahui.", h=1.6)

    # Tape check.
    pairs = [("C1", "C2"), ("C4", "C3"), ("C1", "C4"), ("C2", "C3"), ("C1", "C3"), ("C2", "C4"),
             ("A", "M0"), ("A", "M1")]
    trows = [[f"{a}-{b}", f"{math.dist(P[a], P[b]):.4f}"] for a, b in pairs]
    table(pv, 316.0, 278.0, [("Ukur", 16.0, "l"), ("m", 18.0, "r")], trows, row_h=3.9,
          title="PITA UKUR")
    pv.text((316.0, 278.0 - 3.9 * 9 - 3.4), "as ke as, datar", h=1.6)

    items = [
        "Semua ukuran ke AS (sumbu) kolom dan dinding, BUKAN muka dinding. Muka luar: dinding geser "
        f"x = {ox0:.3f} / {ox1:.3f}, kolom y = {oy0:.3f} / {oy1:.3f}. Salah acuan = salah "
        f"{wt / 2 * 1000:.0f}-{cd / 2 * 1000:.0f} mm.",
        f"Arah: as 2 (garis tengah) menghadap kiblat {dms_s(THETA)} dari UTARA SEJATI. Jangan pakai "
        "kompas, utara peta/sertifikat (TM-3, selisih sekitar 11'), atau sejajar batas tanah.",
        "Uitzet (titik A dan B, garis tengah, titik sudut, tanda paku pada bouwplank) dikerjakan dan "
        "menjadi tanggung jawab PEMILIK. Kontraktor menyediakan dan memasang papan bouwplank, "
        "menjaganya tetap utuh, dan membangun sesuai tanda tersebut.",
        "Sebelum galian: pemeriksaan bersama Pemilik dan Kontraktor, yaitu 4 sisi dan 2 diagonal "
        "(tabel PITA UKUR), jarak A-B, dan bayangan unting-unting di M0 pada jam di lembar lapangan. "
        "Hasil dicatat dalam Berita Acara Serah Terima Uitzet, ditandatangani kedua pihak.",
        "Bila bouwplank atau patok terganggu: kontraktor menghentikan pekerjaan pada garis itu dan "
        "melapor; pemilik memasang ulang tanda.",
        "Toleransi pasangan 10 mm terhadap tanda as.",
        f"A dipasang {A_BEHIND:g} m di belakang M0 pada perpanjangan garis tengah. B (titik "
        "belakang) sejauh mungkin dari A, minimal 15 m dan sebaiknya 25 m atau lebih, saling "
        "terlihat dengan A; letaknya mengikuti batas tanah.",
        "Sudut polar untuk total station dicetak oleh setout.py pada hari pengukuran dari koordinat "
        "GNSS A dan B. Gambar ini memberi koordinat rumah saja.",
        "Dinding pengisi digambar tanpa bukaan; pintu dan jendela menurut gambar arsitek. Ukuran "
        "pondasi dan galian menurut gambar struktur.",
        "Garis meridian (MF, MN, MS) dan pad gnomon (GF..P4) memakai UTARA SEJATI, bukan sumbu rumah.",
    ]
    notes(pv, 152.0, yb - 9.0, 253.0, items)

    title_block(sh, pv, "1:100", "Untuk pelaksanaan uitzet dan serah terima bouwplank.",
                meta["date"], meta["source"])
    return sh


def so02(g, meta):
    sh = Sheet("SO-02", "RUANG MERIDIAN (DZUHUR)", 25)
    v = sh.view(25, (34.0, -164.0), main=True)
    pv = sh.paper()
    xl, yl = g["xl"], g["yl"]
    wt, cd, it = setout.WALL_T, setout.COL_D, setout.INFILL_INT_T
    fx, fy = setout.MERIDIAN_FOOT
    rx0, rx1, ry0, ry1 = g["room"]
    P = g["P"]
    ux, uy = g["u"]
    H = setout.MERIDIAN_H

    # ---- plan, house frame at true coordinates
    ylo, yhi = 6.60, 10.62
    v.rect(xl[0] - wt / 2, ylo, xl[0] + wt / 2, yhi, "BETON", fill=True)
    for x in (xl[0], xl[1]):
        for y in (yl[2], yl[3]):
            v.rect(x - cd / 2, y - cd / 2, x + cd / 2, y + cd / 2, "BETON", fill=True)
    v.rect(xl[1] - it / 2, yl[2] + cd / 2, xl[1] + it / 2, yl[3] - cd / 2, "DINDING")
    v.rect(xl[1] - it / 2, ylo, xl[1] + it / 2, yl[2] - cd / 2, "DINDING")
    v.rect(xl[0] + cd / 2, yl[2] - it / 2, xl[1] - cd / 2, yl[2] + it / 2, "DINDING")
    v.rect(xl[0] + cd / 2, yl[3] - INFILL_EXT_T / 2, xl[1] - cd / 2, yl[3] + INFILL_EXT_T / 2, "DINDING")
    for y in (yl[2], yl[3]):
        v.rect(xl[0] - 0.1, y - BEAM_B / 2, xl[1] + 0.1, y + BEAM_B / 2, "TERSEMBUNYI")
    for x, lab in zip(xl[:2], "12"):
        v.line((x, ylo - 0.05), (x, yhi + 0.02), "AS")
        bubble(v, (x, yhi + 0.02 + v.m(3.2)), lab)
    for y, lab in zip(yl[2:], "CD"):
        v.line((xl[0] - 0.35, y), (xl[1] + 0.25, y), "AS")
        bubble(v, (xl[0] - 0.35 - v.m(3.2), y), lab)

    # Steel angle and plate, overhead.
    xv = fx - HOLE_FROM_LEG
    a0, a1 = yl[2] + BEAM_B / 2, yl[3] - BEAM_B / 2
    v.rect(xv, a0, xv + ANGLE_LEG, a1, "TERSEMBUNYI")
    v.rect(fx - PLATE / 2, fy - PLATE / 2, fx + PLATE / 2, fy + PLATE / 2, "TERSEMBUNYI")
    v.text((xv - v.m(2.4), a0 + 0.08), "siku L50.50.5 di atas, as C ke as D", h=1.5, rot=90.0)
    v.text((1.5, yl[3] + BEAM_B / 2 + v.m(0.8)), "ring balok 230x300 (di atas)", h=1.5, align="c")

    # The line, the foot and the window the foot may move in.
    wx0, wx1, wy0, wy1 = g["foot_window"]
    v.rect(wx0, wy0, wx1, wy1, "SEMENTARA")
    v.text((wx1 + v.m(1.0), wy0 + v.m(0.5)), "daerah MF", h=1.5, layer="SEMENTARA")
    v.line(P["MS"], P["MN"], "MERIDIAN")
    mark(v, P["MF"], "MF", 1.5, -3.4)
    mark(v, P["MN"], "MN (U)", 1.2, 1.2)
    mark(v, P["MS"], "MS (S)", 1.2, -3.4)
    L = g["m_n"] - g["m_s"]
    at = (P["MS"][0] + 0.3 * (P["MN"][0] - P["MS"][0]), P["MS"][1] + 0.3 * (P["MN"][1] - P["MS"][1]))
    v.text((at[0] + uy * v.m(3.6), at[1] - ux * v.m(3.6)), f"strip 3 mm, L = {L * 1000:.0f}, UTARA SEJATI",
           h=1.5, rot=NB, align="c", layer="MERIDIAN")

    # The clear roof sheet's footprint for the worked example.
    G = G_EXAMPLE
    ew = math.ceil(g["tan_ew"] * 100.0) / 100.0
    corners = [house(-ew * G, -g["tan_s"] * G), house(ew * G, -g["tan_s"] * G),
               house(ew * G, g["tan_n"] * G), house(-ew * G, g["tan_n"] * G)]
    v.poly([(fx + c[0], fy + c[1]) for c in corners], "SINAR", closed=True)

    # Dimensions.
    dim_h(v, rx0, rx1, 6.73, ry0 - 0.05)
    dim_h(v, xl[0], fx, 6.66 - v.m(4.0), fy, f"{fx * 1000:.0f}")
    dim_v(v, ry0, ry1, xl[1] + 0.22, rx1 + 0.05)
    dim_v(v, yl[2], fy, xl[0] - 0.18, fx - 0.15, f"{(fy - yl[2]) * 1000:.0f}")
    arrow(v, (2.45, 7.28), NB, 11.0, "U")
    pv.text((16.0, 281.0), "DENAH RUANG MERIDIAN  1:25", h=2.6, bold=True)
    pv.text((16.0, 277.0), f"ruang bersih {(rx1 - rx0) * 1000:.0f} x {(ry1 - ry0) * 1000:.0f}; "
            f"garis putus = di atas kepala; oranye = atap bening untuk G = {G_EXAMPLE:g} m", h=1.6)

    # ---- section along true north through MF: s along north, z above the floor line
    s_s, s_n = g["s_room"]
    sv = sh.view(25, (272.0, 62.0))
    cut1 = wt / abs(ux)
    sv.rect(s_s - cut1, 0.0, s_s, STOREY_H, "BETON", fill=True)
    sv.rect(s_n, 0.0, s_n + it / abs(ux), STOREY_H - BEAM_H, "DINDING")
    b0 = (xl[1] - BEAM_B / 2 - fx) / ux
    b1 = (xl[1] + BEAM_B / 2 - fx) / ux
    sv.rect(b0, STOREY_H - BEAM_H, b1, STOREY_H, "BETON", fill=True)
    sv.line((s_s, 0.0), (s_n, 0.0), "BETON")
    sv.line((s_s - cut1, -0.12), (s_n + it / abs(ux), -0.12), "TIPIS")
    sv.line((g["m_s"], 0.012), (g["m_n"], 0.012), "MERIDIAN")
    # Plate and angle, cut obliquely.
    pc = (PLATE / 2) / abs(ux)
    sv.rect(-pc, H - 0.004, pc, H, "BETON", fill=True)
    sl = -HOLE_FROM_LEG / abs(ux)
    sv.poly([(sl, H), (sl, H + ANGLE_LEG), (sl + ANGLE_T / abs(ux), H + ANGLE_LEG),
             (sl + ANGLE_T / abs(ux), H + ANGLE_T), (sl + ANGLE_LEG / abs(ux), H + ANGLE_T),
             (sl + ANGLE_LEG / abs(ux), H)], "BETON", closed=True, fill=True)
    # Sun rays through the hole, and up to the example roof.
    for img, tan_up, lab in ((g["img_jun"], g["tan_n"], "21 Jun"), (g["img_dec"], -g["tan_s"], "21 Des")):
        sv.line((0.0, H), (img, 0.0), "SINAR")
        sv.line((0.0, H), (tan_up * G, H + G), "SINAR")
        sv.text((img, sv.m(2.2)), lab, h=1.6, align="c", layer="SINAR")
    sv.line((0.0, 0.0), (0.0, H + G), "SINAR")
    sv.text((sv.m(3.6), 0.6), "zenit (deklinasi = lintang)", h=1.6, rot=90.0, layer="SINAR")
    sv.line((-0.55, H + G), (0.85, H + G), "TERSEMBUNYI")
    sv.text((-0.55, H + G + sv.m(1.2)), f"contoh atap G = {G:g} m di atas lubang", h=1.6)
    dim_h(sv, -g["tan_s"] * G, 0.0, H + G + sv.m(7.0), H + G, f"{g['tan_s']:.2f} G")
    dim_h(sv, 0.0, g["tan_n"] * G, H + G + sv.m(7.0), H + G, f"{g['tan_n']:.2f} G")
    dim_h(sv, g["m_s"], 0.0, -0.22, -0.02)
    dim_h(sv, 0.0, g["m_n"], -0.22, -0.02)
    dim_h(sv, g["m_s"], g["m_n"], -0.36, -0.02, f"{L * 1000:.0f} strip")
    dim_v(sv, 0.0, H, s_n + it / abs(ux) + 0.22, 0.02, f"H = {H * 1000:.0f} (target)")
    sv.text((s_s + 0.05, 2.9), "S", h=2.4, bold=True)
    sv.text((s_n - 0.05, 2.9), "U", h=2.4, bold=True, align="r")
    sv.text((sl - sv.m(1.0), H + sv.m(3.0)), "siku + pelat", h=1.6, align="r")
    sv.text((s_s - cut1 / 2, 1.6), "dinding geser as 1", h=1.6, rot=90.0, align="c", valign="m")
    pv.text((178.0, 247.0), "POTONGAN MERIDIAN  1:25", h=2.6, bold=True)
    pv.text((178.0, 242.8), "bidang utara-selatan sejati melalui MF; dinding terpotong miring", h=1.6)

    # ---- details 1:5 (house x across, z up), centred on the hole
    dv = sh.view(5, (372.0 - fx * 200.0, 150.0 - H * 200.0))
    dv.poly([(xv, H), (xv, H + ANGLE_LEG), (xv + ANGLE_T, H + ANGLE_LEG), (xv + ANGLE_T, H + ANGLE_T),
             (xv + ANGLE_LEG, H + ANGLE_T), (xv + ANGLE_LEG, H)], "BETON", closed=True, fill=True)
    dv.rect(fx - PLATE / 2, H - 0.004, fx - APERTURE_D / 2, H, "BETON", fill=True)
    dv.rect(fx + APERTURE_D / 2, H - 0.004, fx + PLATE / 2, H, "BETON", fill=True)
    dv.line((xv + ANGLE_LEG / 2, H + ANGLE_T + 0.012), (xv + ANGLE_LEG / 2, H - 0.012), "TEKS")
    up = 0.07
    dv.line((fx, H), (fx + g["tan_n"] * ux * up, H + up), "SINAR")
    dv.line((fx, H), (fx - g["tan_s"] * ux * up, H + up), "SINAR")
    dim_h(dv, xv, fx, H - 0.03, H - 0.005, f"{HOLE_FROM_LEG * 1000:.0f}")
    dim_h(dv, fx - PLATE / 2, fx + PLATE / 2, H - 0.045, H - 0.005)
    dv.text((fx + 0.03, H + 0.05), "kerucut sinar bebas", h=1.5, layer="SINAR")
    dv.text((xv - 0.005, H + ANGLE_LEG + 0.006), "L50.50.5", h=1.6, align="c")
    dv.text((xv - 0.008, H - 0.012), "baut", h=1.5, align="r")
    pv.text((340.0, 205.0), "DETAIL SIKU + PELAT  1:5", h=2.2, bold=True)
    pv.text((340.0, 201.0), "potongan tegak lurus siku", h=1.5)

    pp = sh.view(5, (372.0 - fx * 200.0, 238.0 - fy * 200.0))
    pp.rect(fx - PLATE / 2, fy - PLATE / 2, fx + PLATE / 2, fy + PLATE / 2, "BETON")
    pp.circle((fx, fy), APERTURE_D / 2, "MERIDIAN", fill=False)
    for d in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        pp.line((fx + d[0] * 0.006, fy + d[1] * 0.006), (fx + d[0] * 0.02, fy + d[1] * 0.02), "TIPIS")
    pp.rect(xv, fy - PLATE / 2 - 0.01, xv + ANGLE_LEG, fy + PLATE / 2 + 0.01, "TERSEMBUNYI")
    pp.text((fx, fy - PLATE / 2 - pp.m(3.0)), f"{PLATE * 1000:.0f} x {PLATE * 1000:.0f}", h=1.6, align="c")
    pp.text((fx + 0.008, fy + 0.006), f"Ø{APERTURE_D * 1000:.0f}", h=1.6)
    pv.text((340.0, 262.0), "DETAIL PELAT LUBANG  1:5", h=2.2, bold=True)
    pv.text((340.0, 258.0), "denah; siku putus-putus", h=1.5)

    need = math.ceil(g["leg_clear"] * 1000.0 / 5.0) * 5.0
    items = [
        f"Lubang Ø{APERTURE_D * 1000:.0f} mm dibor dan dihaluskan pada shim kuningan/stainless "
        f"0.3 mm, dipasang pada pelat {PLATE * 1000:.0f}x{PLATE * 1000:.0f} yang DATAR. Tinggi lubang "
        f"di atas garis lantai H = {H:.3f} m (target). Ukur H nyata setelah terpasang, 1 mm, dan "
        "catat untuk pemilik.",
        f"Siku L50.50.5 membentang antara ring balok as C dan as D (panjang bersih "
        f"{g['angle_len'] * 1000:.0f}, sekitar {g['angle_kg']:.0f} kg), dijangkar ke SISI ring balok "
        "pada setengah tinggi, di antara tulangan 2+2 D12 dan sengkang. Ukuran dan posisi angkur "
        "disetujui insinyur struktur; deteksi tulangan sebelum mengebor.",
        "JANGAN digantung pada rangka atap baja ringan atau plafon: bergerak beberapa mm oleh panas "
        "dan angin, dan 1 mm = 4 detik.",
        f"Lubang minimal {need:.0f} mm dari kaki tegak siku (di gambar {HOLE_FROM_LEG * 1000:.0f}); "
        "kerucut sinar di atas lubang bebas dari siku, plafon dan rangka.",
        f"Atap: satu lembar di atas lubang diganti polikarbonat BENING profil sama (bukan opal/susu). "
        f"Bagian bening pada tinggi G di atas lubang: {g['tan_s']:.2f} G ke selatan sampai "
        f"{g['tan_n']:.2f} G ke utara, {ew:.2f} G ke timur dan barat (G = {G:g} m: "
        f"{(g['tan_s'] + g['tan_n']) * G:.2f} x {2 * ew * G:.2f} m). Kedap air oleh kontraktor atap.",
        f"Garis meridian: strip kuningan/stainless lebar 3 mm, rata lantai, panjang {L * 1000:.0f} "
        f"({g['m_n'] * 1000:.0f} ke utara, {-g['m_s'] * 1000:.0f} ke selatan dari MF), arah UTARA "
        f"SEJATI = {dms_s(NB)} dari +x ke +y. Musim pertama: kawat baja 0.5 mm yang ditegangkan dulu, "
        "agar masih bisa digeser.",
        "MF ditetapkan dari lubang yang SUDAH terpasang (unting-unting laser, 0.5 mm), bukan dari "
        f"gambar. Rencana MF ({setout.MERIDIAN_FOOT[0]:.3f}, {setout.MERIDIAN_FOOT[1]:.3f}) boleh "
        "bergeser di dalam 'daerah MF'.",
        "Jangan menetapkan garis dengan menandai bayangan pada jam salat dari aplikasi. Lantai tidak "
        "perlu datar; tanda kalender dipasang pemilik setelah pengamatan.",
        f"Massa tambahan sekitar {g['angle_kg'] + 0.5:.0f} kg: model struktur rumah tidak berubah.",
    ]
    notes(pv, 16.0, 91.0, 152.0, items[:5], h=1.6, lead=2.45)
    notes(pv, 340.0, 118.0, 66.0, items[5:], title="CATATAN (lanj.)", h=1.5, lead=2.3, start=6)
    title_block(sh, pv, "1:25, detail 1:5", "Untuk ditinjau insinyur struktur (angkur) dan "
                "dilaksanakan kontraktor.", meta["date"], meta["source"])
    return sh


def so03(g, meta):
    sh = Sheet("SO-03", "GNOMON ASR: TIANG & PAD", 20)
    v = sh.view(20, (50.0, 212.5), main=True)
    pv = sh.paper()
    emin, emax, nmin, nmax = g["pad_ext"]
    Hp = setout.PAD_H
    po = setout.POST_OFFSET

    # ---- pad plan, true-north frame (east, north) about the foot pin
    v.rect(emin, nmin, emax, nmax, "GNOMON", fill=True)
    v.line((0.0, nmin), (0.0, nmax), "MERIDIAN")
    v.rect(-po - POST_W / 2, -POST_W / 2, -po + POST_W / 2, POST_W / 2, "BETON", fill=True)
    v.line((-po + POST_W / 2, 0.0), (-BALL_D / 2, 0.0), "TERSEMBUNYI")
    v.circle((0.0, 0.0), BALL_D / 2, "TERSEMBUNYI")
    mark(v, (0.0, 0.0), "GF", -1.6, 2.4, align="r")
    mark(v, (0.0, nmax), "GN", 1.2, 1.2)
    mark(v, (0.0, nmin), "GS", 1.2, -3.2)
    v.text((-po, -POST_W / 2 - v.m(3.4)), "GP tiang", h=1.6, align="c")
    for (dec, n) in g["noon_pad"]:
        v.circle((0.0, n), 0.012, "GNOMON")
    for dec, (e, n, Lr, direction) in g["asr"]:
        v.circle((e, n), 0.012, "GNOMON")
        a = 90.0 - direction
        v.arc((0.0, 0.0), Lr, a - 7.0, a + 7.0, "SINAR")
        v.text((e + v.m(1.5), n - v.m(1.0)), f"Asr dek. {dec:+.1f}°", h=1.5, layer="SINAR")
    v.text((0.0 + v.m(1.0), g["noon_pad"][0][1] + v.m(0.5)), "siang dek. +23.4°", h=1.5)
    dim_h(v, emin, emax, nmin - 0.16, nmin - 0.02)
    dim_h(v, 0.0, emax, nmax + 0.12, nmax + 0.02)
    dim_v(v, nmin, 0.0, emax + 0.14, emax + 0.02)
    dim_v(v, 0.0, nmax, emax + 0.14, emax + 0.02)
    o = (-0.42, 0.52)
    arrow(v, o, 90.0, 8.0, "U")
    az = math.radians(THETA)
    arrow(v, o, math.degrees(math.atan2(math.cos(az), math.sin(az))), 8.0, "kiblat", layer="GARIS-KIBLAT", h=1.8)
    pv.text((16.0, 281.0), "DENAH PAD  1:20", h=2.6, bold=True)
    pv.text((16.0, 277.0), "orientasi UTARA SEJATI (tidak sejajar rumah); busur = contoh, tidak diukir",
            h=1.6)

    # ---- elevation looking north: east to the right, z above the pad surface
    ev = sh.view(20, (205.5, 152.5))
    ev.line((-0.75, 0.0), (emin, 0.0), "TIPIS")
    ev.rect(emin, -0.10, emax, 0.0, "TERSEMBUNYI")
    ev.line((emin, 0.0), (emax, 0.0), "GNOMON")
    ev.rect(-0.50, -0.70, -0.10, 0.0, "TERSEMBUNYI")
    ev.rect(-po - POST_W / 2, -0.60, -po + POST_W / 2, Hp + 0.01, "GNOMON", fill=True)
    ev.rect(-po + POST_W / 2, Hp - 0.01, -BALL_D / 2, Hp + 0.01, "GNOMON", fill=True)
    ev.circle((0.0, Hp), BALL_D / 2, "BETON", fill=True)
    ev.line((0.0, Hp - BALL_D / 2), (0.0, 0.0), "TERSEMBUNYI")
    mark(ev, (0.0, 0.0), "", 0, 0)
    fence = Hp - FENCE_CLEAR
    ev.line((-0.66, 0.0), (-0.66, fence), "SEMENTARA")
    ev.text((-0.66 - ev.m(1.0), fence / 2), "pagar barat, jika ada", h=1.5, rot=90.0, align="c",
            layer="SEMENTARA")
    dim_v(ev, 0.0, Hp, 0.22, 0.03, f"H = {Hp * 1000:.0f} ke pusat bola")
    dim_v(ev, 0.0, fence, -0.82, -0.68, f"maks. {fence * 1000:.0f}")
    dim_h(ev, -po, 0.0, Hp + 0.14, Hp + 0.03, f"{po * 1000:.0f}")
    ev.text((emax, 0.0 + ev.m(1.2)), "permukaan pad", h=1.6, align="r")
    ev.text(((emin + emax) / 2, -0.10 - ev.m(3.2)), "pelat pad: tebal dan tulangan oleh kontraktor",
            h=1.5, align="c")
    ev.text((-0.30, -0.70 - ev.m(3.2)), "pondasi tiang: oleh insinyur", h=1.5, align="c")
    ev.text((-0.75, 0.0 + ev.m(1.2)), "tanah", h=1.5)
    pv.text((168.0, 246.0), "TAMPAK DARI SELATAN  1:20", h=2.6, bold=True)
    pv.text((168.0, 241.8), "melihat ke utara; timur di kanan", h=1.6)

    # ---- detail 1:5, post top, arm and ball
    dv = sh.view(5, (395.0, -110.0))
    dv.rect(-po - POST_W / 2, 1.40, -po + POST_W / 2, Hp + 0.01, "GNOMON", fill=True)
    dv.rect(-po + POST_W / 2, Hp - 0.01, -BALL_D / 2, Hp + 0.01, "GNOMON", fill=True)
    dv.circle((0.0, Hp), BALL_D / 2, "BETON", fill=True)
    dim_h(dv, -po, 0.0, Hp + 0.06, Hp + 0.035, f"{po * 1000:.0f}")
    dim_h(dv, -po - POST_W / 2, -po + POST_W / 2, 1.42, 1.43, f"{POST_W * 1000:.0f}")
    dv.text((0.0, Hp - BALL_D / 2 - dv.m(3.4)), f"bola Ø{BALL_D * 1000:.0f}", h=1.6, align="c")
    dv.text((-po + POST_W / 2 + 0.01, Hp + 0.016), "lengan ke TIMUR", h=1.5)
    pv.text((325.0, 216.0), "DETAIL UJUNG TIANG  1:5", h=2.2, bold=True)
    pv.text((325.0, 212.0), "tiang berakhir di lengan", h=1.5)

    az0, az1 = g["asr_az"]
    alt0, alt1 = g["asr_alt"]
    pf = meta["pad_foot"]
    left = [
        f"Posisi {'SEMENTARA' if meta['pad_placeholder'] else 'rencana'}: GF di koordinat rumah "
        f"({pf[0]:.3f}, {pf[1]:.3f})"
        + (f", {pf[1] - setout.DEPTH:g} m di depan dinding kiblat pada garis tengah, sampai batas "
           "tanah dan pagar barat diketahui." if meta["pad_placeholder"] else "."),
        f"Tiang kaku dan berdiri sendiri: baja kotak {POST_W * 1000:.0f}x{POST_W * 1000:.0f} pada "
        "pondasi beton, atau kolom beton kecil. Jangan pada pagar panel yang bergoyang.",
        f"Bola Ø{BALL_D * 1000:.0f} mm di ujung lengan datar {po * 1000:.0f} mm yang mengarah ke TIMUR. "
        f"Pusat bola tepat H = {Hp:.3f} m di atas permukaan pad, dan minimal "
        f"{FENCE_CLEAR * 1000:.0f} mm di atas puncak pagar di baratnya. Tiang berakhir di lengan.",
        f"Pad {(emax - emin) * 1000:.0f} x {(nmax - nmin) * 1000:.0f}: dari GF ke timur "
        f"{emin:+.2f} .. {emax:+.2f} m, ke utara {nmin:+.2f} .. {nmax:+.2f} m, sejajar UTARA SEJATI.",
    ]
    right = [
        "Permukaan doff dan terang. Setiap titik kerja dalam 0.5 mm dari satu bidang datar (selang air "
        "dan mistar); 1 mm tinggi = 3-4.5 detik.",
        "Pin kuningan di GF (tepat di bawah pusat bola); garis utara-selatan melalui GF, cukup 0.5°.",
        f"JANGAN mengukir busur Asr: jari-jarinya berubah sampai {g['asr_dr_day'] * 1000:.0f} mm per "
        "hari dan dipasang harian oleh pemilik.",
        f"Saat Asr matahari di azimut {az0:.0f}°-{az1:.0f}°, tinggi {alt0:.0f}°-{alt1:.0f}°: jaga "
        "pandangan ke arah itu tetap terbuka.",
        "Periksa ulang kerataan pad setiap pergantian musim (tanah dapat mengembang dan menyusut).",
        "Ukuran pondasi tiang dan tebal pad oleh kontraktor/insinyur; tidak mempengaruhi model "
        "struktur rumah.",
    ]
    notes(pv, 16.0, 122.0, 140.0, left, h=1.6, lead=2.45)
    notes(pv, 168.0, 100.0, 237.0, right, title="CATATAN (lanj.)", h=1.6, lead=2.45, start=5)
    title_block(sh, pv, "1:20, detail 1:5", "Untuk ditinjau insinyur (pondasi tiang) dan dilaksanakan "
                "kontraktor.", meta["date"], meta["source"])
    return sh


# --------------------------------------------------------------------- main --

def provenance():
    here = os.path.dirname(os.path.abspath(__file__))
    try:
        rev = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=here, capture_output=True,
                             text=True, check=True).stdout.strip()
        dirty = subprocess.run(["git", "status", "--porcelain", "--", "."], cwd=here,
                               capture_output=True, text=True, check=True).stdout.strip()
        return f"setout/sheets.py @ {rev}{' + perubahan lokal' if dirty else ''}"
    except (OSError, subprocess.CalledProcessError):
        return "setout/sheets.py"


def build(pad_foot=None, date=None):
    placeholder = pad_foot is None
    pf = setout.PAD_FOOT if placeholder else pad_foot
    g = geometry(pf)
    meta = {"date": date or datetime.date.today().isoformat(), "source": provenance(),
            "pad_foot": pf, "pad_placeholder": placeholder}
    return g, [so01(g, meta), so02(g, meta), so03(g, meta)]


def write_all(out, pad_foot=None, date=None):
    os.makedirs(out, exist_ok=True)
    g, sheets = build(pad_foot, date)
    paths = []
    for sh in sheets:
        p = os.path.join(out, f"{sh.number}.dxf")
        frame = {"SO-01": "house frame: x, y in mm, +y = qibla",
                 "SO-02": "house frame: x, y in mm, +y = qibla",
                 "SO-03": "pad plan: east, north in mm from GF (TRUE north)"}[sh.number]
        write_dxf(sh, p, comment=f"Griya Sakha {sh.number} {sh.title}\nmain view: {frame}; 1 unit = 1 mm")
        paths.append(p)
    p = os.path.join(out, "griya-sakha-SO.pdf")
    write_pdf(sheets, p, "Griya Sakha SO-01..03: uitzet, ruang meridian, gnomon Asr")
    paths.append(p)
    return g, sheets, paths


# ----------------------------------------------------------------- selftest --

def _dxf_groups(path):
    with open(path, encoding="ascii") as f:
        raw = f.read().splitlines()
    return [(int(raw[i]), raw[i + 1]) for i in range(0, len(raw) - 1, 2)]


def selftest():
    import tempfile
    ok = True
    n = 0

    def check(label, cond, detail=""):
        nonlocal ok, n
        n += 1
        ok &= bool(cond)
        print(f"  {'PASS' if cond else 'FAIL'}  {label}{'  (' + detail + ')' if detail else ''}")

    out = tempfile.mkdtemp(prefix="sheets-")
    g, sheets, paths = write_all(out, date="2026-01-01")
    P = g["P"]

    # The field sheet's numbers, re-derived through this file's own path.
    check("meridian line ends +0.971 / -1.902 m, 2873 mm", abs(g["m_n"] - 0.971) < 5e-4 and
          abs(g["m_s"] + 1.902) < 5e-4 and round((g["m_n"] - g["m_s"]) * 1000) == 2873)
    e0, e1, n0, n1 = g["pad_ext"]
    check("pad 2.07 x 2.19 m, E -0.04..2.03 / N -1.35..0.84", abs(e1 - e0 - 2.07) < 5e-3 and
          abs(n1 - n0 - 2.19) < 5e-3 and round(e0, 2) == -0.04 and round(n0, 2) == -1.35)
    check("true north 24°15'18\" from +x toward +y", dms_s(NB) == "24°15'18\"")
    check("tape diagonal 11.9202 m", f"{math.dist(P['C1'], P['C3']):.4f}" == "11.9202")
    rx0, rx1, ry0, ry1 = g["room"]
    check("meridian room clear 2.85 x 3.135 m", abs(rx1 - rx0 - 2.85) < 1e-9 and abs(ry1 - ry0 - 3.135) < 1e-9)
    w = g["foot_window"]
    check("MF window x 1.83-2.07, y 7.83-9.79 (field sheet)",
          all(abs(a - b) < 0.006 for a, b in zip(w, (1.83, 2.07, 7.83, 9.79))),
          " ".join(f"{v:.3f}" for v in w))
    check("MF itself sits inside its window", w[0] <= setout.MERIDIAN_FOOT[0] <= w[1] and
          w[2] <= setout.MERIDIAN_FOOT[1] <= w[3])
    check("clear roof sheet 0.30 G south, 0.59 G north, 0.05 G east-west",
          f"{g['tan_s']:.2f}" == "0.30" and f"{g['tan_n']:.2f}" == "0.59" and
          math.ceil(g["tan_ew"] * 100) / 100 == 0.05)
    check("the angle's leg clears the hole's sun cone", g["leg_clear"] < HOLE_FROM_LEG,
          f"needs {g['leg_clear'] * 1000:.1f} mm, drawn {HOLE_FROM_LEG * 1000:.0f}")
    check("the steel angle is about 12 kg, not 'under 10'", 11.0 < g["angle_kg"] < 12.5,
          f"{g['angle_kg']:.2f} kg")
    check("Asr radius changes by up to about 11 mm a day", 0.009 < g["asr_dr_day"] < 0.012,
          f"{g['asr_dr_day'] * 1000:.1f} mm")

    # DXF: structure, layers, and the main view at TRUE house coordinates.
    for sh, path in zip(sheets, paths):
        grp = _dxf_groups(path)
        names = [v for c, v in grp if c == 0]
        layers_def = {grp[i + 1][1] for i in range(len(grp) - 1)
                      if grp[i] == (0, "LAYER") and grp[i + 1][0] == 2}
        used = set()
        for i, (c, val) in enumerate(grp):
            if c == 8:
                used.add(val)
        check(f"{sh.number}.dxf is a closed R12 file with its layers defined",
              names[-1] == "EOF" and ("AC1009" in [v for _, v in grp]) and used <= layers_def and
              names.count("SECTION") == names.count("ENDSEC") == 4)
    grp = _dxf_groups(paths[0])
    pts = []
    for i, (c, val) in enumerate(grp):
        if (c, val) == (0, "POINT"):
            d = dict(grp[i + 1:i + 6])
            pts.append((float(d[10]), float(d[20])))
    want = [(x * 1000.0, y * 1000.0) for _, x, y, _ in g["pts"]]
    check("SO-01.dxf carries every setout point at its house coordinate, in mm",
          len(pts) == len(want) and all(min(math.dist(p, q) for p in pts) < 1e-3 for q in want),
          f"{len(pts)} points")
    lost = [t for path in paths[:3] for c, t in _dxf_groups(path) if c == 1 and "?" in t]
    check("DXF text is plain ASCII with no lost characters, on every sheet", not lost, repr(lost[:3]))

    # PDF: three A3 landscape pages, a valid xref, every glyph encodable.
    with open(paths[-1], "rb") as f:
        pdf = f.read()
    start = int(pdf.rsplit(b"startxref", 1)[1].split()[0])
    body = pdf[start:].split(b"trailer")[0].split(b"\n")[3:]     # xref, "0 n", the free entry
    offs = [int(l[:10]) for l in body if l.strip()]
    check("PDF xref offsets point at their objects",
          all(pdf[o:].startswith(b"%d 0 obj" % (i + 1)) for i, o in enumerate(offs)))
    check("PDF has three A3 landscape pages", pdf.count(b"/MediaBox [0 0 1190.55 841.89]") == 3)
    lost = [p[2] for sh in sheets for p in sh.prims if p[0] == "text" and
            b"?" in pdf_bytes(p[2]) and "?" not in p[2]]
    check("every sheet text encodes in WinAnsi", not lost, repr(lost[:3]))

    # Layout: no text leaves the frame, none runs into the title block from above.
    bad = []
    for sh in sheets:
        for p in sh.prims:
            if p[0] != "text" or p[9]:
                continue
            (x, y), h, align = p[3], p[4], p[7]
            wmm = text_width_mm(p[2], h, p[10])
            x0 = {"l": x, "c": x - wmm / 2, "r": x - wmm}[align]
            if x0 < FRAME[0] or x0 + wmm > FRAME[2] or y < FRAME[1] or y + h > FRAME[3]:
                bad.append((sh.number, p[2][:40]))
    check("no sheet text leaves the A3 frame", not bad, repr(bad[:3]))
    k = sheets[0].prims  # scale: 1 m in the SO-01 plan is 10 mm on paper
    v = sheets[0].view(100, (0.0, 0.0))
    check("1:100 puts 1 m at 10 mm", abs(v.P((1.0, 0.0))[0] - 10.0) < 1e-12)

    print(f"{n} checks, {'all passed' if ok else 'FAILURES'}  (files in {out})")
    return ok


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "out"))
    ap.add_argument("--pad-foot", default=None,
                    help="house x,y of the Asr ball's plumb point (default: setout.py's PLACEHOLDER)")
    ap.add_argument("--date", default=None, help="date for the title block (default today)")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        sys.exit(0 if selftest() else 1)
    pf = tuple(float(v) for v in a.pad_foot.split(",")) if a.pad_foot else None
    _, _, paths = write_all(a.out, pf, a.date)
    for p in paths:
        print(p)


if __name__ == "__main__":
    main()
