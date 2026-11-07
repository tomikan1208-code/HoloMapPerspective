#!/usr/bin/env python3
"""HoloMapPerspective hardware GPU sidecar. Binary stdio protocol used by GpuEngine.
Install `python -m pip install 'moderngl>=5.8,<6'`; runs on SAME machine as Paper.
GPU rendering is not emulated. An absent OpenGL 3.3 GPU fails clearly in gpu-renderer.log.
"""
import os
import sys
import struct
import platform
import json
from pathlib import Path
from collections import OrderedDict


def log(*parts):
    print(*parts, file=sys.stderr, flush=True)


def read_exact(stream, n):
    parts = bytearray(n)
    view = memoryview(parts)
    left = n
    while left:
        count = stream.readinto(view[n-left:])
        if not count:
            raise EOFError('client disconnected')
        left -= count
    return parts


def send(stream, payload):
    stream.write(struct.pack('>i', len(payload)))
    stream.write(payload)
    stream.flush()


VERTEX = '''#version 330
in vec3 in_pos;
in uint in_rgb;
in vec2 in_uv;
uniform vec3 u_mid;
uniform float u_normalization;
uniform vec3 u_eye;
uniform vec2 u_blocks;
uniform float u_size;
uniform float u_depth;
uniform vec3 u_right;
uniform vec3 u_up;
uniform vec3 u_normal;
uniform bool u_vertex_alpha;
out vec4 v_color;
out vec2 v_uv;
out vec3 v_world;
void main() {
    vec3 world = (in_pos-u_mid)*u_normalization;
    vec3 p = vec3(dot(world,u_right),dot(world,u_up),dot(world,u_normal))*u_size;
    p.z -= u_depth;
    float d = u_eye.z-p.z;
    // Off-axis perspective onto the map plane, not a conventional orbiting camera.
    gl_Position = vec4(2.0*(u_eye.z*p.x-u_eye.x*p.z)/u_blocks.x,
                       2.0*(u_eye.z*p.y-u_eye.y*p.z)/u_blocks.y,
                       d*1.0004-0.04,
                       d);
    v_world = world;
    v_uv = in_uv;
    v_color = vec4(vec3(float((in_rgb >> 16u) & 255u),
                   float((in_rgb >> 8u) & 255u),
                   float(in_rgb & 255u))/255.0,
                   u_vertex_alpha ? float((in_rgb >> 24u) & 255u)/255.0 : 1.0);
}
'''
FRAGMENT = '''#version 330
in vec4 v_color;
in vec2 v_uv;
uniform bool u_textured;
uniform sampler2D u_texture;
in vec3 v_world;
out vec4 out_color;
void main() {
    vec3 n = cross(dFdx(v_world), dFdy(v_world));
    // Derivatives face the camera; restore the mesh winding for world-fixed lighting.
    if (!gl_FrontFacing) n = -n;
    n = normalize(n+vec3(1e-10));
    float light = clamp(0.64 + 0.3*abs(n.z) + 0.13*n.y, 0.34, 1.0);
    vec4 color = v_color * (u_textured ? texture(u_texture,v_uv) : vec4(1));
    if (color.a < 0.5) discard;
    out_color = vec4(color.rgb*light, color.a);
}
'''


