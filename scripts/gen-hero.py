"""Generates site/hero.svg: Lapland night, aurora, AI constellation. Deterministic.

Usage: python3 scripts/gen-hero.py site/hero.svg
"""
import math, random, sys

W, H, HORIZON = 1600, 640, 500
rnd = random.Random(7)
out = []
a = out.append


def wave(x0, x1, base, amp, phase, freq, steps=24):
    pts = []
    for i in range(steps + 1):
        x = x0 + (x1 - x0) * i / steps
        y = base(x) + amp * math.sin(freq * x / W * 2 * math.pi + phase) \
            + amp * 0.45 * math.sin(freq * 2.3 * x / W * 2 * math.pi + phase * 1.7)
        pts.append((x, y))
    return pts


def curtain(top_base, bottom_base, amp, phase, freq):
    top = wave(-100, W + 100, top_base, amp, phase, freq)
    bot = wave(-100, W + 100, bottom_base, amp * 0.8, phase + 0.6, freq)
    d = "M" + " L".join(f"{x:.0f},{y:.0f}" for x, y in top)
    d += " L" + " L".join(f"{x:.0f},{y:.0f}" for x, y in reversed(bot)) + " Z"
    return d


a(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" preserveAspectRatio="xMidYMid slice" role="img" '
  'aria-label="Northern lights over the fells and a lake in Lapland, with a glowing network of stars">')
a('<defs>')
a('<linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">'
  '<stop offset="0" stop-color="#01030a"/><stop offset=".5" stop-color="#05122b"/>'
  '<stop offset=".8" stop-color="#0a2346"/><stop offset="1" stop-color="#103a5c"/></linearGradient>')
a('<linearGradient id="lake" x1="0" y1="0" x2="0" y2="1">'
  '<stop offset="0" stop-color="#0d2c47"/><stop offset="1" stop-color="#02060d"/></linearGradient>')
a('<linearGradient id="fade" x1="0" y1="0" x2="0" y2="1">'
  '<stop offset="0" stop-color="#fff" stop-opacity=".45"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient>')
a(f'<mask id="lakefade"><rect x="0" y="{HORIZON}" width="{W}" height="{H - HORIZON}" fill="url(#fade)"/></mask>')
COLORS = {"g1": ("#43ffb4", "#8a5cff"), "g2": ("#2fe0d8", "#3f6dff"), "g3": ("#7dffa0", "#ff5fcf")}
a('<filter id="edge" x="-10%" y="-30%" width="120%" height="160%"><feGaussianBlur stdDeviation="10 22"/></filter>')
a('<filter id="soft" x="-20%" y="-50%" width="140%" height="200%"><feGaussianBlur stdDeviation="16"/></filter>')
a('<filter id="rayblur" x="-10%" y="-10%" width="120%" height="120%"><feGaussianBlur stdDeviation="2.5 9"/></filter>')
a('<filter id="glow" x="-200%" y="-200%" width="500%" height="500%"><feGaussianBlur stdDeviation="3" result="b"/>'
  '<feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>')

