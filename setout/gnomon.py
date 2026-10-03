"""Sun geometry for the two gnomons in the field sheet's part two (stages 8-12):
http://183.81.158.231:8080/qibla-setout-field-sheet.html
(qibla-setout-field-sheet.html in the quarkus repo).

The indoor meridian line under the roof aperture (Dhuhr) and the outdoor
ball-nodus pad (Asr). Stdlib only, for the same reason as geolib.py: the
numbers that decide where concrete and brass go must be checkable anywhere.

Declination is swept over its full range rather than taken from an ephemeris:
the stakeout only needs the envelope, and at the solstices -- where the
envelope is set -- the declination is stationary, so the sweep and the dated
2027 run of observe.js agree to the millimetre (setout.py --selftest checks).
"""
import math

OBLIQUITY = 23.4363           # deg, 2027; drifts by 0.013 deg per century
T_DEFAULT, P_DEFAULT = 28.0, 1005.0


def refraction(true_alt, T=T_DEFAULT, P=P_DEFAULT):
    """Saemundsson, on a TRUE altitude, scaled to P (hPa) and T (C). Degrees."""
    if true_alt < -2.0:
        return 0.0
    return (P / 1010.0) * (283.0 / (273.0 + T)) * 1.02 / (
        60.0 * math.tan(math.radians(true_alt + 10.3 / (true_alt + 5.11))))


def noon_zenith_app(lat, dec):
    """Apparent noon zenith distance, signed + when the sun culminates north of the zenith."""
    z = dec - lat
    alt = 90.0 - abs(z)
    z_app = 90.0 - (alt + refraction(alt))
    return math.copysign(z_app, z)


def noon_offset(lat, dec, H):
    """Noon image/shadow centre from the foot along true north (+N), metres."""
    return -H * math.tan(math.radians(noon_zenith_app(lat, dec)))


def meridian_extent(lat, H, margin=0.025):
    """(north_end, south_end) of the meridian line from its foot, + north, metres.
    The margin is half a sun image, so the whole disc stays on the line."""
    a, b = noon_offset(lat, -OBLIQUITY, H), noon_offset(lat, OBLIQUITY, H)
    return max(a, b) + margin, min(a, b) - margin


def asr_point(lat, dec, H):
    """Standard-rule Asr shadow centre from the foot: (east, north, radius, direction_deg).
    Radius is H + the noon shadow, both as measured, i.e. with refraction."""
    z0 = abs(noon_zenith_app(lat, dec))
    a_app = math.degrees(math.atan(1.0 / (1.0 + math.tan(math.radians(z0)))))
    a = a_app
    for _ in range(8):                          # true altitude whose refracted image is a_app
        a = a_app - refraction(a)
    p, d, al = math.radians(lat), math.radians(dec), math.radians(a)
    cos_h = (math.sin(al) - math.sin(p) * math.sin(d)) / (math.cos(p) * math.cos(d))
    h = math.acos(max(-1.0, min(1.0, cos_h)))   # afternoon: positive hour angle
    az = math.degrees(math.atan2(-math.cos(d) * math.sin(h),
                                 math.sin(d) * math.cos(p) - math.cos(d) * math.sin(p) * math.cos(h))) % 360.0
    L = H * (1.0 + math.tan(math.radians(z0)))
    direction = (az + 180.0) % 360.0
    r = math.radians(direction)
    return L * math.sin(r), L * math.cos(r), L, direction


def pad_extent(lat, H, margin=0.04, steps=720):
    """(east_min, east_max, north_min, north_max) of every noon and Asr shadow centre
    over a year, from the foot, plus a margin for the shadow's own size. Metres."""
    E, N = [], []
    for i in range(steps + 1):
        dec = -OBLIQUITY + 2.0 * OBLIQUITY * i / steps
        E.append(0.0); N.append(noon_offset(lat, dec, H))
        e, n, _, _ = asr_point(lat, dec, H)
        E.append(e); N.append(n)
    return min(E) - margin, max(E) + margin, min(N) - margin, max(N) + margin
