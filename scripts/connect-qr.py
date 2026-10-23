#!/usr/bin/env python3
"""Print a QR code that connects the Android app to a hub.

    muxway hub token create --name phone --read 'alice-laptop/*' | scripts/connect-qr.py https://hub.example.com

The token comes from stdin (the first mxt_... word) or as the second
argument. The code holds `muxway://connect?hub=<url>&token=<token>` and is
drawn with block characters in explicit black and white, so it scans from
any terminal theme. No dependencies beyond the standard library.

    --text      print the payload instead of the code
    --modules   print the modules as rows of 0 and 1 (for tests)
"""
import re
import sys
from urllib.parse import quote

# QR, byte mode, error correction level M, versions 1 to 10.
# version: (error correction codewords per block, data codewords of each block)
TABLE = {
    1: (10, [16]),
    2: (16, [28]),
    3: (26, [44]),
    4: (18, [32, 32]),
    5: (24, [43, 43]),
    6: (16, [27] * 4),
    7: (18, [31] * 4),
    8: (22, [38, 38, 39, 39]),
    9: (22, [36, 36, 36, 37, 37]),
    10: (26, [43] * 4 + [44]),
}
ALIGN = {2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30], 6: [6, 34], 7: [6, 22, 38], 8: [6, 24, 42], 9: [6, 26, 46], 10: [6, 28, 50]}


def payload(hub, token):
    return "muxway://connect?hub=%s&token=%s" % (quote(hub, safe=":/"), quote(token, safe=""))


# --- Reed-Solomon over GF(256), polynomial 0x11D ---

EXP = [0] * 512
LOG = [0] * 256
_x = 1
for _i in range(255):
    EXP[_i] = _x
    LOG[_x] = _i
    _x <<= 1
    if _x & 0x100:
        _x ^= 0x11D
for _i in range(255, 512):
    EXP[_i] = EXP[_i - 255]


def gf_mul(a, b):
    return 0 if a == 0 or b == 0 else EXP[LOG[a] + LOG[b]]


def rs_generator(n):
    """(x - a^0)(x - a^1)...(x - a^(n-1)), highest degree first."""
    g = [1]
    for i in range(n):
        ng = [0] * (len(g) + 1)
        for j, c in enumerate(g):
            ng[j] ^= c
            ng[j + 1] ^= gf_mul(c, EXP[i])
        g = ng
    return g


def rs_remainder(data, gen):
    rem = [0] * (len(gen) - 1)
    for b in data:
        factor = b ^ rem[0]
        rem = rem[1:] + [0]
        for i in range(len(rem)):
            rem[i] ^= gf_mul(gen[i + 1], factor)
    return rem


# --- Encoding ---

def encode_data(text, version):
    data = text.encode("utf-8")
    ec, blocks = TABLE[version]
    capacity = sum(blocks)
    bits = []

    def put(value, n):
        for i in range(n - 1, -1, -1):
            bits.append((value >> i) & 1)

    put(0b0100, 4)
    put(len(data), 16 if version >= 10 else 8)
    for b in data:
        put(b, 8)
    if len(bits) > capacity * 8:
        return None
    put(0, min(4, capacity * 8 - len(bits)))
    while len(bits) % 8:
        bits.append(0)
    words = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]
    pad = (0xEC, 0x11)
    i = 0
    while len(words) < capacity:
        words.append(pad[i % 2])
        i += 1
    gen = rs_generator(ec)
    chunks, ecs, pos = [], [], 0
    for size in blocks:
        chunk = words[pos:pos + size]
        pos += size
        chunks.append(chunk)
        ecs.append(rs_remainder(chunk, gen))
    out = []
    for i in range(max(blocks)):
        for c in chunks:
            if i < len(c):
                out.append(c[i])
    for i in range(ec):
        for e in ecs:
            out.append(e[i])
    return out


def bch(value, poly, shift):
    rem = value << shift
    top = poly.bit_length() - 1
    for i in range(value.bit_length() - 1 + shift, top - 1, -1):
        if rem >> i & 1:
            rem ^= poly << (i - top)
    return rem


