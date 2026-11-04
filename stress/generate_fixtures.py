import io
import os
import random
import sys

from PIL import Image

FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")
TARGET_MB = [2, 2.5, 3, 3.5, 4, 4.5, 5, 3, 3.5, 4, 4.5, 5, 2.5, 3, 3.5, 4, 4.5, 5, 3, 4]
OVERSIZE_MB = 105
INVALID_MB = 1


def random_image(width, height):
    rnd = random.Random()
    img = Image.new("RGB", (width, height))
    px = img.load()
    r, g, b = rnd.randint(0, 255), rnd.randint(0, 255), rnd.randint(0, 255)
    step = max(1, width // 3)
    for y in range(height):
        if y % 16 == 0:
            r = (r + rnd.randint(-40, 40)) % 256
            g = (g + rnd.randint(-40, 40)) % 256
            b = (b + rnd.randint(-40, 40)) % 256
        for x in range(width):
            px[x, y] = ((r + (x * 37 + y * 17) % 64) % 256,
                        (g + (x * 29 + y * 43) % 64) % 256,
                        (b + (x * 53 + y * 7) % 64) % 256)
    return img


def calibrate(target_bytes):
    best = None
    for dim in [(2000, 1500), (3000, 2250), (4000, 3000), (5000, 3750)]:
        img = random_image(*dim)
        for q in (85, 90, 92, 95):
            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=q)
            size = buf.tell()
            diff = abs(size - target_bytes)
            if best is None or diff < best[0]:
                best = (diff, dim, q, size)
    return best[1], best[2], best[3]


def main():
    os.makedirs(FIXTURES, exist_ok=True)
    for idx, mb in enumerate(TARGET_MB, start=1):
        target = int(mb * 1024 * 1024)
        dim, q, size = calibrate(target)
        path = os.path.join(FIXTURES, f"photo_{idx:02d}.jpg")
        img = random_image(*dim)
        img.save(path, format="JPEG", quality=q)
        print(f"{os.path.basename(path)}: {size / 1024 / 1024:.2f} MB ({dim[0]}x{dim[1]} q{q})")

    seed = os.path.join(FIXTURES, "photo_01.jpg")
    with open(seed, "rb") as f:
        base = f.read()
    target = OVERSIZE_MB * 1024 * 1024
    with open(os.path.join(FIXTURES, "oversize.jpg"), "wb") as f:
        f.write(base)
        f.write(os.urandom(max(0, target - len(base))))
    print(f"oversize.jpg: {os.path.getsize(os.path.join(FIXTURES, 'oversize.jpg')) / 1024 / 1024:.1f} MB")

    with open(os.path.join(FIXTURES, "invalid.jpg"), "wb") as f:
        f.write(os.urandom(INVALID_MB * 1024 * 1024))
    print(f"invalid.jpg: {INVALID_MB} MB (bad magic bytes)")


if __name__ == "__main__":
    main()