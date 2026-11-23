"""Deja de cada foto de un ZIP sólo el objeto: fondo gris fuera de la silueta, recorte a su caja (con margen y la proporción de la
foto), ampliado al tamaño de la foto y girado k grados antihorarios. Para emparejar dos poses del mismo objeto sin que el fondo
(distinto en cada toma) confunda al emparejador. Escribe <salida>/fotos y <salida>/rect.txt (foto, x, y, ancho, alto del recorte).
uso: recortar.py <zip> <salida> <k> [una de cada n fotos]"""
import sys, glob, os, numpy as np
from PIL import Image
z, out, k = sys.argv[1], sys.argv[2], int(sys.argv[3]); step = int(sys.argv[4]) if len(sys.argv) > 4 else 1
os.makedirs(out + '/fotos', exist_ok=True); rows = []
fs = sorted(glob.glob(z + '/fotos/*.jpg'))
for n, f in enumerate(fs):
    name = os.path.basename(f); mp = f'{z}/colmap/mascaras/{name}.png'
    im = Image.open(f).convert('RGB'); W, H = im.size
    if n % step or not os.path.exists(mp):
        Image.new('RGB', (W, H) if k % 180 == 0 else (H, W), (128, 128, 128)).save(out + '/fotos/' + name, quality=90); continue  # relleno: el arnés quiere todas
    m = np.asarray(Image.open(mp).convert('L')) > 127
    if m.sum() < 400: Image.new('RGB', (W, H) if k % 180 == 0 else (H, W), (128, 128, 128)).save(out + '/fotos/' + name, quality=90); continue
    ys, xs = np.nonzero(m); x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
    rw, rh = (x1 - x0) * 1.12, (y1 - y0) * 1.12; zoom = min(W / rw, H / rh, 4); rw, rh = W / zoom, H / zoom
    rx = min(max(0, (x0 + x1) / 2 - rw / 2), W - rw); ry = min(max(0, (y0 + y1) / 2 - rh / 2), H - rh)
    a = np.asarray(im).copy(); a[~m] = 128
    c = Image.fromarray(a).resize((W, H), Image.BICUBIC, box=(rx, ry, rx + rw, ry + rh))
    c.rotate(k, expand=True).save(out + '/fotos/' + name, quality=95); rows.append(f'{n} {rx:.3f} {ry:.3f} {rw:.3f} {rh:.3f}')
open(out + '/rect.txt', 'w').write('\n'.join(rows) + '\n'); print(len(rows), 'fotos recortadas')
