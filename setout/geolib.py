"""WGS84 geodesy: Vincenty inverse, ECEF<->ENU (Karney LocalCartesian equivalent)."""
import math

A = 6378137.0
F = 1.0 / 298.257223563
B = A * (1.0 - F)
E2 = F * (2.0 - F)

def vincenty_inverse(lat1, lon1, lat2, lon2):
    """Returns (s_metres, az1_deg, az2_deg). Accurate to ~0.5 mm / 1e-9 deg."""
    L = math.radians(lon2 - lon1)
    U1 = math.atan((1 - F) * math.tan(math.radians(lat1)))
    U2 = math.atan((1 - F) * math.tan(math.radians(lat2)))
    sU1, cU1, sU2, cU2 = math.sin(U1), math.cos(U1), math.sin(U2), math.cos(U2)
    lam = L
    for _ in range(200):
        sl, cl = math.sin(lam), math.cos(lam)
        ss = math.hypot(cU2 * sl, cU1 * sU2 - sU1 * cU2 * cl)
        if ss == 0:
            return 0.0, 0.0, 0.0
        cs = sU1 * sU2 + cU1 * cU2 * cl
        sig = math.atan2(ss, cs)
        sa = cU1 * cU2 * sl / ss
        c2a = 1 - sa * sa
        c2sm = cs - 2 * sU1 * sU2 / c2a if c2a != 0 else 0.0
        C = F / 16 * c2a * (4 + F * (4 - 3 * c2a))
        lam_p = lam
        lam = L + (1 - C) * F * sa * (sig + C * ss * (c2sm + C * cs * (-1 + 2 * c2sm ** 2)))
        if abs(lam - lam_p) < 1e-15:
            break
    u2 = c2a * (A * A - B * B) / (B * B)
    Aa = 1 + u2 / 16384 * (4096 + u2 * (-768 + u2 * (320 - 175 * u2)))
    Bb = u2 / 1024 * (256 + u2 * (-128 + u2 * (74 - 47 * u2)))
    dsig = Bb * ss * (c2sm + Bb / 4 * (cs * (-1 + 2 * c2sm ** 2)
           - Bb / 6 * c2sm * (-3 + 4 * ss ** 2) * (-3 + 4 * c2sm ** 2)))
    s = B * Aa * (sig - dsig)
    az1 = math.degrees(math.atan2(cU2 * math.sin(lam), cU1 * sU2 - sU1 * cU2 * math.cos(lam))) % 360.0
    az2 = math.degrees(math.atan2(cU1 * math.sin(lam), -sU1 * cU2 + cU1 * sU2 * math.cos(lam))) % 360.0
    return s, az1, az2

def spherical_azimuth(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    return math.degrees(math.atan2(math.sin(dl) * math.cos(p2),
        math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl))) % 360.0

def geodetic_to_ecef(lat, lon, h):
    p, l = math.radians(lat), math.radians(lon)
    N = A / math.sqrt(1 - E2 * math.sin(p) ** 2)
    return ((N + h) * math.cos(p) * math.cos(l),
            (N + h) * math.cos(p) * math.sin(l),
            (N * (1 - E2) + h) * math.sin(p))

def ecef_to_enu(x, y, z, lat0, lon0, h0):
    """Karney LocalCartesian Forward(): ECEF -> local ENU about (lat0,lon0,h0)."""
    x0, y0, z0 = geodetic_to_ecef(lat0, lon0, h0)
    dx, dy, dz = x - x0, y - y0, z - z0
    p, l = math.radians(lat0), math.radians(lon0)
    sp, cp, sl, cl = math.sin(p), math.cos(p), math.sin(l), math.cos(l)
    return (-sl * dx + cl * dy,
            -sp * cl * dx - sp * sl * dy + cp * dz,
            cp * cl * dx + cp * sl * dy + sp * dz)

def enu_to_geodetic(e, n, u, lat0, lon0, h0):
    """Inverse of ecef_to_enu, then ECEF -> geodetic (Bowring, iterated)."""
    p, l = math.radians(lat0), math.radians(lon0)
    sp, cp, sl, cl = math.sin(p), math.cos(p), math.sin(l), math.cos(l)
    x0, y0, z0 = geodetic_to_ecef(lat0, lon0, h0)
    x = x0 - sl * e - sp * cl * n + cp * cl * u
    y = y0 + cl * e - sp * sl * n + cp * sl * u
    z = z0 + cp * n + sp * u
    r = math.hypot(x, y)
    lat = math.atan2(z, r * (1 - E2))
    for _ in range(10):
        N = A / math.sqrt(1 - E2 * math.sin(lat) ** 2)
        h = r / math.cos(lat) - N
        lat = math.atan2(z, r * (1 - E2 * N / (N + h)))
    N = A / math.sqrt(1 - E2 * math.sin(lat) ** 2)
    return math.degrees(lat), math.degrees(math.atan2(y, x)), r / math.cos(lat) - N

def local_enu_azimuth(lat1, lon1, h1, lat2, lon2, h2):
    """Horizontal azimuth of the 1->2 baseline in the ENU frame at point 1."""
    e, n, _ = ecef_to_enu(*geodetic_to_ecef(lat2, lon2, h2), lat1, lon1, h1)
    return math.degrees(math.atan2(e, n)) % 360.0, math.hypot(e, n)

def utm_convergence(lat, lon, cm):
    """Grid convergence (grid north - true north), degrees. Series to 5th order."""
    p, dl = math.radians(lat), math.radians(lon - cm)
    t, c = math.tan(p), E2 / (1 - E2) * math.cos(p) ** 2
    g = (dl * math.sin(p)
         + dl ** 3 * math.sin(p) * math.cos(p) ** 2 * (1 + 3 * c + 2 * c * c) / 3
         + dl ** 5 * math.sin(p) * math.cos(p) ** 4 * (2 - t * t) / 15)
    return math.degrees(g)

def dms(deg):
    s = "-" if deg < 0 else ""
    deg = abs(deg); d = int(deg); m = int((deg - d) * 60); sec = (deg - d - m / 60) * 3600
    return f"{s}{d}°{m:02d}'{sec:05.2f}\""
