"""Alinea dos poses del mismo objeto emparejando sólo el objeto: fotos recortadas y sin fondo (recortar.py), las de B también giradas.
uso: alinear_objeto.py <trabajo A> <trabajo B> <recortes A (giro 0)> <recortes B giro 0,90,180,270 separados por coma> <tfl> <ancho,alto> [cada n]
Cada carpeta de recortes trae kp_/gxy_/gd_ (XfHarness in + xfpy bb + XfHarness sel sobre los recortes) y rect.txt."""
import sys, os, time, numpy as np
from ai_edge_litert.interpreter import Interpreter
A, B, CA, CBS, TF = sys.argv[1:6]; W, H = (int(x) for x in sys.argv[6].split(',')); EV = int(sys.argv[7]) if len(sys.argv) > 7 else 1
N = 1024; CERCA = float(os.environ.get('CERCA', '3'))
def load(d):
    pts = []; of = {}
    for l in open(f'{d}/obj3d.txt'):
        a = l.split(); pid = len(pts); pts.append([float(a[0]), float(a[1]), float(a[2]), int(a[3])])
        for o in a[4:]:
            f, k = o.split(':'); of[(int(f), int(k))] = pid
    return np.array(pts), of
pa, ofa = load(A); pb, ofb = load(B)
def rects(d): return {int(l.split()[0]): [float(x) for x in l.split()[1:]] for l in open(f'{d}/rect.txt') if l.strip()}
def back(k, xy):  # de la foto girada k° (antihorario) a la sin girar
    x, y = xy[:, 0], xy[:, 1]
    if k == 90: return np.c_[W - 1 - y, x]
    if k == 180: return np.c_[W - 1 - x, H - 1 - y]
    if k == 270: return np.c_[y, H - 1 - x]
    return xy
def photo(d, k, j, r):  # puntos clave del recorte, en px de la foto original
    xy = back(k, np.fromfile(f'{d}/kp_{j:03d}.bin', '<f4').reshape(-1, 2))
    return np.c_[r[0] + (xy[:, 0] + 0.5) * r[2] / W - 0.5, r[1] + (xy[:, 1] + 0.5) * r[3] / H - 0.5]
def to3d(scan, of, pts, j, xy):  # el punto 3D del objeto cuyo punto clave original está más cerca (a menos de CERCA px), o -1
    kp0 = np.fromfile(f'{scan}/kp_{j:03d}.bin', '<f4').reshape(-1, 2); has = np.array([of.get((j, u), -1) for u in range(len(kp0))])
    good = has >= 0
    if good.any(): good[good] = pts[has[good], 3] == 1
    out = -np.ones(N, int)
    if not good.any(): return out
    src = kp0[good]; ids = has[good]; dd = np.linalg.norm(xy[:, None, :] - src[None, :, :], axis=2); nn = dd.argmin(1); ok = dd[np.arange(len(xy)), nn] < CERCA
    out[:len(xy)][ok] = ids[nn[ok]]; return out
it = Interpreter(f'{TF}/lighterglue.tflite', num_threads=8); run = it.get_signature_runner('serving_default')
L = lambda d, n, i, s: np.fromfile(f'{d}/{n}_{i:03d}.bin', '<f4').reshape(s)
ra = rects(CA); fa = sorted(ra)[::EV]; ta = {i: to3d(A, ofa, pa, i, photo(CA, 0, i, ra[i])) for i in fa}
print(f'A: {len(fa)} fotos, puntos clave con punto 3D del objeto por foto: mediana {int(np.median([(t >= 0).sum() for t in ta.values()]))}')
t0 = time.time(); res = []
for g in CBS.split(','):
    k = int(g.split(':')[0]); d = g.split(':')[1]; rb = rects(d); fb = sorted(rb)[::EV]; P = []; Q = []; raw = []
    for j in fb:
        tb = to3d(B, ofb, pb, j, photo(d, k, j, rb[j]))
        for i in fa:
            sc = run(args_0=L(CA, 'gxy', i, (1, N, 2)), args_1=L(d, 'gxy', j, (1, N, 2)), args_2=L(CA, 'gd', i, (1, N, 64)), args_3=L(d, 'gd', j, (1, N, 64)))['output_0'][0]
            m0, m1, v = sc.argmax(1), sc.argmax(0), sc.max(1); idx = np.arange(N); ok = (m1[m0] == idx) & (np.exp(v) > 0.1); raw.append(int(ok.sum()))
            for u in idx[ok]:
                x, y = ta[i][u], tb[m0[u]]
                if x >= 0 and y >= 0: P.append(pa[x, :3]); Q.append(pb[y, :3])
    res.append((k, np.array(P).reshape(-1, 3), np.array(Q).reshape(-1, 3)))
    print(f'B girado {k}°: {len(fb)} fotos · pares por par de fotos: mediana {int(np.median(raw))}, máx {max(raw)}, pares de fotos con 40+: {sum(r >= 40 for r in raw)} · pares 3D-3D {len(P)}')
print(f'{time.time()-t0:.0f} s')
def kabsch(P, Q):
    cp, cq = P.mean(0), Q.mean(0); U, S_, Vt = np.linalg.svd((P - cp).T @ (Q - cq)); d = np.sign(np.linalg.det(Vt.T @ U.T))
    R = Vt.T @ np.diag([1, 1, d]) @ U.T; return R, cq - R @ cp
def fit(P, Q, tol=4):
    if len(P) < 6: return None
    rng = np.random.default_rng(0); best = None
    for _ in range(20000):
        s = rng.choice(len(P), 3, replace=False); R, t = kabsch(P[s], Q[s]); inl = np.linalg.norm(P @ R.T + t - Q, axis=1) < tol
        if best is None or inl.sum() > best.sum(): best = inl
    inl = best
    for _ in range(3):
        if inl.sum() < 3: return None
        R, t = kabsch(P[inl], Q[inl]); e = np.linalg.norm(P @ R.T + t - Q, axis=1); inl = e < tol
    return R, t, inl, e
for k, P, Q in res:
    f = fit(P, Q); print(f'  giro {k}° solo: ' + ('muy pocos pares' if f is None else f'coherentes {f[2].sum()} de {len(P)} ({100*f[2].mean():.0f} %)'))
P = np.concatenate([r[1] for r in res]); Q = np.concatenate([r[2] for r in res]); f = fit(P, Q)
if f is None: print('muy pocos pares'); sys.exit()
R, t, inl, e = f; ang = np.degrees(np.arccos(np.clip((np.trace(R) - 1) / 2, -1, 1))); tilt = np.degrees(np.arccos(np.clip(R[2, 2], -1, 1)))
print(f'todos juntos: coherentes (a menos de 4 mm) {inl.sum()} de {len(P)} ({100*inl.mean():.0f} %) · error mediano {np.median(e[inl]):.1f} mm')
print(f'giro total {ang:.1f}° · inclinación respecto de la vertical {tilt:.1f}° · traslación {np.round(t, 1)} mm')
np.savetxt(f'{B}/../alin_obj_{os.path.basename(A)}_{os.path.basename(B)}.txt', np.c_[R, t])
