"""Offscreen previews and UV/color/alpha checks using the shipping GPU renderer."""
import importlib.util
import json
import math
import struct
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('renderer', ROOT/'src/main/resources/gpu/renderer.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
gpu = module.GpuRenderer()
results = {}
for name in ('Sparxie', 'home'):
    mesh = ROOT/'build/prepared-models'/name/'model.hmesh'
    triangles, *bounds = struct.unpack_from('<i6f', mesh.read_bytes(), 8)
    low, high = np.array(bounds[:3]), np.array(bounds[3:])
    midpoint = (low+high)/2
    norm = 1/max(high-low)
    if name == 'home':
        normal = np.array((.6, .45, .66)); normal /= np.linalg.norm(normal)
        right = np.cross((0, 1, 0), normal); right /= np.linalg.norm(right)
        up = np.cross(normal, right)
        width, height, bw, bh = 768, 512, 5, 3.333333
        model_size = 3.7
    else:
        right, up, normal = np.eye(3)
        width, height, bw, bh = 384, 768, 2, 4
        model_size = .82*min(np.array((2, 4, 2))/((high-low)*norm))
    args = [0, 0, 6, bw, bh, model_size/bh, 0, *midpoint, norm, *right, *up, *normal]
    rgba = np.frombuffer(gpu.render(str(mesh), width, height, args), np.uint8).reshape(height, width, 4)
    visible = rgba[:, :, 3] >= 128
    assert visible.sum() > 500, name+' is empty'
    colors = np.unique(rgba[visible], axis=0)
    if name == 'Sparxie':
        pixels = rgba[visible, :3].astype(int)
        assert ((pixels[:, 0] > pixels[:, 1]*1.5) & (pixels[:, 0] > 60)).sum() > 100, 'Missing red clothes'
        assert (pixels.max(axis=1)-pixels.min(axis=1) > 60).sum() > 500, 'Missing texture colors'
    out = ROOT/'build'/(name+'-0.6.0-preview.png')
    Image.fromarray(rgba).save(out)
    results[name] = dict(triangles=triangles, colored_pixels=int(visible.sum()), colors=len(colors), preview=str(out))
(ROOT/'build/prepared-models-preview.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
print(json.dumps(results, ensure_ascii=True))
