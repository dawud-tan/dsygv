// observe.js -- what a home gnomon should show, next to what praytime.js v3.2 says.
//
//   bun observe.js --from 2027-06-21 --days 3                 (site defaults below)
//   bun observe.js --from 2027-06-21 --hin 3.207 --hout 1.503 --horizon 0.12
//   bun observe.js --from 2027-01-01 --days 365 --csv > year.csv
//
// Needs praytime.js v3.2 next to this file, or --praytime <path>. setout/praytime.js IS that
// file, unmodified from npm praytime@3.2.0 (src/praytime.js; tarball sha1
// aeb2c93e9358bf076f3ddd631181bff40db844cc; MIT, see praytime.LICENSE). It is vendored on
// purpose: v3.2 is the thing under test, so it must not drift with an upgrade.
// The house-side counterpart is setout.py, which stakes both gnomons; the design,
// dimensions and procedures are in qibla-setout-field-sheet.html (quarkus repo), served at
// http://183.81.158.231:8080/qibla-setout-field-sheet.html -- part two, stages 8-12.
//
// Definitions (the ones asked for, not the code's defaults):
//   Dhuhr   = trailing (east) limb leaves the meridian plane  (PrayTimes "A Note on Dhuhr", def. 3)
//   Asr     = shadow = height + shadow at midday, both measured, i.e. with refraction
//   Maghrib = upper limb gone below the real skyline, + 2 min
// Solar model: the same low-precision series praytime.js uses (checked against the NREL
// SPA at this site: <= 1.1 s for transit, <= 1.5 s for Asr, <= 1.8 s for sunset), plus
// the Earth-Sun distance, which the code does not need but the disc size does.

const path = require('path');
const fs = require('fs');

const A = Object.fromEntries(process.argv.slice(2).join(' ').split('--').filter(Boolean)
    .map(s => s.trim().split(/\s+/)).map(([k, ...v]) => [k, v.length ? v.join(' ') : true]));
const num = (k, d) => (A[k] === undefined ? d : Number(A[k]));

const LAT = num('lat', -6.96595767);   // 294deg15'18" integer-arcsecond qibla site on 110.9274 E
const LON = num('lon', 110.9274);
const TZ = num('tz', 7);               // WIB
const H_IN = num('hin', 3.20);         // roof-aperture height above the meridian line, m
const H_OUT = num('hout', 1.50);       // outdoor gnomon: ball-nodus centre height above its pad, m
const T = num('T', 28), P = num('P', 1005);
const DAYS = num('days', 1);
const HORIZON = num('horizon', 0);     // apparent skyline altitude at the sunset azimuth, deg
const from = (A.from || new Date().toISOString().slice(0, 10)).split('-').map(Number);

const { PrayTime } = require(A.praytime || path.join(__dirname, 'praytime.js'));
const code = new PrayTime('Singapore')                    // fajr 20, isha 18 = Kemenag
    .adjust({ asr: 'Standard', maghrib: '2 min', dhuhr: '0 min' })
    .location([LAT, LON]).utcOffset(TZ).round('none').format('x');
const codeConv = new PrayTime('Singapore')
    .adjust({ asr: 'Standard', maghrib: '2 min', dhuhr: '0 min', iterations: 5 })
    .location([LAT, LON]).utcOffset(TZ).round('none').format('x');

// Skyline profile: lines of "azimuth altitude" in degrees, measured from the eye/screen
// position with the total station. Linear interpolation; flat HORIZON if absent.
let profile = null;
if (A['horizon-file']) {
    profile = fs.readFileSync(A['horizon-file'], 'utf8').split('\n')
        .map(l => l.trim().split(/[\s,]+/).map(Number)).filter(p => p.length >= 2 && !isNaN(p[1]))
        .sort((a, b) => a[0] - b[0]);
}
const skyline = az => {
    if (!profile) return HORIZON;
    for (let i = 1; i < profile.length; i++)
        if (az <= profile[i][0]) {
            const [a0, h0] = profile[i - 1], [a1, h1] = profile[i];
            return h0 + (h1 - h0) * (az - a0) / (a1 - a0);
        }
    return profile[profile.length - 1][1];
};

const rad = Math.PI / 180, deg = 180 / Math.PI;
const mod = (a, b) => ((a % b) + b) % b;

