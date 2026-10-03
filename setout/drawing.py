"""Drawing model with two writers, for the setout sheets (sheets.py).

One set of primitives, rendered twice:

  * DXF R12, model space in MILLIMETRES at TRUE size. The main view of each
    sheet sits at real house coordinates (x mm, y mm), so a drafter can measure
    it, snap to it and paste it into a site plan. Other views sit where a plot
    at the main scale would put them, still at true size.
  * PDF, the printable A3 sheet at the stated scales.

Both come from the same primitives, so the print and the CAD file cannot
disagree. Stdlib only, like the rest of setout/: the numbers that decide where
concrete and brass go must be checkable anywhere.

Views never rotate their model frame. A plan in the house frame is drawn with
+y (the qibla) up the sheet; a plan in the true-north frame with north up.
"""
import math
import zlib

MM = 72.0 / 25.4          # PDF points per millimetre
CAP = 0.718               # Helvetica cap height per em. CAD text height IS cap height.

# Layer: (ACI colour, linetype, PDF line width mm, PDF stroke rgb, PDF fill rgb)
LAYERS = {
    "0":            (7, "CONTINUOUS", 0.18, (0, 0, 0), None),
    "KERTAS":       (7, "CONTINUOUS", 0.35, (0, 0, 0), None),
    "TIPIS":        (7, "CONTINUOUS", 0.13, (0, 0, 0), None),
    "TEKS":         (7, "CONTINUOUS", 0.18, (0, 0, 0), None),
    "UKURAN":       (7, "CONTINUOUS", 0.13, (0.15, 0.15, 0.15), None),
    "AS":           (1, "CENTER", 0.13, (0.70, 0.10, 0.10), None),
    "GARIS-KIBLAT": (1, "CENTER", 0.40, (0.75, 0.05, 0.05), None),
    "BETON":        (7, "CONTINUOUS", 0.35, (0, 0, 0), (0.72, 0.72, 0.72)),
    "DINDING":      (8, "CONTINUOUS", 0.18, (0.30, 0.30, 0.30), None),
    "TERSEMBUNYI":  (8, "HIDDEN", 0.25, (0.30, 0.30, 0.30), None),
    "BOUWPLANK":    (3, "DASHED", 0.25, (0.10, 0.50, 0.10), None),
    "TITIK":        (6, "CONTINUOUS", 0.25, (0.55, 0.00, 0.55), None),
    "MERIDIAN":     (5, "CONTINUOUS", 0.50, (0.00, 0.25, 0.75), None),
    "SINAR":        (30, "DASHED", 0.18, (0.90, 0.45, 0.00), None),
    "GNOMON":       (4, "CONTINUOUS", 0.35, (0.00, 0.42, 0.48), (0.80, 0.90, 0.92)),
    "SEMENTARA":    (6, "DASHED", 0.25, (0.55, 0.00, 0.55), None),
}

# Paper-mm patterns (+ dash, - gap). $LTSCALE = the main scale makes them plot at these lengths.
LINETYPES = {
    "CONTINUOUS": ("Solid line", []),
    "DASHED":     ("Dashed __ __ __", [4.0, -2.0]),
    "CENTER":     ("Center ____ _ ____", [8.0, -1.5, 1.5, -1.5]),
    "HIDDEN":     ("Hidden _ _ _ _", [2.0, -1.0]),
}

# Helvetica advance widths (per 1000 em), ASCII 32..126, from the standard AFM.
_HELV = [278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
         556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
         1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
         667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
         333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
         556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584]
_HELV_EXTRA = {"°": 400, "±": 584, "×": 584, "Ø": 778, "–": 556, "—": 1000, "·": 278}
BOLD_WIDEN = 1.06          # Helvetica-Bold is about this much wider; only used to align

# Characters outside plain ASCII that sheets may use, and what each writer does with them.
_DXF_SPECIAL = {"°": "%%d", "±": "%%p", "Ø": "%%c", "×": "x", "′": "'", "″": '"',
                "–": "-", "—": "-", "·": "."}
_PDF_SUBST = {"′": "'", "″": '"'}


