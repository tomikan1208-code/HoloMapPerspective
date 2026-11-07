"""Hardware GL regression for UV orientation, alpha cutouts and shared texture eviction."""
import importlib.util
import json
import struct
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

root=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('renderer',root/'src/main/resources/gpu/renderer.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
gpu=module.GpuRenderer()
with tempfile.TemporaryDirectory() as temp:
    temp=Path(temp)
    Image.fromarray(np.array([[[255,0,0,255],[0,255,0,255]],[[0,0,255,255],[255,255,255,0]]],dtype=np.uint8)).save(temp/'atlas.png')
    vertices=[(-1,-1,0,0,0),(1,-1,0,1,0),(1,1,0,1,1),(-1,-1,0,0,0),(1,1,0,1,1),(-1,1,0,0,1)]
    data=b'HMAPTEX1'+struct.pack('<i6f',2,-1,-1,0,1,1,0)+b''.join(struct.pack('<3fI2f',x,y,z,0xffffffff,u,v) for x,y,z,u,v in vertices)
    args=[0,0,2,2,2,1,0,0,0,0,1,1,0,0,0,1,0,0,0,1]
    for index in range(10):
        mesh=temp/('frame-'+str(index)+'.hmesh');mesh.write_bytes(data)
        Path(str(mesh)+'.materials.json').write_text(json.dumps({'segments':[{'first':0,'count':6,'texture':'atlas.png'}]}))
        gpu.mesh(str(mesh))
        gpu.textures[str((temp/'atlas.png').resolve())][0].filter=(gpu.moderngl.NEAREST,gpu.moderngl.NEAREST)
        rgba=np.frombuffer(gpu.render(str(mesh),128,128,args),np.uint8).reshape(128,128,4)
        red,green,blue,clear=rgba[32,32],rgba[32,96],rgba[96,32],rgba[96,96]
        assert red[0]>180 and red[1]<20 and red[2]<20, red
        assert green[1]>180 and green[0]<20 and green[2]<20, green
        assert blue[2]>180 and blue[0]<20 and blue[1]<20, blue
        assert clear[3]==0, clear
    assert len(gpu.meshes)==8
    assert len(gpu.textures)==1 and next(iter(gpu.textures.values()))[1]==8
print('PASS: real GPU UV colors, transparent cutouts and shared texture references across 10 meshes')