// Sun at UT instant t (ms): the praytime.js series, evaluated at the real instant.
function sun(t) {
    const D = t / 864e5 - 10957.5;
    const g = mod(357.529 + 0.98560028 * D, 360);
    const q = mod(280.459 + 0.98564736 * D, 360);
    const L = mod(q + 1.915 * Math.sin(g * rad) + 0.020 * Math.sin(2 * g * rad), 360);
    const e = 23.439 - 0.00000036 * D;
    const RA = mod(Math.atan2(Math.cos(e * rad) * Math.sin(L * rad), Math.cos(L * rad)) * deg / 15, 24);
    const dec = Math.asin(Math.sin(e * rad) * Math.sin(L * rad)) * deg;
    let eqt = q / 15 - RA;
    eqt = mod(eqt + 12, 24) - 12;
    const r = 1.00014 - 0.01671 * Math.cos(g * rad) - 0.00014 * Math.cos(2 * g * rad);
    const ut = mod(t / 36e5, 24);
    const H = mod(15 * (ut + LON / 15 + eqt - 12) + 180, 360) - 180;       // local hour angle
    const sa = Math.sin(LAT * rad) * Math.sin(dec * rad) + Math.cos(LAT * rad) * Math.cos(dec * rad) * Math.cos(H * rad);
    const alt = Math.asin(sa) * deg;
    const az = mod(Math.atan2(-Math.cos(dec * rad) * Math.sin(H * rad),
        Math.sin(dec * rad) * Math.cos(LAT * rad) - Math.cos(dec * rad) * Math.sin(LAT * rad) * Math.cos(H * rad)) * deg, 360);
    return { dec, H, alt, az, sd: 0.266564 / r };
}

// Saemundsson, on a TRUE altitude, scaled to local P and T. Degrees.
const refr = a => (a < -2 ? 0 : (P / 1010) * (283 / (273 + T)) * 1.02 / (60 * Math.tan((a + 10.3 / (a + 5.11)) * rad)));

function bisect(f, a, b) {
    let fa = f(a);
    for (let i = 0; i < 60; i++) {
        const m = (a + b) / 2, fm = f(m);
        if ((fm > 0) === (fa > 0)) { a = m; fa = fm; } else b = m;
    }
    return (a + b) / 2;
}

function day(y, mo, d) {
    const t0 = Date.UTC(y, mo - 1, d) - TZ * 36e5;                  // local midnight, UT ms
    const c = code.times([y, mo, d]), cc = codeConv.times([y, mo, d]);

    const transit = bisect(t => sun(t).H, t0 + 10.5 * 36e5, t0 + 13 * 36e5);
    const n = sun(transit);
    const offMeridian = t => { const s = sun(t); return Math.cos(s.dec * rad) * Math.sin(s.H * rad); };
    const sinSD = Math.sin(n.sd * rad);
    const T1 = bisect(t => offMeridian(t) + sinSD, transit - 3e5, transit);   // leading limb reaches the plane
    const T2 = bisect(t => offMeridian(t) - sinSD, transit, transit + 3e5);   // trailing limb leaves it (Dhuhr)

    const altNoonApp = n.alt + refr(n.alt);
    const z0 = 90 - altNoonApp;
    const asrAlt = Math.atan(1 / (1 + Math.tan(z0 * rad))) * deg;           // apparent
    const asr = bisect(t => { const s = sun(t); return s.alt + refr(s.alt) - asrAlt; }, transit + 6e4, transit + 6 * 36e5);

    const set = bisect(t => {
        const s = sun(t), limb = s.alt + s.sd;
        return limb + refr(limb) - skyline(s.az);
    }, transit + 3 * 36e5, transit + 8 * 36e5);

    const gnomon = H => {
        const s0 = H * Math.tan(z0 * rad);
        const sa = sun(asr), aApp = (sa.alt + refr(sa.alt)) * rad;
        const vNoon = H * Math.cos(n.dec * rad) * (2 * Math.PI / 86400) / Math.sin(altNoonApp * rad) * 60e3; // mm/min
        const a2 = sun(asr + 6e4), a2App = (a2.alt + refr(a2.alt)) * rad;
        const vAsr = (H / Math.tan(a2App) - H / Math.tan(aApp)) * 1e3;                             // mm/min
        return {
            noonOffset: s0, noonSide: n.dec > LAT ? 'S' : 'N',
            noonImage: [H / Math.sin(altNoonApp * rad) * 2 * n.sd * rad * 1e3, H / Math.sin(altNoonApp * rad) ** 2 * 2 * n.sd * rad * 1e3],
            vNoon, asrRadius: H + s0, asrDir: mod(sa.az + 180, 360), vAsr,
            asrImageRadial: H / Math.sin(aApp) ** 2 * 2 * n.sd * rad * 1e3,
        };
    };
    return { c, cc, transit, T1, T2, semidur: (T2 - transit) / 1e3, asr, set, maghrib: set + 12e4, z0, asrAlt,
             setAz: sun(set).az, gin: gnomon(H_IN), gout: gnomon(H_OUT) };
}