# Aurora curtains: each morphs between two wave shapes
curtains = [
    # gradient, top, bottom, amplitude, freq, phases, opacity, duration
    ("g2", lambda x: 170 + 60 * math.sin(x / 520), lambda x: 360 + 40 * math.sin(x / 400), 40, 1.3, (0.0, 1.4), .6, 26),
    ("g1", lambda x: 120 + 90 * math.sin(x / 600 + 1), lambda x: 320 + 70 * math.sin(x / 600 + 1), 34, 1.7, (2.0, 3.2), .95, 19),
    ("g3", lambda x: 90 + 50 * math.sin(x / 450 + 2), lambda x: 250 + 50 * math.sin(x / 450 + 2), 26, 2.1, (4.0, 5.1), .45, 23),
]
for i, (g, tb, bb, amp, fr, (p1, p2), op, dur) in enumerate(curtains):
    d1, d2 = curtain(tb, bb, amp, p1, fr), curtain(tb, bb, amp, p2, fr)
    y_top, y_bot = min(tb(x) for x in range(0, W, 50)) - amp * 1.5, max(bb(x) for x in range(0, W, 50)) + amp
    col, top_col = COLORS[g]
    a(f'<linearGradient id="{g}" gradientUnits="userSpaceOnUse" x1="0" y1="{y_top:.0f}" x2="0" y2="{y_bot:.0f}">'
      f'<stop offset="0" stop-color="{top_col}" stop-opacity="0"/>'
      f'<stop offset=".45" stop-color="{top_col}" stop-opacity=".35"/>'
      f'<stop offset=".8" stop-color="{col}" stop-opacity=".9"/>'
      f'<stop offset="1" stop-color="{col}" stop-opacity=".6"/></linearGradient>')
    a(f'<mask id="c{i}" maskUnits="userSpaceOnUse" x="-100" y="-100" width="{W + 200}" height="{HORIZON + 200}">'
      f'<path d="{d1}" fill="#fff" filter="url(#edge)"><animate attributeName="d" dur="{dur}s" repeatCount="indefinite" '
      f'values="{d1};{d2};{d1}"/></path></mask>')
a('</defs>')

a(f'<rect width="{W}" height="{H}" fill="url(#sky)"/>')

# Stars (some twinkle)
a('<g fill="#e8f4ff">')
for _ in range(190):
    x, y = rnd.uniform(0, W), rnd.uniform(0, HORIZON - 40) ** 1.08 / (HORIZON - 40) ** 0.08
    r, o = rnd.uniform(.4, 1.5), rnd.uniform(.25, .9)
    if rnd.random() < .18:
        a(f'<circle cx="{x:.0f}" cy="{y:.0f}" r="{r:.1f}" opacity="{o:.2f}"><animate attributeName="opacity" '
          f'dur="{rnd.uniform(3, 7):.1f}s" begin="{rnd.uniform(0, 5):.1f}s" repeatCount="indefinite" values="{o:.2f};.1;{o:.2f}"/></circle>')
    else:
        a(f'<circle cx="{x:.0f}" cy="{y:.0f}" r="{r:.1f}" opacity="{o:.2f}"/>')
a('</g>')

# Aurora: blurred glow plus vertical rays clipped to each curtain
a('<g id="aurora">')
for i, (g, tb, bb, amp, fr, _, op, dur) in enumerate(curtains):
    a(f'<g mask="url(#c{i})" opacity="{op}">')
    a(f'<rect x="-100" y="0" width="{W + 200}" height="{HORIZON}" fill="url(#{g})" filter="url(#soft)"/>')
    a(f'<g filter="url(#rayblur)">')
    x = -100
    while x < W + 100:
        w = rnd.uniform(3, 11)
        a(f'<rect x="{x:.0f}" y="0" width="{w:.0f}" height="{HORIZON}" fill="url(#{g})" opacity="{rnd.uniform(.25, .95):.2f}"/>')
        x += w + rnd.uniform(2, 14)
    a('</g></g>')
a('</g>')

# AI constellation: a small neural network drawn among the stars, with pulses travelling along edges
nodes = [(x, y + 70) for x, y in [(1060, 70), (1150, 120), (1250, 80), (1330, 150), (1210, 190), (1100, 210), (1420, 100),
         (1480, 200), (1360, 250), (1000, 150), (1500, 60)]]
edges = [(0, 1), (1, 2), (2, 3), (1, 4), (4, 3), (4, 5), (5, 9), (9, 0), (2, 6), (6, 3), (6, 10), (3, 7),
         (7, 8), (8, 4), (1, 5), (6, 7)]