def text_width_mm(s, h_mm, bold=False):
    """Advance width of `s` at cap height h_mm, for alignment and fit checks."""
    size = h_mm / CAP
    w = 0
    for ch in s:
        o = ord(ch)
        w += _HELV[o - 32] if 32 <= o <= 126 else _HELV_EXTRA.get(ch, 556)
    return w / 1000.0 * size * (BOLD_WIDEN if bold else 1.0)


def dxf_text(s):
    out = []
    for ch in s:
        if ch in _DXF_SPECIAL:
            out.append(_DXF_SPECIAL[ch])
        elif 32 <= ord(ch) <= 126:
            out.append(ch)
        else:
            out.append("?")
    return "".join(out)


def pdf_bytes(s):
    """WinAnsi (cp1252) bytes for a PDF string literal, escaped. '?' marks a loss."""
    s = "".join(_PDF_SUBST.get(ch, ch) for ch in s)
    raw = s.encode("cp1252", errors="replace")
    out = bytearray()
    for b in raw:
        if b in (0x28, 0x29, 0x5C):
            out += b"\\" + bytes([b])
        elif 32 <= b <= 126:
            out.append(b)
        else:
            out += b"\\%03o" % b
    return bytes(out)


class Sheet:
    """One A3 sheet. `anchor` ties the main view's paper position to DXF (0,0)
    so that the main view's model origin lands exactly on the DXF origin."""

    def __init__(self, number, title, main_scale, size=(420.0, 297.0)):
        self.number, self.title, self.main_scale, self.size = number, title, main_scale, size
        self.prims = []
        self.anchor = None          # paper point that maps to DXF (0, 0)

    def paper_to_dxf(self, p):
        ax, ay = self.anchor
        return ((p[0] - ax) * self.main_scale, (p[1] - ay) * self.main_scale)

    def view(self, scale, paper_origin, main=False):
        """A model view in metres at 1:scale. `paper_origin` is where model (0, 0)
        falls on the sheet, in mm. The main view defines the DXF anchor."""
        if main:
            self.anchor = paper_origin
        return View(self, scale, paper_origin)

    def paper(self):
        return PaperView(self)


class _Base:
    """Shared primitive API. Subclasses define P (to paper mm), D (to DXF mm),
    k (model length -> paper mm) and kd (model length -> DXF mm)."""

    text_scale = 1.0            # DXF text height per paper mm of text

    def line(self, a, b, layer="0"):
        self.sheet.prims.append(("line", layer, (self.P(a), self.P(b)), (self.D(a), self.D(b))))

    def poly(self, pts, layer="0", closed=False, fill=False):
        self.sheet.prims.append(("poly", layer, closed, fill,
                                 [self.P(p) for p in pts], [self.D(p) for p in pts]))

    def rect(self, x0, y0, x1, y1, layer="0", fill=False):
        self.poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], layer, True, fill)

    def circle(self, c, r, layer="0", fill=False):
        self.sheet.prims.append(("circle", layer, fill, self.P(c), r * self.k, self.D(c), r * self.kd))

    def arc(self, c, r, a0, a1, layer="0"):
        """Counter-clockwise from a0 to a1, degrees from +x."""
        self.sheet.prims.append(("arc", layer, self.P(c), r * self.k, a0, a1, self.D(c), r * self.kd))

    def text(self, p, s, h=2.5, layer="TEKS", align="l", valign="b", rot=0.0, bold=False):
        """h is the printed cap height in paper mm."""
        self.sheet.prims.append(("text", layer, s, self.P(p), h, self.D(p), h * self.text_scale,
                                 align, valign, rot, bold))

    def point(self, p, layer="TITIK"):
        """A DXF POINT at the exact coordinate (the PDF draws nothing for it)."""
        self.sheet.prims.append(("point", layer, self.D(p)))

    def m(self, paper_mm):
        """Paper millimetres expressed in this view's model units."""
        return paper_mm / self.k


class View(_Base):
    def __init__(self, sheet, scale, paper_origin):
        self.sheet, self.scale, self.origin = sheet, scale, paper_origin
        self.k = 1000.0 / scale      # metres -> paper mm
        self.kd = 1000.0             # metres -> DXF mm, true size
        self.text_scale = scale

    def P(self, p):
        return (self.origin[0] + p[0] * self.k, self.origin[1] + p[1] * self.k)

    def D(self, p):
        ox, oy = self.sheet.paper_to_dxf(self.origin)
        return (ox + p[0] * self.kd, oy + p[1] * self.kd)


