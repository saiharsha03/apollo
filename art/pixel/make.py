"""Draws Apollo's built-in pixel characters (owl, cloud, titan) and writes app/src/main/assets/pixel/<name>.json.
The JSON files are the source the phone, mini-app pages and Athena read (docs/PIXEL.md); this script is only how the
built-ins were drawn, so a hand edit to a JSON file is lost if this runs again. Each character is a body without its
right arm plus anchors (shoulder, head, right edge); a scene draws the arm to a hand point and attaches its props to the
hand, the head or the edge, so the props touch the character.
Run: python make.py <out_dir>   or   python make.py --sheet sheet.png [scene,scene]"""
import json
import os
import sys

W, H = 24, 20
PAL = {'#': '#F2EDE4', '+': '#A39A8C', '-': '#5E574D', 'r': 'red', 'o': '#0E0D0C'}


class C:
    def __init__(self):
        self.g = [['.'] * W for _ in range(H)]

    def put(self, x, y, ch):
        x, y = int(round(x)), int(round(y))
        if 0 <= x < W and 0 <= y < H and ch != '.':
            self.g[y][x] = ch

    def rect(self, x, y, w, h, ch):
        for j in range(h):
            for i in range(w):
                self.put(x + i, y + j, ch)

    def line(self, x0, y0, x1, y1, ch):
        n = max(abs(x1 - x0), abs(y1 - y0), 1)
        for i in range(n + 1):
            self.put(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n, ch)

    def sprite(self, rows, x, y):
        for j, r in enumerate(rows):
            for i, ch in enumerate(r):
                self.put(x + i, y + j, ch)

    def rows(self):
        return [''.join(r) for r in self.g]


def pose(base, **rows):
    out = list(base)
    for k, v in rows.items():
        out[int(k[1:])] = v
    return out

# ------------------------------------------------------------------ characters

# The owl's eye discs, row by row inside a dim rim: the white disc and a black pupil.
EYE = {'w': "-####-", 'p': "-#oo#-", 'r': "-##oo-", 'lid': "-++++-", 'line': "-oooo-"}


def owl(face=('w', 'p', 'p', 'w')):
    rows = [".+..........+.", "..++++++++++..", ".+----++----+."]
    rows += ["+" + EYE[k] + EYE[k] + "+" for k in face]
    return rows + [".+----++----+.", "-+++++oo+++++-", "--+########+--", "--+#-#-#-#-+--", ".-+########+-.", "..-++++++++-..", "...++....++..."]


CLOUD = [
    "....###.....",
    "..#####.##..",
    ".##########.",
    "############",
    "############",
    "####o##o####",
    "####o##o####",
    "##r##++##r##",
    ".##########.",
    "..++++++++..",
]
TITAN = [
    ".....rr.....",
    ".....++.....",
    "..########..",
    "..##o##o##..",
    "..##o##o##..",
    "..###--###..",
    "++########..",
    "+-########..",
    "+-###++###..",
    "..########..",
    "..###..###..",
    ".+++....+++.",
]