a('<g stroke="#9fe8ff" stroke-width="1" opacity=".55">')
for i, j in edges:
    (x1, y1), (x2, y2) = nodes[i], nodes[j]
    a(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke-opacity="{rnd.uniform(.35, .8):.2f}"/>')
a('</g>')
a('<g fill="#d9fbff" filter="url(#glow)">')
for k, (x, y) in enumerate(nodes):
    r = 2.6 if k in (1, 3, 4, 6) else 1.9
    a(f'<circle cx="{x}" cy="{y}" r="{r}"><animate attributeName="r" dur="{rnd.uniform(3, 6):.1f}s" '
      f'repeatCount="indefinite" values="{r};{r + 1.2:.1f};{r}"/></circle>')
for route, dur, begin in (([9, 0, 1, 2, 6, 10], 7, 0), ([5, 4, 3, 7, 8], 6, 2.5), ([0, 1, 4, 3, 6], 8, 4)):
    path = "M" + " L".join(f"{nodes[n][0]},{nodes[n][1]}" for n in route)
    a(f'<circle r="2.4" fill="#7dffd0" opacity="0"><animateMotion dur="{dur}s" begin="{begin}s" repeatCount="indefinite" '
      f'path="{path}"/><animate attributeName="opacity" dur="{dur}s" begin="{begin}s" repeatCount="indefinite" '
      f'values="0;1;1;0" keyTimes="0;.1;.85;1"/></circle>')
a('</g>')


# Fells (tunturi): far and near silhouettes
def ridge(base, amps, seed):
    r = random.Random(seed)
    pts = []
    for i in range(41):
        x = -20 + (W + 40) * i / 40
        y = base - sum(am * math.sin(x / wl + ph) for am, wl, ph in amps) - r.uniform(0, 4)
        pts.append(f"{x:.0f},{y:.0f}")
    return f'M-20,{H} L' + " L".join(pts) + f' L{W + 20},{H} Z'


a(f'<path d="{ridge(HORIZON - 40, [(38, 260, 1), (18, 110, 2), (10, 50, 0)], 1)}" fill="#0a1d36"/>')
a(f'<path d="{ridge(HORIZON - 6, [(22, 330, 3), (12, 140, 1), (5, 40, 2)], 2)}" fill="#061325"/>')


# Spruces along the shore (slender Lapland spruce)
def spruce(x, base, h):
    w = h * rnd.uniform(.16, .22)
    pts, tiers = [(x, base - h)], max(5, int(h / 9))
    for t in range(1, tiers + 1):
        y = base - h + h * t / tiers
        half = w * (t / tiers) ** .8
        pts += [(x + half, y), (x + half * .35, y - 1)]
    left = [(2 * x - px, py) for px, py in reversed(pts[1:])]
    return "M" + " L".join(f"{px:.1f},{py:.1f}" for px, py in pts + left) + " Z"


trees = []
x = -10
while x < W + 10:
    gap = rnd.uniform(8, 26) if (x < 520 or x > 1120) else rnd.uniform(40, 120)
    trees.append(spruce(x, HORIZON + 4, rnd.uniform(28, 92) if (x < 520 or x > 1120) else rnd.uniform(14, 40)))
    x += gap
a(f'<g id="trees" fill="#02060c"><path d="{" ".join(trees)}"/></g>')

# Lake with reflection of aurora and trees
a(f'<rect x="0" y="{HORIZON}" width="{W}" height="{H - HORIZON}" fill="url(#lake)"/>')
a(f'<g mask="url(#lakefade)" transform="translate(0 {2 * HORIZON}) scale(1 -1)">'
  '<use href="#aurora"/></g>')
a(f'<g transform="translate(0 {2 * HORIZON + 8}) scale(1 -1)" opacity=".7"><use href="#trees"/></g>')
a('<g stroke="#bff9e6" stroke-linecap="round">')
for _ in range(40):
    y = rnd.uniform(HORIZON + 10, H - 10)
    x, w = rnd.uniform(0, W), rnd.uniform(20, 140) * (1 + (y - HORIZON) / 120)
    a(f'<line x1="{x:.0f}" y1="{y:.0f}" x2="{x + w:.0f}" y2="{y:.0f}" stroke-width="{rnd.uniform(.6, 1.6):.1f}" '
      f'stroke-opacity="{rnd.uniform(.04, .16):.2f}"/>')
a('</g>')
a('</svg>')

open(sys.argv[1], "w").write("\n".join(out) + "\n")