class PaperView(_Base):
    """Sheet furniture in paper mm: frame, title block, tables, notes."""

    def __init__(self, sheet):
        self.sheet = sheet
        self.k = 1.0
        self.kd = sheet.main_scale
        self.text_scale = sheet.main_scale

    def P(self, p):
        return (p[0], p[1])

    def D(self, p):
        return self.sheet.paper_to_dxf(p)


# ---------------------------------------------------------------- DXF R12 --

def write_dxf(sheet, path, comment=""):
    lines = []

    def g(code, value):
        lines.append(f"{code:>3}")
        lines.append(value if isinstance(value, str) else
                     (f"{value:.4f}" if isinstance(value, float) else str(value)))

    used = sorted({p[1] for p in sheet.prims} | {"0"})
    xs, ys = [], []
    for p in sheet.prims:
        if p[0] == "line":
            for q in p[3]: xs.append(q[0]); ys.append(q[1])
        elif p[0] == "poly":
            for q in p[5]: xs.append(q[0]); ys.append(q[1])
        elif p[0] in ("circle",):
            c, r = p[5], p[6]; xs += [c[0] - r, c[0] + r]; ys += [c[1] - r, c[1] + r]
        elif p[0] == "arc":
            c, r = p[6], p[7]; xs += [c[0] - r, c[0] + r]; ys += [c[1] - r, c[1] + r]
        elif p[0] == "text":
            xs.append(p[5][0]); ys.append(p[5][1])
        elif p[0] == "point":
            xs.append(p[2][0]); ys.append(p[2][1])

    for c in comment.splitlines():
        g(999, c)
    g(0, "SECTION"); g(2, "HEADER")
    g(9, "$ACADVER"); g(1, "AC1009")
    g(9, "$DWGCODEPAGE"); g(3, "ANSI_1252")
    g(9, "$INSBASE"); g(10, 0.0); g(20, 0.0); g(30, 0.0)
    g(9, "$EXTMIN"); g(10, float(min(xs))); g(20, float(min(ys))); g(30, 0.0)
    g(9, "$EXTMAX"); g(10, float(max(xs))); g(20, float(max(ys))); g(30, 0.0)
    g(9, "$LTSCALE"); g(40, float(sheet.main_scale))
    g(9, "$TEXTSTYLE"); g(7, "STANDARD")
    g(9, "$CLAYER"); g(8, "0")
    g(0, "ENDSEC")

    g(0, "SECTION"); g(2, "TABLES")
    g(0, "TABLE"); g(2, "LTYPE"); g(70, len(LINETYPES))
    for name, (desc, pat) in LINETYPES.items():
        g(0, "LTYPE"); g(2, name); g(70, 0); g(3, desc); g(72, 65); g(73, len(pat))
        g(40, float(sum(abs(v) for v in pat)))
        for v in pat:
            g(49, float(v))
    g(0, "ENDTAB")
    g(0, "TABLE"); g(2, "LAYER"); g(70, len(used))
    for name in used:
        aci, lt = LAYERS[name][0], LAYERS[name][1]
        g(0, "LAYER"); g(2, name); g(70, 0); g(62, aci); g(6, lt)
    g(0, "ENDTAB")
    g(0, "TABLE"); g(2, "STYLE"); g(70, 1)
    g(0, "STYLE"); g(2, "STANDARD"); g(70, 0); g(40, 0.0); g(41, 1.0); g(50, 0.0)
    g(71, 0); g(42, 2.5); g(3, "txt"); g(4, "")
    g(0, "ENDTAB")
    g(0, "ENDSEC")

    g(0, "SECTION"); g(2, "BLOCKS"); g(0, "ENDSEC")

    g(0, "SECTION"); g(2, "ENTITIES")
    for p in sheet.prims:
        kind, layer = p[0], p[1]
        if kind == "line":
            (a, b) = p[3]
            g(0, "LINE"); g(8, layer)
            g(10, a[0]); g(20, a[1]); g(30, 0.0); g(11, b[0]); g(21, b[1]); g(31, 0.0)
        elif kind == "poly":
            closed, fill, pts = p[2], p[3], p[5]
            if fill and closed and len(pts) in (3, 4):
                # SOLID takes its 3rd and 4th corners in "bow-tie" order.
                q = pts if len(pts) == 3 else [pts[0], pts[1], pts[3], pts[2]]
                g(0, "SOLID"); g(8, layer)
                for i, pt in enumerate(q + ([q[2]] if len(q) == 3 else [])):
                    g(10 + i, pt[0]); g(20 + i, pt[1]); g(30 + i, 0.0)
            g(0, "POLYLINE"); g(8, layer); g(66, 1); g(10, 0.0); g(20, 0.0); g(30, 0.0)
            g(70, 1 if closed else 0)
            for pt in pts:
                g(0, "VERTEX"); g(8, layer); g(10, pt[0]); g(20, pt[1]); g(30, 0.0)
            g(0, "SEQEND"); g(8, layer)
        elif kind == "circle":
            c, r = p[5], p[6]
            g(0, "CIRCLE"); g(8, layer); g(10, c[0]); g(20, c[1]); g(30, 0.0); g(40, float(r))
        elif kind == "arc":
            c, r, a0, a1 = p[6], p[7], p[4], p[5]
            g(0, "ARC"); g(8, layer); g(10, c[0]); g(20, c[1]); g(30, 0.0); g(40, float(r))
            g(50, float(a0 % 360.0)); g(51, float(a1 % 360.0))
        elif kind == "text":
            s, d, hd, align, valign, rot = p[2], p[5], p[6], p[7], p[8], p[9]
            h72 = {"l": 0, "c": 1, "r": 2}[align]
            v73 = {"b": 0, "m": 2, "t": 3}[valign]
            g(0, "TEXT"); g(8, layer); g(10, d[0]); g(20, d[1]); g(30, 0.0)
            g(40, float(hd)); g(1, dxf_text(s))
            if rot:
                g(50, float(rot))
            g(7, "STANDARD")
            if h72 or v73:
                g(72, h72); g(11, d[0]); g(21, d[1]); g(31, 0.0); g(73, v73)
        elif kind == "point":
            d = p[2]
            g(0, "POINT"); g(8, layer); g(10, d[0]); g(20, d[1]); g(30, 0.0)
    g(0, "ENDSEC")
    g(0, "EOF")
    with open(path, "w", encoding="ascii", newline="\r\n") as f:
        f.write("\n".join(lines) + "\n")


