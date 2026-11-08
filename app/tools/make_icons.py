import zlib, struct, sys, os
FONT = {
 'X':["10001","01010","00100","00100","00100","01010","10001"],
 'C':["01110","10001","10000","10000","10000","10001","01110"],
 'P':["11110","10001","10001","11110","10000","10000","10000"],
 'E':["11111","10000","10000","11110","10000","10000","11111"],
 'L':["10000","10000","10000","10000","10000","10000","11111"],
 'A':["01110","10001","10001","11111","10001","10001","10001"],
 'N':["10001","11001","10101","10011","10001","10001","10001"],
 'R':["11110","10001","10001","11110","10100","10010","10001"],
 ' ':["00000"]*7,
}
def png(path, w, h, px):
    raw = b''.join(b'\x00' + bytes(px[y]) for y in range(h))
    def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    open(path, 'wb').write(data)
def canvas(w, h, rgb): return [list(rgb) * w for _ in range(h)]
def rect(px, x0, y0, x1, y1, rgb):
    for y in range(max(0, y0), min(len(px), y1)):
        row = px[y]
        for x in range(max(0, x0), min(len(row)//3, x1)):
            row[x*3:x*3+3] = rgb
def text(px, s, x, y, scale, rgb):
    for ch in s:
        g = FONT[ch]
        for gy, line in enumerate(g):
            for gx, bit in enumerate(line):
                if bit == '1': rect(px, x+gx*scale, y+gy*scale, x+(gx+1)*scale, y+(gy+1)*scale, rgb)
        x += 6*scale
TEAL, INK, PAPER, MINT = (14,111,104), (27,43,47), (245,247,244), (95,211,198)
out = sys.argv[1]
# banner 320x180 (xhdpi)
b = canvas(320, 180, TEAL); rect(b, 0, 150, 320, 180, INK)
text(b, "XCPE", 40, 34, 8, PAPER); text(b, "CLEANER", 40, 100, 5, MINT); text(b, "CODAMINDS", 118, 158, 2, MINT) if 'O' in FONT else None
png(os.path.join(out, 'res/drawable-xhdpi/banner.png'), 320, 180, b)
# launcher icon 144x144 (xxhdpi)
i = canvas(144, 144, TEAL); rect(i, 0, 104, 144, 144, INK); text(i, "XC", 30, 28, 8, PAPER); text(i, "CLEAN", 20, 112, 3, MINT)
png(os.path.join(out, 'res/mipmap-xxhdpi/ic_launcher.png'), 144, 144, i)
print("icons written")
