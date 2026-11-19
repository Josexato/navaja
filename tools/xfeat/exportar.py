"""Convierte XFeat (cuerpo) y LighterGlue (N fijo, sin poda) a TFLite para Navaja.

uso: python exportar.py <clon de verlab/accelerated_features> <carpeta de salida>
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
class Glue(nn.Module):
    """Puntos normalizados (1,N,2) y descriptores (1,N,64) de dos fotos -> para cada punto de 0 su mejor par en 1 (y viceversa) y el log-puntaje."""
    def __init__(s):
        super().__init__(); s.lg = LighterGlue(f'{XR}/weights/xfeat-lighterglue.pt').net.eval()
    def forward(s, k0, k1, d0, d1):
        g = s.lg
        d0 = g.input_proj(d0); d1 = g.input_proj(d1)
        e0 = g.posenc(k0); e1 = g.posenc(k1)
        for i in range(g.conf.n_layers):
            d0, d1 = g.transformers[i](d0, d1, e0, e1)
        sc, _ = g.log_assignment[g.conf.n_layers - 1](d0, d1)
        sc = sc[:, :-1, :-1]
        v0, m0 = sc.max(2); m1 = sc.max(1).indices
        return m0.to(torch.int32), m1.to(torch.int32), v0

if __name__ == '__main__':
    bb = Backbone().eval()
    with torch.no_grad():
        m = litert_torch.convert(bb, (torch.rand(1, H, W, 1) * 255,))
    m.export(f'{OUT}/xfeat.tflite')
    gl = Glue().eval()
    with torch.no_grad():
        ex = (torch.rand(1, N, 2) * 2 - 1, torch.rand(1, N, 2) * 2 - 1, F.normalize(torch.randn(1, N, 64), dim=-1), F.normalize(torch.randn(1, N, 64), dim=-1))
        m = litert_torch.convert(gl, ex)
    m.export(f'{OUT}/lighterglue.tflite')
    print('listo')