# at: top-left of the body; edge: first column right of it; shoulder: where the right arm starts; head: the right side
# of the head at eye level; rest: the right arm hanging; left: the cloud's other little arm.
CHARS = {
    'owl': dict(agent='athena', at=(0, 6), edge=14, shoulder=(13, 15), head=(13, 10), arm='+', thick=2, rest=[(13, 15), (14, 16), (14, 17)],
                poses={'base': owl(), 'blink': owl(('lid', 'lid', 'line', 'w')), 'look': owl(('w', 'r', 'r', 'w')),
                       'down': owl(('w', 'w', 'p', 'p')), 'shut': owl(('lid', 'lid', 'lid', 'line'))}),
    'cloud': dict(agent='iris', at=(1, 10), edge=13, shoulder=(12, 16), head=(12, 15), arm='+', thick=1, rest=[(13, 17), (14, 18)],
                  left=[(0, 16), (0, 17)],
                  poses={'base': CLOUD,
                         'blink': pose(CLOUD, r5="############", r6="###oo##oo###"),
                         'look': pose(CLOUD, r5="#####o##o###", r6="#####o##o###"),
                         'down': pose(CLOUD, r5="############", r6="#####o##o###"),
                         'shut': pose(CLOUD, r5="############", r6="###--##--###")}),
    'titan': dict(agent='atlas', at=(0, 8), edge=10, shoulder=(10, 14), head=(10, 11), arm='+', thick=2, rest=[(10, 14), (11, 15), (11, 16)],
                  poses={'base': TITAN,
                         'blink': pose(TITAN, r3="..########..", r4="..#oo##oo#.."),
                         'look': pose(TITAN, r3="..###o##o#..", r4="..###o##o#.."),
                         'down': pose(TITAN, r3="..########..", r4="..###o##o#.."),
                         'shut': pose(TITAN, r3="..########..", r4="..#--##--#..", r0=".....--.....")}),
}


def body(c, ch, p='base', dy=0):
    x, y = ch['at']
    c.sprite(ch['poses'][p], x, y + dy)
    for lx, ly in ch.get('left', []):
        c.put(lx, ly + dy, ch['arm'])


def arm(c, ch, hand, dy=0):
    """The right arm from the shoulder to the hand; wings and titan arms are two dots thick."""
    sx, sy = ch['shoulder']
    sy += dy
    if ch['thick'] > 1:
        c.line(sx, sy + 1, hand[0], hand[1] + 1, '-')
    c.line(sx, sy, hand[0], hand[1], ch['arm'])
    c.put(hand[0], hand[1], '#')                 # the hand, so it shows on the prop it holds


def rest(c, ch, dy=0):
    for x, y in ch['rest']:
        c.put(x, y + dy, ch['arm'])


def table(c, e, y=17):
    c.rect(e - 1, y, W - e + 1, 1, '-')
    for x in (e, W - 2):
        c.put(x, y + 1, '-'); c.put(x, y + 2, '-')

# ------------------------------------------------------------------ scenes: (ms per frame, frames)


def s_stand(ch):
    out = []
    for p in ['base', 'base', 'look', 'base', 'blink']:
        c = C(); body(c, ch, p); rest(c, ch); out.append(c)
    return 600, out


def s_typing(ch):
    """Typing on a laptop on the table: the hand taps the keys, lines of text appear."""
    e, out = ch['edge'], []
    widths = [4, 3, 4, 2]
    for i in range(4):
        c = C()
        table(c, e)
        c.rect(e + 1, 8, 8, 7, '-'); c.rect(e + 2, 9, 6, 5, 'o')         # lid and screen
        c.rect(e, 15, 10, 2, '-')                                        # the keyboard, keys in grey
        for k in range(4):
            c.put(e + 1 + k * 2, 15, '+')
        for k in range(i + 1):
            c.rect(e + 3, 10 + k, widths[k], 1, '#')
        c.put(e + 3 + widths[i], 10 + i, 'r')
        body(c, ch, 'look' if i % 2 == 0 else 'down')
        arm(c, ch, (e + 2 + (i % 2) * 4, 14 + (i % 2)))
        out.append(c)
    return 200, out


BOOK = ["####.+.####", "#####+#####", "#+++#+#++##", "#####+#####", "#++##+#+++#", "#####+#####", "+++++++++++"]


def s_reading(ch):
    """An open book held up in front; the right page turns over."""
    e, out = ch['edge'], []
    for i in range(5):
        c = C()
        bx, by = e, 9
        c.sprite(BOOK, bx, by)
        if i == 3:                               # the page standing up mid-turn
            c.rect(bx + 5, by + 1, 6, 5, '#'); c.rect(bx + 5, by - 4, 1, 5, '#')
        if i == 4:                               # a fresh right page
            c.rect(bx + 6, by + 2, 4, 1, '#'); c.rect(bx + 7, by + 2, 3, 1, '-')
        body(c, ch, 'blink' if i == 2 else 'down')
        arm(c, ch, (bx, by + 6))
        out.append(c)
    return 600, out


