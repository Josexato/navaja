"""Fase Python del arnés: 'bb' corre XFeat sobre in_*.bin; 'glue' corre LighterGlue sobre las entradas que eligió Java. uso: xfpy.py <modo> <trabajo> <tfl> [zip] [cmp]"""
import sys, glob, os, time, numpy as np
from ai_edge_litert.interpreter import Interpreter
mode, WK, TF = sys.argv[1:4]
H, W, N = 960, 512, 1024
def run(it, ins):
    r = it.get_signature_runner('serving_default'); return r(**ins)
if mode == 'bb':
    it = Interpreter(f'{TF}/xfeat.tflite', num_threads=4); t = 0; n = 0
    for p in sorted(glob.glob(f'{WK}/in_*.bin')):
        i = p[-7:-4]; x = np.fromfile(p, '<f4').reshape(1, H, W, 1)
        t0 = time.time(); o = run(it, {'args_0': x}); t += time.time() - t0; n += 1
        o['output_0'].astype('<f4').tofile(f'{WK}/d_{i}.bin'); o['output_1'].astype('<f4').tofile(f'{WK}/h_{i}.bin'); o['output_2'].astype('<f4').tofile(f'{WK}/r_{i}.bin')
    print(f'XFeat: {n} fotos · {1000*t/n:.0f} ms/foto')
    # Control de la imagen de entrada contra F.interpolate de torch (como XFeat)
    import torch, torch.nn.functional as F
    from PIL import Image
    Z = sys.argv[4]; f0 = sorted(glob.glob(f'{Z}/fotos/*.jpg'))[0]
    a = torch.from_numpy(np.asarray(Image.open(f0).convert('RGB')).astype(np.float32)).permute(2, 0, 1)[None].mean(1, keepdim=True)
    ref = F.interpolate(a, (H, W), mode='bilinear', align_corners=False)[0, 0].numpy()
    mine = np.fromfile(f'{WK}/in_000.bin', '<f4').reshape(H, W)
    print(f'entrada Java vs torch: diferencia máx {np.abs(ref - mine).max():.4f} (0..255)')
else:
    it = Interpreter(f'{TF}/lighterglue.tflite', num_threads=4)
    idx = [int(l) for l in open(f'{WK}/keys.txt').read().split() if l.strip()]
    t = 0; n = 0
    for k in range(len(idx)):
        for s in (1, 2, 3):
            if k + s >= len(idx): continue
            i, j = idx[k], idx[k + s]
            if not os.path.exists(f'{WK}/gxy_{i:03d}.bin') or not os.path.exists(f'{WK}/gxy_{j:03d}.bin'): continue
            if os.path.exists(f'{WK}/m_{i:03d}_{j:03d}.bin'): continue
            ins = {'args_0': np.fromfile(f'{WK}/gxy_{i:03d}.bin', '<f4').reshape(1, N, 2), 'args_1': np.fromfile(f'{WK}/gxy_{j:03d}.bin', '<f4').reshape(1, N, 2),
                   'args_2': np.fromfile(f'{WK}/gd_{i:03d}.bin', '<f4').reshape(1, N, 64), 'args_3': np.fromfile(f'{WK}/gd_{j:03d}.bin', '<f4').reshape(1, N, 64)}
            t0 = time.time(); o = run(it, ins); t += time.time() - t0; n += 1
            sc = o['output_0'][0]  # matriz de log-puntajes: el mejor par de cada punto se elige aquí, como en XFeat.runGlue
            np.concatenate([sc.argmax(1), sc.argmax(0)]).astype('<i4').tofile(f'{WK}/m_{i:03d}_{j:03d}.bin')
            sc.max(1).astype('<f4').tofile(f'{WK}/v_{i:03d}_{j:03d}.bin')
    print(f'LighterGlue: {n} pares · {1000*t/n:.0f} ms/par')
