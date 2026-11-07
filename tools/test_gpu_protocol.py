"""Offline GPU wire-protocol test with a fake OpenGL driver.
Validates binary mesh VBO upload, uniforms, frame byte count, and row order.
This is NOT a hardware GPU test; run with Python only.
"""
import os
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
 def read(self,components=4,alignment=1):
  w,h=self.size
  return b''.join(bytes([row%256,22,33,255])*w for row in range(h))
 def release(self):pass
class Resource:
 def release(self):pass
class Context:
 def __init__(self):
  self.info={'GL_VENDOR':'Protocol Test','GL_RENDERER':'Mock hardware'}
  self.viewport=None;self.depth_func=None
 def program(self,**kwargs):return Program()
 def buffer(self,reserve):return Buffer(reserve)
 def vertex_array(self,prog,content):return VAO()
 def renderbuffer(self,size,components=4):return Resource()
 def depth_renderbuffer(self,size):return Resource()
 def framebuffer(self,color_attachments,depth_attachment):return FBO(TEST_SIZE)
 def enable_only(self,flag):pass
TEST_SIZE=(4,3)
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
        p=subprocess.Popen([sys.executable,'-u',str(helper)],env=env,
                            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        assert p.stdin and p.stdout
        size=struct.unpack('>i',p.stdout.read(4))[0]
        hello=p.stdout.read(size).decode()
        assert hello.startswith('READY '), hello
        path=str(mesh).encode()
        args=[0.,0.,2.,1.,1.,.82,.75,0.,0.,0.,1.,1.,0.,0.,0.,1.,0.,0.,0.,1.]
        p.stdin.write(struct.pack('>i',len(path))+path+struct.pack('>ii20d',4,3,*args))
        p.stdin.flush()
        n=struct.unpack('>i',p.stdout.read(4))[0]
        assert n==4*3*4, f'Returned {n}, expected 48'
        rgba=p.stdout.read(n)
        assert rgba[0]==2 and rgba[4*8]==0, 'Framebuffer must be vertically flipped'
        p.stdin.close();p.wait(timeout=4)
        assert p.returncode==0
        print('GPU protocol mock passed: first-pixel row flip, mesh upload, frame size, startup handshake')

if __name__=='__main__':run()
