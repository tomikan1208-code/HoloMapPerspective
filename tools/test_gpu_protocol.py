"""Offline GPU wire-protocol test with a fake OpenGL driver.
Validates full, cropped, empty and error responses, including GL row order.
This is NOT a hardware GPU test; run with Python only.
"""
import os
from io import BytesIO
import sys
import struct
import tempfile
import subprocess
from pathlib import Path

FAKE_MODERNGL='''
DEPTH_TEST=1
TRIANGLES=4
class Buffer:
 def __init__(self, size):self.data=bytearray(size)
 def write(self,data,offset=0):self.data[offset:offset+len(data)]=data
 def release(self):pass
class Uniform:
 def __init__(self):self.value=None
class Program(dict):
 def __getitem__(self,key):
  if key not in self:self[key]=Uniform()
  return super().__getitem__(key)
class VAO:
 def render(self,*args,**kwargs):pass
 def release(self):pass
class FBO:
 def __init__(self,size):self.size=size
 def use(self):pass
 def clear(self,*args,**kwargs):pass
 def read(self,viewport,components=4,alignment=1):
  x,y,w,h=viewport
  assert 0<=x<x+w<=self.size[0] and 0<=y<y+h<=self.size[1]
  return bytes(value for row in range(y,y+h) for col in range(x,x+w)
               for value in (row%256,col%256,33,255))
 def release(self):pass
class Resource:
 def __init__(self,size):self.size=size
 def release(self):pass
class Context:
 def __init__(self):
  self.info={'GL_VENDOR':'Protocol Test','GL_RENDERER':'Mock hardware'}
  self.viewport=None;self.depth_func=None
 def program(self,**kwargs):return Program()
 def buffer(self,reserve):return Buffer(reserve)
 def vertex_array(self,prog,content):return VAO()
 def renderbuffer(self,size,components=4):return Resource(size)
 def depth_renderbuffer(self,size):return Resource(size)
 def framebuffer(self,color_attachments,depth_attachment):return FBO(color_attachments[0].size)
 def enable_only(self,flag):pass
def create_standalone_context(**kwargs):return Context()
'''

def run():
    with tempfile.TemporaryDirectory() as tmp:
        tmp=Path(tmp)
        (tmp/'moderngl.py').write_text(FAKE_MODERNGL)
        mesh=tmp/'mesh.hmesh'
        mesh.write_bytes(b'HMAPGPU1'+struct.pack('<i6f',1,-1,-1,-1,1,1,1)+
                         b''.join(struct.pack('<fffI',*p,0x88bbdd) for p in [(-1,0,0),(1,0,0),(0,1,0)]))
        helper=Path(__file__).resolve().parents[1]/'src/main/resources/gpu/renderer.py'
        env=dict(os.environ,PYTHONPATH=str(tmp))
        path=str(mesh).encode()
        args=[0.,0.,2.,1.,1.,.82,.75,0.,0.,0.,1.,1.,0.,0.,0.,1.,0.,0.,0.,1.]
        cropped=[0,0,4,2,2,.2,0,0,0,0,.5,*args[11:]]
        outside=[10,0,4,2,2,.2,2,0,0,0,.5,*args[11:]]
        outside_near=[20,0,.1,2,2,.2,-.25,0,0,0,.5,*args[11:]]

        def request(w,h,values):
            return struct.pack('>i',len(path))+path+struct.pack('>iii20d',-1,w,h,*values)

        wire=(request(4,3,args)+request(64,48,cropped)+request(64,48,outside)
              +request(64,48,outside_near)+request(0,48,args)+request(4,3,args))
        run=subprocess.run([sys.executable,'-u',str(helper)],env=env,input=wire,capture_output=True,check=True,timeout=10)
        response=BytesIO(run.stdout)

        def length():
            return struct.unpack('>i',response.read(4))[0]

        def image(expected):
            size=length()
            region=struct.unpack('>4i',response.read(16))
            assert region==expected, region
            x,y,w,h=region
            assert size==w*h*4, size
            rgba=response.read(size)
            assert rgba[:4]==bytes((y,x,33,255)), 'Pixels start at the bottom-left of the region'
            assert rgba[-4:]==bytes((y+h-1,x+w-1,33,255))

        hello=response.read(length()).decode()
        assert hello.startswith('HMAP-IPC3 READY '), hello
        image((0,0,4,3))
        image((23,16,18,16))
        assert length()==0
        assert length()==0  # Still outside a lateral clip plane when the box crosses the near plane.
        error_size=length()
        assert error_size<0 and b'Invalid map render size' in response.read(-error_size)
        image((0,0,4,3))
        assert not response.read()
        print('PASS: GPU handshake, bottom-first full and cropped images, empty response and error recovery')

if __name__=='__main__':run()