class Matrix:
    def __init__(self, version):
        self.version = version
        self.size = version * 4 + 17
        self.m = [[False] * self.size for _ in range(self.size)]
        self.fixed = [[False] * self.size for _ in range(self.size)]

    def set(self, x, y, dark):
        self.m[y][x] = dark
        self.fixed[y][x] = True

    def functions(self):
        s = self.size
        for x in range(s):  # timing
            self.set(x, 6, x % 2 == 0)
            self.set(6, x, x % 2 == 0)
        for cx, cy in ((3, 3), (s - 4, 3), (3, s - 4)):  # finders with separators
            for dy in range(-4, 5):
                for dx in range(-4, 5):
                    x, y = cx + dx, cy + dy
                    if 0 <= x < s and 0 <= y < s:
                        d = max(abs(dx), abs(dy))
                        self.set(x, y, d not in (2, 4))
        for c in ALIGN.get(self.version, []):  # alignment
            for r in ALIGN[self.version]:
                if (c == 6 and r == 6) or (c == 6 and r == s - 7) or (c == s - 7 and r == 6):
                    continue
                for dy in range(-2, 3):
                    for dx in range(-2, 3):
                        self.set(c + dx, r + dy, max(abs(dx), abs(dy)) != 1)
        self.format_bits(0)  # reserve
        if self.version >= 7:
            bits = (self.version << 12) | bch(self.version, 0x1F25, 12)
            for i in range(18):
                bit = bits >> i & 1
                a, b = s - 11 + i % 3, i // 3
                self.set(a, b, bit)
                self.set(b, a, bit)

    def format_bits(self, mask):
        data = (0b00 << 3) | mask  # level M is 00
        bits = ((data << 10) | bch(data, 0x537, 10)) ^ 0x5412
        s = self.size
        for i in range(6):
            self.set(8, i, bits >> i & 1)
        self.set(8, 7, bits >> 6 & 1)
        self.set(8, 8, bits >> 7 & 1)
        self.set(7, 8, bits >> 8 & 1)
        for i in range(9, 15):
            self.set(14 - i, 8, bits >> i & 1)
        for i in range(8):
            self.set(s - 1 - i, 8, bits >> i & 1)
        for i in range(8, 15):
            self.set(8, s - 15 + i, bits >> i & 1)
        self.set(8, s - 8, True)

    def place(self, words):
        s = self.size
        i = 0
        total = len(words) * 8
        right = s - 1
        while right >= 1:
            if right == 6:
                right = 5
            for vert in range(s):
                for j in range(2):
                    x = right - j
                    upward = ((right + 1) & 2) == 0
                    y = s - 1 - vert if upward else vert
                    if not self.fixed[y][x] and i < total:
                        self.m[y][x] = bool(words[i >> 3] >> (7 - (i & 7)) & 1)
                        i += 1
            right -= 2

    def masked(self, mask):
        s = self.size
        rules = (
            lambda x, y: (x + y) % 2 == 0,
            lambda x, y: y % 2 == 0,
            lambda x, y: x % 3 == 0,
            lambda x, y: (x + y) % 3 == 0,
            lambda x, y: (x // 3 + y // 2) % 2 == 0,
            lambda x, y: x * y % 2 + x * y % 3 == 0,
            lambda x, y: (x * y % 2 + x * y % 3) % 2 == 0,
            lambda x, y: ((x + y) % 2 + x * y % 3) % 2 == 0,
        )
        rule = rules[mask]
        out = [row[:] for row in self.m]
        for y in range(s):
            for x in range(s):
                if not self.fixed[y][x] and rule(x, y):
                    out[y][x] = not out[y][x]
        # format bits for this mask, written over the reserved area
        saved = self.m
        self.m = out
        self.format_bits(mask)
        self.m = saved
        return out


def penalty(m):
    s = len(m)
    score = 0
    for lines in (m, [list(col) for col in zip(*m)]):
        for line in lines:
            run, prev = 0, None
            for v in line + [None]:
                if v == prev:
                    run += 1
                else:
                    if run >= 5:
                        score += run - 2
                    run, prev = 1, v
            bits = "".join("1" if v else "0" for v in line)
            score += 40 * (bits.count("10111010000") + bits.count("00001011101"))
    for y in range(s - 1):
        for x in range(s - 1):
            if m[y][x] == m[y][x + 1] == m[y + 1][x] == m[y + 1][x + 1]:
                score += 3
    dark = sum(v for row in m for v in row)
    k = abs(dark * 20 - s * s * 10) // (s * s)
    score += 10 * k
    return score


def qr(text):
    for version in range(1, 11):
        words = encode_data(text, version)
        if words is not None:
            break
    else:
        return None
    mx = Matrix(version)
    mx.functions()
    mx.place(words)
    best = min((mx.masked(k) for k in range(8)), key=penalty)
    return best


def render(m):
    quiet = 4
    s = len(m)
    rows = [[False] * (s + 2 * quiet) for _ in range(s + 2 * quiet)]
    for y in range(s):
        for x in range(s):
            rows[y + quiet][x + quiet] = m[y][x]
    if len(rows) % 2:
        rows.append([False] * len(rows[0]))
    out = []
    for y in range(0, len(rows), 2):
        line = []
        for x in range(len(rows[0])):
            top, bottom = rows[y][x], rows[y + 1][x]
            # light modules are drawn white, dark ones stay black
            line.append(" " if top and bottom else "▄" if top else "▀" if bottom else "█")
        out.append("\033[97;40m" + "".join(line) + "\033[0m")
    return "\n".join(out)


def main(argv):
    flags = [a for a in argv if a.startswith("--")]
    args = [a for a in argv if not a.startswith("--")]
    if not args:
        sys.exit(__doc__)
    hub = args[0]
    token = args[1] if len(args) > 1 else None
    if token is None:
        m = re.search(r"mxt_[A-Za-z0-9_-]+", sys.stdin.read())
        if not m:
            sys.exit("no mxt_ token on stdin")
        token = m.group(0)
    text = payload(hub, token)
    if "--text" in flags:
        print(text)
        return
    m = qr(text)
    if m is None:
        sys.exit("too long for a code: shorten the hub URL")
    if "--modules" in flags:
        print("\n".join("".join("1" if v else "0" for v in row) for row in m))
        return
    print(render(m))
    print("scan this in the app: settings, scan code")


if __name__ == "__main__":
    main(sys.argv[1:])
