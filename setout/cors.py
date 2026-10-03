"""Ina-CORS stations near the Jatipohon site, with their published coordinates.

WHY THIS IS A FILE AND NOT A COMMENT. Which station you process against is the
single decision that decides whether the GNSS half of the setout works at all.
RTKLIB solutions degrade sharply past ~20 km, and the answer here is not close:
Telkom Purwodadi is 7 km away and everything else is 51 km or worse. That is
worth being able to re-derive rather than remember, because if Purwodadi is
red (down) on the day you observe, the fallback is NOT "use the next one" --
it is "come back tomorrow".

All receivers in the network are understood to be Leica GR50, which tracks
GPS L1/L2/L5, Galileo E1/E5a/E5b and BeiDou B1I/B2I/B2a -- a superset of every
band the SkyTraq PX1125R can see (GPS/QZSS L1/L5, Galileo E1/E5a, BDS B1I/B2a).
So the hardware is compatible on every signal that matters. What is NOT
guaranteed is that the station's logging profile actually writes L5/E5a into
the RINEX; that has to be read out of the file header. See check_rinex_header().
"""
import math
from geolib import vincenty_inverse

# Published Ina-CORS coordinates, latitude/longitude in degrees.
STATIONS = [
    ("Telkom Purwodadi",             -7.0960799055556, 110.91420790556),
    ("Telkom Blora",                 -6.9692287527778, 111.41476898611),
    ("Stasiun Radio Pantai Rembang", -6.7031104971255, 111.3330927729),
    ("Telkom Solo",                  -7.5705936222222, 110.83029725833),
    ("Kantor Kel. Tingkir Tengah",   -7.3614310720855, 110.53106745247),
    ("Telkom Semarang",              -6.9566460317486, 110.46141282182),
    ("Telkom Jepara",                -6.5962018,       110.6667557),
    ("Telkom Tugu",                  -6.9872698888889, 110.37693940556),
]

# Sumber, Jatipohon, Grobogan -- approximate, replace with the real mark.
SITE_DEFAULT = (-7.0500, 110.9600)


def rank(lat, lon):
    """Stations sorted by baseline length from (lat, lon). Returns
    (km, name, azimuth_deg, lat, lon) tuples."""
    out = []
    for name, la, lo in STATIONS:
        d, az, _ = vincenty_inverse(lat, lon, la, lo)
        out.append((d / 1000.0, name, az, la, lo))
    out.sort()
    return out


def verdict(km):
    """What a baseline of this length means for a static PPK session.

    The thresholds are RTKLIB's practical ones, not a spec: below ~10 km the
    ionosphere is common enough that even single-frequency L1 resolves, by
    20 km you want both bands, and past ~30 km fix reliability falls off
    faster than session length can buy back."""
    if km < 10:
        return "excellent - even L1-only would fix"
    if km < 20:
        return "good - dual-band fixes comfortably"
    if km < 30:
        return "workable - needs L5/E5a, allow a longer session"
    return "too long - do not rely on it"


def report(lat=None, lon=None):
    lat = SITE_DEFAULT[0] if lat is None else lat
    lon = SITE_DEFAULT[1] if lon is None else lon
    lines = [f"Ina-CORS baselines from ({lat:.6f}, {lon:.6f})", ""]
    lines.append(f"{'#':>2}  {'station':<32}{'baseline':>11}{'azimuth':>9}   verdict")
    lines.append("-" * 88)
    for i, (km, name, az, la, lo) in enumerate(rank(lat, lon), 1):
        lines.append(f"{i:>2}  {name:<32}{km:>8.2f} km{az:>8.1f}   {verdict(km)}")
    lines.append("")
    lines.append("Download: https://srgi.big.go.id  ->  Unduh Data RINEX  (free, login required)")
    lines.append("  30 s epoch interval, 24 h files, available h-2 hours, 1 year of archive.")
    lines.append("  Green in the station catalogue = data present; red = station was down.")
    return "\n".join(lines)


if __name__ == "__main__":
    print(report())