def s_telescope(ch):
    """Eye to a telescope on a tripod, pointed up-right at a star."""
    hx, hy = ch['head']
    out = []
    for i in range(4):
        c = C()
        tx, ty = hx + 8, max(1, hy - 7)
        mx, my = (hx + tx) // 2, (hy + ty) // 2
        c.line(mx, my + 1, mx - 3, H - 1, '-'); c.line(mx, my + 1, mx + 3, H - 1, '-'); c.line(mx, my + 1, mx, H - 1, '-')
        body(c, ch, 'look')
        c.line(hx + 1, hy + 1, tx + 1, ty + 1, '+'); c.line(hx, hy + 1, tx, ty + 1, '+'); c.line(hx, hy, tx, ty, '#')
        c.put(tx + 1, ty, '#'); c.put(tx + 2, ty, '+')
        arm(c, ch, (mx - 1, my + 2))
        sx, sy = [(W - 2, 0), (W - 1, 2), (W - 4, 1)][i % 3]
        c.put(sx, sy, 'r' if i == 1 else '#')
        if i == 1:
            c.put(sx - 1, sy, '+'); c.put(sx, sy + 1, '+')
        out.append(c)
    return 450, out


def s_painting(ch):
    """Brush in hand at an easel; the picture fills in."""
    e, out = ch['edge'], []
    for i in range(4):
        c = C()
        ex = e + 3
        c.line(ex + 1, 12, ex - 1, H - 1, '-'); c.line(ex + 6, 12, ex + 8, H - 1, '-'); c.line(ex + 3, 12, ex + 3, H - 1, '-')
        c.rect(ex - 1, 3, 9, 9, '+'); c.rect(ex, 4, 7, 7, '#')
        if i >= 1:
            c.rect(ex, 9, 7, 2, '-')
        if i >= 2:
            c.rect(ex + 2, 7, 3, 2, '-')
        if i >= 3:
            c.rect(ex + 4, 5, 2, 2, 'r')
        hand = [(e, 12), (e + 1, 11), (e + 1, 9), (e + 2, 8)][i]
        body(c, ch, 'look')
        arm(c, ch, hand)
        c.line(hand[0], hand[1], hand[0] + 2, hand[1] - 2, '-')
        c.put(hand[0] + 2, hand[1] - 2, '#')
        out.append(c)
    return 450, out


def s_mail(ch):
    """A letter slides into the envelope in her hand; the flap closes with a red seal."""
    e, out = ch['edge'], []
    for i in range(5):
        c = C()
        ex, ey = e, 12
        ly = [5, 7, 9, 11, 12][i]
        if i < 4:
            c.rect(ex + 2, ly, 6, 6, '#')
            c.rect(ex + 3, ly + 1, 4, 1, '-'); c.rect(ex + 3, ly + 3, 3, 1, '-')
        c.rect(ex, ey, 10, 7, '+')
        c.line(ex, ey + 6, ex + 4, ey + 3, '-'); c.line(ex + 9, ey + 6, ex + 5, ey + 3, '-')
        if i >= 3:
            c.line(ex, ey, ex + 4, ey + 4, '#'); c.line(ex + 9, ey, ex + 5, ey + 4, '#')
        if i == 4:
            c.put(ex + 4, ey + 4, 'r'); c.put(ex + 5, ey + 4, 'r')
        body(c, ch, 'down' if i < 3 else 'look')
        arm(c, ch, (ex, ey + 4))
        out.append(c)
    return 380, out


