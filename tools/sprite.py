import os, glob
from collections import deque
from PIL import Image, ImageDraw
import numpy as np

out = "app/src/main/res/drawable-nodpi/mochi.png"
os.makedirs(os.path.dirname(out), exist_ok=True)
files = sorted(glob.glob("*.jpg") + glob.glob("*.jpeg") + glob.glob("*.png") + glob.glob("*.JPG"))

if files:
    a = np.array(Image.open(files[0]).convert("RGBA"))
    h, w, _ = a.shape
    bg = a[:, :, :3].astype(int).min(axis=2) >= 244
    seen = np.zeros((h, w), bool)
    q = deque()
    for x in range(w):
        for y in (0, h - 1):
            if bg[y, x] and not seen[y, x]:
                seen[y, x] = True
                q.append((y, x))
    for y in range(h):
        for x in (0, w - 1):
            if bg[y, x] and not seen[y, x]:
                seen[y, x] = True
                q.append((y, x))
    while q:
        y, x = q.popleft()
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and bg[ny, nx] and not seen[ny, nx]:
                seen[ny, nx] = True
                q.append((ny, nx))
    a[:, :, 3] = np.where(seen, 0, 255)
    o = Image.fromarray(a)
    o = o.crop(o.getchannel("A").getbbox())
    o.thumbnail((420, 420))
    o.save(out)
else:
    im = Image.new("RGBA", (245, 420), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse((40, 20, 205, 200), fill=(120, 95, 210, 255))
    d.rounded_rectangle((85, 250, 160, 340), 12, fill=(60, 75, 140, 255))
    d.polygon([(70, 320), (175, 320), (195, 365), (50, 365)], fill=(194, 65, 95, 255))
    d.rectangle((100, 360, 115, 405), fill=(243, 217, 201, 255))
    d.rectangle((130, 360, 145, 405), fill=(243, 217, 201, 255))
    d.ellipse((60, 60, 185, 250), fill=(251, 228, 214, 255))
    for x in (95, 150):
        d.ellipse((x - 12, 140, x + 12, 170), fill=(59, 42, 102, 255))
    d.arc((100, 180, 145, 205), 20, 160, fill=(122, 46, 69, 255), width=4)
    d.pieslice((45, 25, 200, 135), 180, 360, fill=(120, 95, 210, 255))
    im.save(out)
print("listo", out)
