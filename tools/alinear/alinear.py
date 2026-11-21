"""Alinea dos escaneos del mismo objeto (A y B): XFeat+LighterGlue entre fotos de A y de B, pares 3D-3D de puntos del objeto, RANSAC rígido.
uso: alinear.py <dirA> <dirB> <tfl> [n fotos por escaneo] [solo objeto 1/0]"""
import sys, time, numpy as np
from ai_edge_litert.interpreter import Interpreter
A, B, TF = sys.argv[1:4]; NS = int(sys.argv[4]) if len(sys.argv) > 4 else 12; ONLY = (sys.argv[5] if len(sys.argv) > 5 else '1') == '1'
N = 1024
def load(d):
    pts = []; of = {}
    for l in open(f'{d}/obj3d.txt'):
        a = l.split(); pid = len(pts); pts.append([float(a[0]), float(a[1]), float(a[2]), int(a[3])])
        for o in a[4:]:
            f, k = o.split(':'); of[(int(f), int(k))] = pid
    keys = [int(x) for x in open(f'{d}/keys.txt').read().split()]
    return np.array(pts), of, keys
pa, ofa, ka = load(A); pb, ofb, kb = load(B)
sa = [ka[i] for i in np.linspace(0, len(ka) - 1, NS).round().astype(int)]; sb = [kb[i] for i in np.linspace(0, len(kb) - 1, NS).round().astype(int)]
it = Interpreter(f'{TF}/lighterglue.tflite', num_threads=4); run = it.get_signature_runner('serving_default')
L = lambda d, n, i, s: np.fromfile(f'{d}/{n}_{i:03d}.bin', '<f4').reshape(s)
t0 = time.time(); P = []; Q = []; per = []
for i in sa:
    for j in sb:
        o = run(args_0=L(A, 'gxy', i, (1, N, 2)), args_1=L(B, 'gxy', j, (1, N, 2)), args_2=L(A, 'gd', i, (1, N, 64)), args_3=L(B, 'gd', j, (1, N, 64)))
        m0, m1, v = o['output_0'][0], o['output_1'][0], o['output_2'][0]
        idx = np.arange(N); ok = (m1[m0] == idx) & (np.exp(v) > 0.1); n = 0
        for u in idx[ok]:
            x = ofa.get((i, int(u))); y = ofb.get((j, int(m0[u])))
            if x is None or y is None: continue
            if ONLY and not (pa[x, 3] == 1 and pb[y, 3] == 1): continue
            P.append(pa[x, :3]); Q.append(pb[y, :3]); n += 1
        per.append(n)
P = np.array(P); Q = np.array(Q)
print(f'{len(sa)}×{len(sb)} pares de fotos en {time.time()-t0:.0f} s · pares 3D-3D: {len(P)} (por par de fotos: mediana {int(np.median(per))}, máx {max(per)})')
if len(P) < 6: print('muy pocos pares'); sys.exit()
def kabsch(P, Q):
    cp, cq = P.mean(0), Q.mean(0); H = (P - cp).T @ (Q - cq); U, S, Vt = np.linalg.svd(H); d = np.sign(np.linalg.det(Vt.T @ U.T))
    R = Vt.T @ np.diag([1, 1, d]) @ U.T; return R, cq - R @ cp
rng = np.random.default_rng(0); best = None
for _ in range(3000):
    s = rng.choice(len(P), 3, replace=False); R, t = kabsch(P[s], Q[s]); e = np.linalg.norm(P @ R.T + t - Q, axis=1); inl = e < 4
    if best is None or inl.sum() > best[0]: best = (inl.sum(), inl)
inl = best[1]; R, t = kabsch(P[inl], Q[inl]); e = np.linalg.norm(P @ R.T + t - Q, axis=1); inl = e < 4; R, t = kabsch(P[inl], Q[inl]); e = np.linalg.norm(P @ R.T + t - Q, axis=1)
ang = np.degrees(np.arccos(np.clip((np.trace(R) - 1) / 2, -1, 1))); tilt = np.degrees(np.arccos(np.clip(R[2, 2], -1, 1)))
print(f'coherentes (a menos de 4 mm): {inl.sum()} de {len(P)} ({100*inl.mean():.0f} %) · error mediano {np.median(e[inl]):.1f} mm')
print(f'giro total {ang:.1f}° · inclinación respecto de la vertical {tilt:.1f}° · traslación {np.round(t,1)} mm')
np.savetxt(f'{B}/../alin_{A.split("/")[-1]}_{B.split("/")[-1]}.txt', np.c_[R, t])