def s_phone(ch):
    """A phone held to the side of the head; rings show beside it."""
    hx, hy = ch['head']
    out = []
    for i in range(4):
        c = C()
        body(c, ch, 'look' if i != 2 else 'blink')
        px, py = hx, hy - 3
        c.rect(px, py, 4, 7, '+'); c.rect(px + 1, py + 1, 2, 4, 'o'); c.put(px + 1, py + 5, 'r')
        arm(c, ch, (px + 1, py + 6))
        if i % 2:
            for d in (5, 7):
                c.put(px + d, py + 1, '+'); c.put(px + d + 1, py + 3, '+'); c.put(px + d, py + 5, '+')
        out.append(c)
    return 280, out


def s_note(ch):
    """Passing a folded note across to the right."""
    e, out = ch['edge'], []
    for i in range(5):
        c = C()
        body(c, ch, 'look')
        hand = [(e + 1, 14), (e + 4, 13), (e + 7, 12), (e + 5, 13), (e, 16)][i]
        arm(c, ch, hand)
        note = [(e + 1, 10), (e + 4, 9), (e + 7, 8), (W - 1, 8), None][i]
        if note:
            c.rect(note[0], note[1], 4, 4, '#'); c.line(note[0], note[1] + 3, note[0] + 3, note[1], '+')
        out.append(c)
    return 320, out


def s_coins(ch):
    """Stacking coins on the table, one at a time."""
    e, out = ch['edge'], []
    for i in range(4):
        c = C()
        table(c, e)
        x = e + 3
        for k in range(i + 1):
            y = 15 - k * 2
            c.rect(x, y, 6, 1, '#'); c.rect(x, y + 1, 6, 1, '+'); c.put(x, y, '+'); c.put(x + 5, y, '+')
        top = 13 - i * 2
        c.rect(x, top, 6, 1, '#'); c.put(x, top, '+'); c.put(x + 5, top, '+'); c.put(x + 2, top, 'r')
        body(c, ch, 'down')
        arm(c, ch, (x, top))
        out.append(c)
    return 450, out


def s_talking(ch):
    """A speech bubble at her head, dots appearing."""
    hx, hy = ch['head']
    bx, by = hx + 2, max(0, hy - 9)
    out = []
    for i in range(4):
        c = C()
        body(c, ch, 'base' if i < 3 else 'blink')
        rest(c, ch)
        c.rect(bx + 1, by, 8, 6, '#'); c.rect(bx, by + 1, 10, 4, '#')
        c.put(bx + 1, by + 6, '#'); c.put(bx, by + 7, '#'); c.put(bx - 1, by + 8, '#')
        for k in range(i):
            c.rect(bx + 2 + k * 2, by + 2, 1, 2, 'o')
        out.append(c)
    return 320, out


def s_working(ch):
    """Turning the crank of a big gear."""
    e, out = ch['edge'], []
    g0 = ["..#.#.#..", ".#######.", "###ooo###", ".##o#o##.", "###ooo###", ".#######.", "..#.#.#.."]
    g1 = [".#.#.#.#.", "#.#####.#", ".##ooo##.", "###o#o###", ".##ooo##.", "#.#####.#", ".#.#.#.#."]
    gx, gy = e + 4, 8
    crank = [(e + 1, 9), (e + 2, 13), (e + 1, 16), (e, 13)]
    for i in range(4):
        c = C()
        c.sprite(g0 if i % 2 == 0 else g1, gx, gy)
        cx, cy = crank[i]
        c.line(gx + 4, gy + 3, cx, cy, '-')
        body(c, ch, 'look')
        arm(c, ch, (cx, cy))
        out.append(c)
    return 260, out


def s_tea(ch):
    """A mug in hand, steam rising; a sip."""
    hx, hy = ch['head']
    out = []
    for i in range(4):
        c = C()
        sip = i == 2
        top = min(hy + 1, H - 7)
        mx, my = (ch['edge'] - 1, top - 2) if sip else (ch['edge'] + 1, top)
        body(c, ch, 'blink' if sip else 'base')
        arm(c, ch, (mx, my + 3))
        c.rect(mx, my, 5, 5, '#'); c.rect(mx, my, 5, 1, '+')
        c.put(mx + 5, my + 1, '+'); c.put(mx + 6, my + 2, '+'); c.put(mx + 5, my + 3, '+')
        if not sip:
            for k in range(4):
                c.put(mx + 2 + ((k + i) % 2), my - 2 - k, '+')
        out.append(c)
    return 600, out