const hms = t => {
    const ds = mod(Math.round(t / 100) + TZ * 36000, 864000);            // tenths of a second, rounded once
    const h = Math.floor(ds / 36000), m = Math.floor(ds / 600) % 60, x = (ds % 600) / 10;
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${x.toFixed(1).padStart(4, '0')}`;
};
const sgn = x => (x >= 0 ? '+' : '') + x.toFixed(1);

// --at HH:MM:SS : where was the sun (true, unrefracted) at that local instant? This is how a
// twilight observation (SQM curve knee, first horizontal dawn band) is compared with the
// code's 20 / 18 degree angles: in degrees of depression, not in minutes.
if (A.at) {
    const [hh, mm, ss] = A.at.split(':').map(Number);
    const t = Date.UTC(from[0], from[1] - 1, from[2]) - TZ * 36e5 + ((hh * 60 + mm) * 60 + (ss || 0)) * 1e3;
    const s = sun(t), s2 = sun(t + 6e4);
    console.log(`${from.join('-')} ${A.at} UTC+${TZ}: sun altitude ${s.alt.toFixed(3)} deg (depression ${(-s.alt).toFixed(3)}), `
        + `azimuth ${s.az.toFixed(2)} deg, changing ${((s2.alt - s.alt)).toFixed(4)} deg/min`);
    const c = code.times(from);
    console.log(`code fajr ${hms(c.fajr)} (sun -20)   code isha ${hms(c.isha)} (sun -18)`);
    process.exit(0);
}

if (A.csv) console.log('date,code_dhuhr,code_asr,code_sunset,code_maghrib,code_fajr,code_isha,transit,T1,T2,asr_obs,sunset_obs,maghrib_obs,'
    + 'noon_offset_in,asr_radius_out,asr_dir_out,set_az');
for (let i = 0; i < DAYS; i++) {
    const dt = new Date(Date.UTC(from[0], from[1] - 1, from[2]) + i * 864e5);
    const [y, mo, d] = [dt.getUTCFullYear(), dt.getUTCMonth() + 1, dt.getUTCDate()];
    const r = day(y, mo, d), c = r.c;
    const iso = dt.toISOString().slice(0, 10);
    if (A.csv) {
        console.log([iso, c.dhuhr, c.asr, c.sunset, c.maghrib, c.fajr, c.isha, r.transit, r.T1, r.T2, r.asr, r.set, r.maghrib,
            r.gin.noonOffset.toFixed(4), r.gout.asrRadius.toFixed(4), r.gout.asrDir.toFixed(2), r.setAz.toFixed(2)]
            .map(v => (typeof v === 'number' && v > 1e12 ? Math.round(v) : v)).join(','));
        continue;
    }
    const g = r.gin, o = r.gout;
    console.log(`\n${iso}   lat ${LAT}  lon ${LON}  UTC+${TZ}   (praytime.js v3.2, Singapore = fajr 20 / isha 18, asr Standard, maghrib 2 min, round none)`);
    console.log(`  code   fajr ${hms(c.fajr)}  dhuhr ${hms(c.dhuhr)}  asr ${hms(c.asr)}  sunset ${hms(c.sunset)}  maghrib ${hms(c.maghrib)}  isha ${hms(c.isha)}`);
    console.log(`         (5 iterations: asr ${sgn((r.cc.asr - c.asr) / 1e3)} s, isha ${sgn((r.cc.isha - c.isha) / 1e3)} s, fajr ${sgn((r.cc.fajr - c.fajr) / 1e3)} s vs the default 1)`);
    console.log(`  DHUHR  meridian line, roof aperture H=${H_IN.toFixed(3)} m`);
    console.log(`         image ${g.noonImage[0].toFixed(1)} x ${g.noonImage[1].toFixed(1)} mm, ${g.noonOffset.toFixed(3)} m ${g.noonSide} of the plumb foot, moving ${g.vNoon.toFixed(1)} mm/min`);
    console.log(`         T1 leading edge touches line ${hms(r.T1)}   centre ${hms(r.transit)}   T2 trailing edge leaves (Dhuhr) ${hms(r.T2)}`);
    console.log(`         expect  T2 - code dhuhr = ${sgn((r.T2 - c.dhuhr) / 1e3)} s   (semi-duration ${r.semidur.toFixed(1)} s)`);
    console.log(`  ASR    ball-nodus gnomon, ball centre H=${H_OUT.toFixed(3)} m: arc radius ${o.asrRadius.toFixed(3)} m (= H + noon ${o.noonOffset.toFixed(3)} m ${o.noonSide}), toward azimuth ${o.asrDir.toFixed(1)}`);
    console.log(`         penumbra ${o.asrImageRadial.toFixed(1)} mm along the shadow, shadow moving ${o.vAsr.toFixed(1)} mm/min; sun apparent alt ${r.asrAlt.toFixed(3)} deg`);
    console.log(`         shadow centre on arc ${hms(r.asr)}   expect obs - code asr = ${sgn((r.asr - c.asr) / 1e3)} s`);
    console.log(`  MAGHRIB upper limb below skyline (${profile ? 'profile' : HORIZON.toFixed(3) + ' deg'}) at az ${r.setAz.toFixed(2)}: ${hms(r.set)}  +2 min = ${hms(r.maghrib)}`);
    console.log(`         expect obs - code = ${sgn((r.set - c.sunset) / 1e3)} s   (T ${T} C, P ${P} hPa; night-to-night refraction scatter is larger than this)`);
}
