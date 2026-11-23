"""Paso de LighterGlue de JoinHarness: cada vista de A contra cada vista de B. uso: unir.py <trabajo> <modelos>"""
import sys, numpy as np
from ai_edge_litert.interpreter import Interpreter
WK, TF = sys.argv[1:3]; N = 1024
v = [l.split() for l in open(f'{WK}/vistas.txt') if l.strip()]; A = [int(x[0]) for x in v if x[1] == '0']; B = [int(x[0]) for x in v if x[1] == '1']
it = Interpreter(f'{TF}/lighterglue.tflite', num_threads=8); run = it.get_signature_runner('serving_default')
L = lambda n, i, s: np.fromfile(f'{WK}/{n}_{i:03d}.bin', '<f4').reshape(s)
for x in A:
    for y in B:
        sc = run(args_0=L('gxy', x, (1, N, 2)), args_1=L('gxy', y, (1, N, 2)), args_2=L('gd', x, (1, N, 64)), args_3=L('gd', y, (1, N, 64)))['output_0'][0]
        np.concatenate([sc.argmax(1), sc.argmax(0)]).astype('<i4').tofile(f'{WK}/m_{x:03d}_{y:03d}.bin'); sc.max(1).astype('<f4').tofile(f'{WK}/v_{x:03d}_{y:03d}.bin')
print(len(A) * len(B), 'pares de vistas')
