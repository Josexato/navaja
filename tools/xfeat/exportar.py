"""Convierte XFeat (cuerpo) y LighterGlue (N fijo, sin poda) a TFLite para Navaja.

uso: python exportar.py <clon de verlab/accelerated_features> <carpeta de salida> [--comparar]
requiere: pip install litert-torch==0.10.0 kornia==0.7.2 (y torch, que litert-torch instala)
Los .tflite resultantes van en app/src/main/assets/modelos/ (ver LICENCIA-xfeat.txt)."""
import sys, torch, torch.nn as nn, torch.nn.functional as F
XR, OUT = sys.argv[1:3]
sys.path.insert(0, XR)
import litert_torch
from modules.model import XFeatModel
def _unfold(self, x, ws=8):
    # igual que _unfold2d con un canal, sin tensores de 6 dimensiones (TFLite admite hasta 5)
    B, C, H, W = x.shape
    return x.reshape(B, H // ws, ws, W // ws, ws).permute(0, 2, 4, 1, 3).reshape(B, ws * ws, H // ws, W // ws)
XFeatModel._unfold2d = _unfold
H, W, N = 960, 512, 1024

class Backbone(nn.Module):
    """(1,H,W,1) gris 0..255 -> descriptores (1,H/8,W/8,64) normalizados, mapa de puntos con NMS 5x5 (1,H,W), confiabilidad (1,H/8,W/8)."""
    def __init__(s):
        super().__init__(); s.net = XFeatModel(); s.net.load_state_dict(torch.load(f'{XR}/weights/xfeat.pt', map_location='cpu')); s.net.eval()
    def forward(s, x):
        x = x.permute(0, 3, 1, 2)
        M, K, R = s.net(x)
        M = F.normalize(M, dim=1)
        sc = F.softmax(K, 1)[:, :64]
        b, _, h, w = sc.shape
        hm = sc.permute(0, 2, 3, 1).reshape(b, h, w, 8, 8).permute(0, 1, 3, 2, 4).reshape(b, 1, h * 8, w * 8)
        mx = F.max_pool2d(hm, 5, stride=1, padding=2)
        hm = torch.where(hm >= mx, hm, torch.zeros_like(hm))
        return M.permute(0, 2, 3, 1), hm[:, 0], R[:, 0]

from modules.lighterglue import LighterGlue
def _logsigmoid(z):
    return torch.clamp(z, max=0) - torch.log(1 + torch.exp(-torch.abs(z)))
def _log_softmax(x):
    # log(softmax + un mínimo) en vez de la fórmula exacta: el conversor convertía la exacta en la operación LOG_SOFTMAX,
    # que los aceleradores del teléfono no garantizan. El mínimo sólo cambia los pares de probabilidad < 1e-4 (se
    # descartan igual) y evita log(0) en media precisión.
    return torch.log(F.softmax(x, -1) + 1e-4)

class Glue(nn.Module):
    """Puntos normalizados (1,N,2) y descriptores (1,N,64) de dos fotos -> matriz (1,N,N) con el log-puntaje de cada par.

    Hace lo mismo que el LightGlue de kornia con una sola cabeza de atención, pero escrito para que entre en los
    aceleradores del teléfono: ningún tensor pasa de 3 dimensiones y no usa ARG_MAX, BROADCAST_TO, SELECT ni PAD (el
    delegado de GPU de TFLite no los admite). Por eso la elección del mejor par de cada punto se hace fuera, en Java
    (XFeat.runGlue): aquí sólo sale la matriz, sin la fila y la columna de "sin par", que no se usaban."""
    def __init__(s):
        super().__init__(); s.lg = LighterGlue(f'{XR}/weights/xfeat-lighterglue.pt').net.eval()
        g = s.lg; D = g.transformers[0].self_attn.embed_dim
        assert g.transformers[0].self_attn.num_heads == 1 and g.transformers[0].cross_attn.heads == 1, 'escrito para una sola cabeza'
        # rotate_half como producto por una matriz fija: (x1, x2) -> (-x2, x1) en cada par de canales
        P = torch.zeros(D, D)
        for i in range(0, D, 2): P[i, i + 1] = -1; P[i + 1, i] = 1
        s.register_buffer('P', P); s.D = D
    def posenc(s, k):
        p = s.lg.posenc.Wr(k)
        c, n = torch.cos(p), torch.sin(p)
        # cada frecuencia vale para sus dos canales (repeat_interleave), con tensores de 4 dimensiones como mucho
        return (torch.stack([c, c], -1).reshape(1, N, s.D), torch.stack([n, n], -1).reshape(1, N, s.D))
    def rot(s, e, t):
        return t * e[0] + F.linear(t, s.P) * e[1]
    def self_block(s, b, x, e):
        W, B = b.Wqkv.weight, b.Wqkv.bias  # las salidas vienen intercaladas: q, k, v de cada canal
        q, k, v = (F.linear(x, W[i::3], B[i::3]) for i in range(3))
        q, k = s.rot(e, q), s.rot(e, k)
        att = F.softmax(torch.matmul(q, k.transpose(1, 2)) * s.D ** -0.5, -1)
        return x + b.ffn(torch.cat([x, b.out_proj(torch.matmul(att, v))], -1))
    def cross_block(s, b, x0, x1):
        qk0, qk1 = b.to_qk(x0) * b.scale ** 0.5, b.to_qk(x1) * b.scale ** 0.5
        v0, v1 = b.to_v(x0), b.to_v(x1)
        sim = torch.matmul(qk0, qk1.transpose(1, 2))
        m0 = torch.matmul(F.softmax(sim, -1), v1)
        m1 = torch.matmul(F.softmax(sim.transpose(1, 2), -1), v0)
        m0, m1 = b.to_out(m0), b.to_out(m1)
        return x0 + b.ffn(torch.cat([x0, m0], -1)), x1 + b.ffn(torch.cat([x1, m1], -1))
    def forward(s, k0, k1, d0, d1):
        g = s.lg
        d0 = g.input_proj(d0); d1 = g.input_proj(d1)
        e0 = s.posenc(k0); e1 = s.posenc(k1)
        for i in range(g.conf.n_layers):
            t = g.transformers[i]
            d0 = s.self_block(t.self_attn, d0, e0); d1 = s.self_block(t.self_attn, d1, e1)
            d0, d1 = s.cross_block(t.cross_attn, d0, d1)
        a = g.log_assignment[g.conf.n_layers - 1]
        m0, m1 = a.final_proj(d0) / s.D ** 0.25, a.final_proj(d1) / s.D ** 0.25
        sim = torch.matmul(m0, m1.transpose(1, 2))
        z0, z1 = a.matchability(d0), a.matchability(d1)
        return _log_softmax(sim) + _log_softmax(sim.transpose(1, 2)).transpose(1, 2) + _logsigmoid(z0) + _logsigmoid(z1).transpose(1, 2)

class GlueOriginal(nn.Module):
    """El LightGlue de kornia tal cual, sólo para comprobar que Glue da lo mismo (python exportar.py ... --comparar)."""
    def __init__(s):
        super().__init__(); s.lg = LighterGlue(f'{XR}/weights/xfeat-lighterglue.pt').net.eval()
    def forward(s, k0, k1, d0, d1):
        g = s.lg
        d0 = g.input_proj(d0); d1 = g.input_proj(d1)
        e0 = g.posenc(k0); e1 = g.posenc(k1)
        for i in range(g.conf.n_layers):
            d0, d1 = g.transformers[i](d0, d1, e0, e1)
        sc, _ = g.log_assignment[g.conf.n_layers - 1](d0, d1)
        return sc[:, :-1, :-1]

if __name__ == '__main__':
    bb = Backbone().eval()
    with torch.no_grad():
        m = litert_torch.convert(bb, (torch.rand(1, H, W, 1) * 255,))
    m.export(f'{OUT}/xfeat.tflite')
    gl = Glue().eval()
    with torch.no_grad():
        ex = (torch.rand(1, N, 2) * 2 - 1, torch.rand(1, N, 2) * 2 - 1, F.normalize(torch.randn(1, N, 64), dim=-1), F.normalize(torch.randn(1, N, 64), dim=-1))
        if '--comparar' in sys.argv:
            a, b = gl(*ex), GlueOriginal().eval()(*ex)
            print('diferencia máxima con el original:', float((a - b).abs().max()), '· mismos pares:', float((a.argmax(2) == b.argmax(2)).float().mean()))
        m = litert_torch.convert(gl, ex)
    m.export(f'{OUT}/lighterglue.tflite')
    print('listo')