class GpuRenderer:
    def __init__(self):
        import moderngl
        self.moderngl=moderngl
        if platform.system() == 'Linux':
            self.ctx=moderngl.create_standalone_context(require=330,backend=os.getenv('HMAP_GL_BACKEND','egl'))
        else:
            self.ctx=moderngl.create_standalone_context(require=330)
        vendor=self.ctx.info.get('GL_VENDOR','unknown')
        gpu=self.ctx.info.get('GL_RENDERER','unknown')
        log('OpenGL GPU:',vendor,gpu)
        if not os.getenv('HMAP_ALLOW_SOFTWARE_GL','').lower() in ('1','true','yes'):
            if any(x in (vendor+' '+gpu).lower() for x in ('llvmpipe','softpipe','swiftshader','software rasterizer','microsoft basic render')):
                raise RuntimeError('Hardware GPU unavailable: '+gpu+' (set HMAP_ALLOW_SOFTWARE_GL=1 only for diagnostic tests)')
        self.name='READY '+vendor+' / '+gpu
        self.program=self.ctx.program(vertex_shader=VERTEX,fragment_shader=FRAGMENT)
        self.meshes=OrderedDict()
        self.materials={}
        self.mesh_textures={}
        self.textures={}
        self.program['u_texture'].value=0
        self.targets={}
        self.ctx.enable_only(moderngl.DEPTH_TEST)
        self.ctx.depth_func='<'

    def mesh(self, path):
        if path in self.meshes:
            self.meshes.move_to_end(path)
            return self.meshes[path]
        with open(path, 'rb') as file:
            header=read_exact(file,36)
            textured=header[:8]==b'HMAPTEX1'
            if header[:8] not in (b'HMAPGPU1',b'HMAPTEX1'):
                raise ValueError('Invalid HMAP GPU mesh header: '+path)
            triangles,=struct.unpack_from('<i',header,8)
            if triangles<1:
                raise ValueError('Empty mesh cache: '+path)
            # Transfer the compact binary mesh to GPU in bounded chunks (no large Python bytes object).
            byte_count=triangles*(72 if textured else 48)
            if os.fstat(file.fileno()).st_size!=36+byte_count:
                raise ValueError('Corrupt mesh cache: '+path)
            vbo=self.ctx.buffer(reserve=byte_count)
            offset=0
            while offset<byte_count:
                chunk=file.read(min(8*1024*1024,byte_count-offset))
                if not chunk:
                    vbo.release()
                    raise EOFError('Mesh cache truncated: '+path)
                vbo.write(chunk,offset=offset)
                offset+=len(chunk)
        content=(vbo,'3f 1u 2f','in_pos','in_rgb','in_uv') if textured else (vbo,'3f 1u','in_pos','in_rgb')
        vao=self.ctx.vertex_array(self.program,[content])
        segments=[]
        textures=set()
        if textured:
            metadata=Path(path+'.materials.json')
            manifest=json.loads(metadata.read_text(encoding='utf-8'))
            expected=0
            for segment in manifest['segments']:
                if segment['first']!=expected or segment['count']<0:
                    raise ValueError('Invalid textured mesh draw range')
                texture=None
                if segment.get('texture'):
                    texture=(Path(path).parent/segment['texture']).resolve()
                    if not texture.is_relative_to(Path(path).parent.resolve()):
                        raise ValueError('Texture must be inside the model directory')
                    textures.add(str(texture))
                segments.append((segment['first'],segment['count'],str(texture) if texture else None))
                expected+=segment['count']
            if expected!=triangles*3:raise ValueError('Texture ranges do not cover mesh')
            for texture_path in textures:
                if texture_path not in self.textures:
                    from PIL import Image
                    with Image.open(texture_path) as source:
                        image=source.convert('RGBA').transpose(Image.Transpose.FLIP_TOP_BOTTOM)
                        texture=self.ctx.texture(image.size,4,image.tobytes())
                        texture.build_mipmaps()
                        texture.filter=(self.moderngl.LINEAR_MIPMAP_LINEAR,self.moderngl.LINEAR)
                    self.textures[texture_path]=[texture,0]
                self.textures[texture_path][1]+=1
        else:segments=[(0,triangles*3,None)]
        self.materials[path]=(textured,segments)
        self.mesh_textures[path]=textures
        self.meshes[path]=(vbo,vao,triangles)
        # Keep several displays plus their next animation frames resident together.
        while len(self.meshes)>8:
            old_path,(old_vbo,old_vao,_)=self.meshes.popitem(last=False)
            old_vao.release();old_vbo.release()
            self.materials.pop(old_path,None)
            for texture_path in self.mesh_textures.pop(old_path,set()):
                self.textures[texture_path][1]-=1
                if self.textures[texture_path][1]==0:
                    self.textures.pop(texture_path)[0].release()
        return vbo,vao,triangles

    def framebuffer(self,width,height):
        key=(width,height)
        if key not in self.targets:
            color=self.ctx.renderbuffer(key,components=4)
            depth=self.ctx.depth_renderbuffer(key)
            fbo=self.ctx.framebuffer(color_attachments=[color],depth_attachment=depth)
            self.targets[key]=(color,depth,fbo)
            while len(self.targets)>4:
                old_key=next(iter(self.targets))
                if old_key!=key:
                    cr,dr,f=self.targets.pop(old_key)
                    f.release();dr.release();cr.release()
                else:break
        return self.targets[key][2]

    def render(self, path,width,height,args):
        if width<1 or height<1 or width*height>8_000_000:
            raise ValueError('Invalid map render size')
        _,vao,count=self.mesh(path)
        ex,ey,ez,bw,bh,scale,depth,mx,my,mz,norm,*basis=args
        if ez<=1e-4:
            return bytes(width*height*4)
        uniforms={
            'u_mid':(mx,my,mz),
            'u_normalization':norm,
            'u_eye':(ex,ey,ez),
            'u_blocks':(bw,bh),
            'u_size':bh*scale,
            'u_depth':depth,
            'u_right':tuple(basis[0:3]),
            'u_up':tuple(basis[3:6]),
            'u_normal':tuple(basis[6:9]),
        }
        for name,value in uniforms.items():self.program[name].value=value
        fbo=self.framebuffer(width,height)
        fbo.use()
        self.ctx.viewport=(0,0,width,height)
        fbo.clear(0,0,0,0,depth=1.0)
        textured,segments=self.materials[path]
        self.program['u_vertex_alpha'].value=textured
        for first,vertices,texture_path in segments:
            self.program['u_textured'].value=texture_path is not None
            if texture_path:self.textures[texture_path][0].use(location=0)
            vao.render(self.moderngl.TRIANGLES,vertices=vertices,first=first)
        rgba=fbo.read(components=4,alignment=1)
        # OpenGL returns bottom-first pixels; map canvases are top-first.
        stride=width*4
        return b''.join(rgba[row*stride:(row+1)*stride] for row in range(height-1,-1,-1))


def main():
    try:
        gpu=GpuRenderer()
        send(sys.stdout.buffer,gpu.name.encode('utf-8'))
    except Exception as exc:
        log('GPU initialization failed:',repr(exc))
        send(sys.stdout.buffer,('ERROR: '+str(exc)).encode('utf-8'))
        return 2
    incoming=sys.stdin.buffer
    outgoing=sys.stdout.buffer
    while True:
        try:
            header=incoming.read(4)
            if not header:return 0
            if len(header)<4:raise EOFError('short request')
            path_len,=struct.unpack('>i',header)
            if not (1<=path_len<=65535):raise ValueError('bad path length')
            path=read_exact(incoming,path_len).decode('utf-8')
            width,height=struct.unpack('>ii',read_exact(incoming,8))
            args=struct.unpack('>20d',read_exact(incoming,20*8))
            response=gpu.render(path,width,height,args)
            send(outgoing,response)
        except EOFError:
            return 0
        except Exception as exc:
            log('GPU render failed:',repr(exc))
            error=str(exc).encode('utf-8')[:8192]
            outgoing.write(struct.pack('>i',-len(error)));outgoing.write(error);outgoing.flush()


if __name__=='__main__':
    sys.exit(main())