def s_sleep(ch):
    hx, hy = ch['head']
    out = []
    for i in range(4):
        c = C()
        dy = -1 if i % 2 else 0
        body(c, ch, 'shut', dy); rest(c, ch, dy)
        if i >= 1:
            c.sprite(["###", ".#.", "###"], hx + 2, hy - 4)
        if i >= 2:
            c.sprite(["####", "..#.", ".#..", "####"], hx + 6, hy - 9)
        out.append(c)
    return 700, out


SCENES = {'stand': s_stand, 'typing': s_typing, 'reading': s_reading, 'telescope': s_telescope, 'painting': s_painting,
          'mail': s_mail, 'phone': s_phone, 'note': s_note, 'coins': s_coins, 'talking': s_talking, 'working': s_working,
          'tea': s_tea, 'sleep': s_sleep}


def build(name):
    out = {}
    for sc, fn in SCENES.items():
        ms, frames = fn(CHARS[name])
        out[sc] = (ms, [f.rows() for f in frames])
    return out


def write(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    for name, ch in CHARS.items():
        items = list(build(name).items())
        lines = ['{', '  "format": 1,', f'  "name": "{name}",', f'  "agent": "{ch["agent"]}",', f'  "w": {W},', f'  "h": {H},',
                 '  "palette": ' + json.dumps(PAL) + ',', '  "scenes": {']
        for si, (sc, (ms, frames)) in enumerate(items):
            lines.append(f'    "{sc}": {{"ms": {ms}, "frames": [')
            lines.append(',\n'.join('      [\n' + ',\n'.join('        "' + r + '"' for r in f) + '\n      ]' for f in frames))
            lines.append('    ]}' + (',' if si < len(items) - 1 else ''))
        lines += ['  }', '}', '']
        text = '\n'.join(lines)
        json.loads(text)
        with open(os.path.join(out_dir, name + '.json'), 'w', encoding='utf-8', newline='\n') as f:
            f.write(text)


def sheet(path, dot=12, names=None, scenes=None):
    from PIL import Image, ImageDraw
    rgb = {k: (0xD7, 0x19, 0x21) if v == 'red' else tuple(int(v[i:i + 2], 16) for i in (1, 3, 5)) for k, v in PAL.items()}
    names = names or list(CHARS)
    built = {n: build(n) for n in names}
    scenes = scenes or list(SCENES)
    most = max(len(built[n][s][1]) for n in names for s in scenes)
    pad = dot * 2
    cw, chh = W * dot + pad, H * dot + pad
    img = Image.new('RGB', (pad + len(names) * most * cw + len(names) * pad, pad + len(scenes) * chh), (0x1C, 0x1A, 0x17))
    d = ImageDraw.Draw(img)
    g = max(1, dot // 7)
    for si, s in enumerate(scenes):
        for ni, n in enumerate(names):
            for fi, f in enumerate(built[n][s][1]):
                ox, oy = pad + (ni * most + fi) * cw + ni * pad, pad + si * chh
                for y, row in enumerate(f):
                    for x, k in enumerate(row):
                        d.rounded_rectangle([ox + x * dot + g, oy + y * dot + g, ox + (x + 1) * dot - g - 1, oy + (y + 1) * dot - g - 1],
                                            radius=dot // 3, fill=rgb.get(k, (0x2E, 0x2A, 0x25)))
    img.save(path)


if __name__ == '__main__':
    a = sys.argv[1:]
    if a and a[0] == '--sheet':
        sheet(a[1], names=a[3].split(',') if len(a) > 3 else None, scenes=a[2].split(',') if len(a) > 2 and a[2] != 'all' else None)
    else:
        write(a[0] if a else 'out')