# --------------------------------------------------------------------- PDF --

_K = 0.5522847498          # cubic Bezier handle for a quarter circle


def _arc_beziers(cx, cy, r, a0, a1):
    """Cubic segments approximating a CCW arc, each <= 90 degrees."""
    if a1 < a0:
        a1 += 360.0
    n = max(1, int(math.ceil((a1 - a0) / 90.0 - 1e-9)))
    step = math.radians((a1 - a0) / n)
    t = 4.0 / 3.0 * math.tan(step / 4.0)
    segs = []
    a = math.radians(a0)
    for _ in range(n):
        b = a + step
        p0 = (cx + r * math.cos(a), cy + r * math.sin(a))
        p3 = (cx + r * math.cos(b), cy + r * math.sin(b))
        p1 = (p0[0] - t * r * math.sin(a), p0[1] + t * r * math.cos(a))
        p2 = (p3[0] + t * r * math.sin(b), p3[1] - t * r * math.cos(b))
        segs.append((p0, p1, p2, p3))
        a = b
    return segs


def _page_content(sheet):
    ops = []

    def f(v):
        return f"{v * MM:.3f}"

    def style(layer, filled):
        _, lt, w, rgb, fill = LAYERS[layer]
        ops.append(f"{w * MM:.3f} w")
        ops.append("%.3f %.3f %.3f RG" % rgb)
        if filled and fill:
            ops.append("%.3f %.3f %.3f rg" % fill)
        pat = LINETYPES[lt][1]
        ops.append("[" + " ".join(f"{abs(v) * MM:.2f}" for v in pat) + "] 0 d" if pat else "[] 0 d")

    ops.append("1 J 1 j")
    for p in sheet.prims:
        kind, layer = p[0], p[1]
        if kind == "line":
            (a, b) = p[2]
            style(layer, False)
            ops.append(f"{f(a[0])} {f(a[1])} m {f(b[0])} {f(b[1])} l S")
        elif kind == "poly":
            closed, fill, pts = p[2], p[3], p[4]
            has_fill = fill and LAYERS[layer][4] is not None
            style(layer, has_fill)
            path = [f"{f(pts[0][0])} {f(pts[0][1])} m"] + [f"{f(q[0])} {f(q[1])} l" for q in pts[1:]]
            ops.append(" ".join(path) + (" h" if closed else "") + (" B" if has_fill and closed else " S"))
        elif kind in ("circle", "arc"):
            if kind == "circle":
                fill, c, r = p[2], p[3], p[4]
                a0, a1 = 0.0, 360.0
            else:
                fill, c, r, a0, a1 = False, p[2], p[3], p[4], p[5]
            has_fill = fill and LAYERS[layer][4] is not None
            style(layer, has_fill)
            segs = _arc_beziers(c[0], c[1], r, a0, a1)
            path = [f"{f(segs[0][0][0])} {f(segs[0][0][1])} m"]
            for s0, s1, s2, s3 in segs:
                path.append(f"{f(s1[0])} {f(s1[1])} {f(s2[0])} {f(s2[1])} {f(s3[0])} {f(s3[1])} c")
            ops.append(" ".join(path) + (" h B" if has_fill else " S"))
        elif kind == "text":
            s, (x, y), h, align, valign, rot, bold = p[2], p[3], p[4], p[7], p[8], p[9], p[10]
            size = h / CAP
            w = text_width_mm(s, h, bold)
            dx = {"l": 0.0, "c": -w / 2.0, "r": -w}[align]
            dy = {"b": 0.0, "m": -h / 2.0, "t": -h}[valign]
            cr, sr = math.cos(math.radians(rot)), math.sin(math.radians(rot))
            tx, ty = x + dx * cr - dy * sr, y + dx * sr + dy * cr
            rgb = LAYERS[layer][3]
            ops.append("%.3f %.3f %.3f rg" % rgb)
            ops.append(f"BT /{'F2' if bold else 'F1'} {size * MM:.3f} Tf "
                       f"{cr:.5f} {sr:.5f} {-sr:.5f} {cr:.5f} {f(tx)} {f(ty)} Tm ("
                       + pdf_bytes(s).decode("latin-1") + ") Tj ET")
    return "\n".join(ops).encode("latin-1")


