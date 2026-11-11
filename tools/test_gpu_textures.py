"""Hardware GL regression for textures, packed poses and readback regions."""
import importlib.util
import json
import struct
import tempfile
import zlib
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

    width,height=128,192
    args[:7]=[.9,.35,4,2,3,.16,.75]
    full=np.frombuffer(gpu.render(str(mesh),width,height,args),np.uint8).reshape(height,width,4)
    (x,y,w,h),rgba=gpu.render_region(str(mesh),width,height,args)
    assert 0<x<x+w<width and 0<y<y+h<height
    cropped=np.zeros_like(full)
    cropped[height-y-h:height-y,x:x+w]=np.frombuffer(rgba,np.uint8).reshape(h,w,4)[::-1]
    np.testing.assert_array_equal(cropped,full)

    outside=args.copy()
    outside[0]=20
    assert gpu.render_region(str(mesh),width,height,outside)==(None,b'')
    assert not any(gpu.render(str(mesh),width,height,outside))

    pmx=temp/'outline.hmesh'
    pmx.write_bytes(b'HMAPTEX2'+data[8:36]+b''.join(
        struct.pack('<3fI2f3ff',x,y,z,0xffffffff,u,v,x,y,0,1) for x,y,z,u,v in vertices))
    Path(str(pmx)+'.materials.json').write_text(json.dumps({'segments':[
        {'first':0,'count':6,'texture':'atlas.png','edge_size':150,'edge_color':[0,0,0,1]}
    ]}))
    region,_=gpu.render_region(str(pmx),width,height,args)
    assert region==(0,0,width,height), 'PMX outline can extend past the stored surface bounds'

    # Move the same indexed quad between two independently decodable poses.
    clip=temp/'clip.hanim'
    manifest=json.dumps({'segments':[{'first':0,'count':6,'texture':'atlas.png','texture_alpha':False}]}).encode()
    payload=[module.CLIP_HEADER.pack(b'HMAPANI1',2,4,2,60,-1,-1,0,1,1,0,len(manifest)),manifest]
    payload.append(b''.join(struct.pack('<I2f',0xffffffff,u,v) for u,v in [(0,0),(1,0),(1,1),(0,1)]))
    payload.append(struct.pack('<6I',0,1,2,0,2,3))
    poses=[]
    for left,right in [(8000,24000),(41000,57000)]:
        bottom,top=24000,41000
        raw=struct.pack('<12H',left,bottom,0,right,bottom,0,right,top,0,left,top,0)
        poses.append(zlib.compress(raw))
        payload.append(module.CLIP_ENTRY.pack(len(poses[-1]),left,bottom,0,right,top,0))
    clip.write_bytes(b''.join(payload+poses))
    args[:7]=[0,0,2,2,2,.5,0]
    left=gpu.render(str(clip),128,128,args,pose=0)
    right=gpu.render(str(clip),128,128,args,pose=1)
    region,_=gpu.render_region(str(clip),128,128,args,pose=1)
    assert region[0]>64, region
    assert left!=right and any(left) and any(right)
    assert gpu.render(str(clip),128,128,args,pose=0)==left
    image=np.frombuffer(left,np.uint8).reshape(128,128,4)
    assert image[72,40,3]==255, 'Opaque diffuse override keeps RGB under a mask alpha channel'
    assert len(gpu.clips)==1 and len(gpu.meshes)==8

print('PASS: GPU texture colors, alpha, cropped readback, PMX bounds and random access to packed poses')
