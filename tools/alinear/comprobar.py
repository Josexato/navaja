"""Comprueba una alineación con algo independiente de los pares: lleva los puntos del objeto de A sobre B y mide a qué distancia
queda cada uno del punto de B más cercano (y al revés). Compara con la misma medida sin alinear y con giros al azar.
uso: comprobar.py <trabajo A> <trabajo B> <matriz 3x4 de alinear>"""
import sys, numpy as np
A, B, M = sys.argv[1:4]
def obj(d):
    a = np.array([[float(x) for x in l.split()[:4]] for l in open(f'{d}/obj3d.txt')]); return a[a[:, 3] == 1, :3]
pa, pb = obj(A), obj(B); T = np.loadtxt(M); R, t = T[:, :3], T[:, 3]
def nn(P, Q):
    out = np.empty(len(P))
    for i in range(0, len(P), 400): out[i:i + 400] = np.sqrt(((P[i:i + 400, None, :] - Q[None, :, :]) ** 2).sum(-1).min(1))
    return out
def score(P): d1, d2 = nn(P, pb), nn(pb, P); return np.median(d1), np.median(d2), (d1 < 3).mean(), (d2 < 3).mean()
s = score(pa @ R.T + t); print(f'alineado: A→B mediana {s[0]:.1f} mm ({100*s[2]:.0f} % a menos de 3 mm) · B→A mediana {s[1]:.1f} mm ({100*s[3]:.0f} %)')
s = score(pa - pa.mean(0) + pb.mean(0)); print(f'sin girar (sólo centrado): A→B {s[0]:.1f} mm ({100*s[2]:.0f} %) · B→A {s[1]:.1f} mm ({100*s[3]:.0f} %)')
rng = np.random.default_rng(1); r = []
for _ in range(20):
    q, _ = np.linalg.qr(rng.standard_normal((3, 3))); q *= np.sign(np.linalg.det(q)); r.append(score((pa - pa.mean(0)) @ q.T + pb.mean(0))[0])
print(f'20 giros al azar (centrado): A→B mediana entre {min(r):.1f} y {max(r):.1f} mm')
print('caja de A alineado (mm):', np.round(np.ptp(pa @ R.T + t, axis=0)), '· caja de B:', np.round(np.ptp(pb, axis=0)))