def write_pdf(sheets, path, title=""):
    objs = []                                  # object bodies, 1-based ids

    def add(body):
        objs.append(body)
        return len(objs)

    catalog = add(None)
    pages = add(None)
    f1 = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    f2 = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
    kids = []
    for sh in sheets:
        data = zlib.compress(_page_content(sh), 9)
        content = add(b"<< /Length %d /Filter /FlateDecode >>\nstream\n" % len(data) + data + b"\nendstream")
        w, h = sh.size[0] * MM, sh.size[1] * MM
        page = add(("<< /Type /Page /Parent %d 0 R /MediaBox [0 0 %.2f %.2f] "
                    "/Resources << /Font << /F1 %d 0 R /F2 %d 0 R >> >> /Contents %d 0 R >>"
                    % (pages, w, h, f1, f2, content)).encode("ascii"))
        kids.append(page)
    objs[catalog - 1] = b"<< /Type /Catalog /Pages %d 0 R >>" % pages
    objs[pages - 1] = ("<< /Type /Pages /Kids [%s] /Count %d >>"
                       % (" ".join(f"{k} 0 R" for k in kids), len(kids))).encode("ascii")
    info = add(b"<< /Title (" + pdf_bytes(title) + b") /Creator (dsygv setout/sheets.py) >>")

    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for i, body in enumerate(objs, start=1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % i + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
    for off in offsets:
        out += b"%010d 00000 n \n" % off
    out += (b"trailer\n<< /Size %d /Root %d 0 R /Info %d 0 R >>\nstartxref\n%d\n%%%%EOF\n"
            % (len(objs) + 1, catalog, info, xref))
    with open(path, "wb") as fh:
        fh.write(out)
