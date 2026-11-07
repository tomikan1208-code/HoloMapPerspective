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
in vec3 in_normal;
in float in_edge;
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
uniform bool u_outline;
uniform float u_edge_size;
out vec4 v_color;
out vec2 v_uv;
out vec3 v_world;
out vec3 v_normal;
void main() {
    vec3 world = (in_pos-u_mid)*u_normalization;
    if (u_outline) world += in_normal * in_edge * u_edge_size * 0.0025;
    vec3 p = vec3(dot(world,u_right),dot(world,u_up),dot(world,u_normal))*u_size;
    p.z -= u_depth;
    float d = u_eye.z-p.z;
    // Off-axis perspective onto the map plane, not a conventional orbiting camera.
    gl_Position = vec4(2.0*(u_eye.z*p.x-u_eye.x*p.z)/u_blocks.x,
                       2.0*(u_eye.z*p.y-u_eye.y*p.z)/u_blocks.y,
                       d*1.0004-0.04,
                       d);
    v_world = world;
    v_normal = in_normal;
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
uniform bool u_pmx;
uniform bool u_two_sided;
uniform bool u_blend;
uniform bool u_outline;
uniform vec4 u_edge_color;
uniform bool u_has_toon;
uniform sampler2D u_toon;
uniform int u_sphere_mode;
uniform sampler2D u_sphere;
uniform vec3 u_sphere_right;
uniform vec3 u_sphere_up;
in vec3 v_world;
in vec3 v_normal;
out vec4 out_color;
void main() {
    vec4 color = v_color * (u_textured ? texture(u_texture,v_uv) : vec4(1));
    if (color.a < (u_blend ? 0.00392 : 0.5)) discard;
    if (u_outline) {
        if (gl_FrontFacing) discard;
        out_color = vec4(u_edge_color.rgb, 1.0);
        return;
    }
    if (u_pmx) {
        if (!u_two_sided && !gl_FrontFacing) discard;
        vec3 normal = normalize(v_normal + vec3(1e-10));
        if (!gl_FrontFacing) normal = -normal;
        float light = dot(normal, normalize(vec3(.26,.72,.64)));
        if (u_has_toon) {
            float ramp = clamp(light*.5 + .5, .002, .998);
            color.rgb *= texture(u_toon, vec2(.5, ramp)).rgb;
        }
        if (u_sphere_mode != 0) {
            vec2 uv = vec2(dot(normal,u_sphere_right), dot(normal,u_sphere_up))*.5+.5;
            vec3 reflection = texture(u_sphere, clamp(uv, .002, .998)).rgb;
            if (u_sphere_mode == 1) color.rgb *= reflection;
            else color.rgb += reflection*.55;
        }
        out_color = color;
        return;
    }
    vec3 n = cross(dFdx(v_world), dFdy(v_world));
    // Derivatives face the camera; restore the mesh winding for world-fixed lighting.
    if (!gl_FrontFacing) n = -n;
    n = normalize(n+vec3(1e-10));
    float light = clamp(0.64 + 0.3*abs(n.z) + 0.13*n.y, 0.34, 1.0);
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
        self.program['u_toon'].value=1
        self.program['u_sphere'].value=2
        self.targets={}
        self.ctx.enable_only(moderngl.DEPTH_TEST)
        self.ctx.depth_func='<'

    def mesh(self, path):
        if path in self.meshes:
            self.meshes.move_to_end(path)
            return self.meshes[path]
        with open(path, 'rb') as file:
            header=read_exact(file,36)
            signature=header[:8]
            pmx=signature==b'HMAPTEX2'
            textured=pmx or signature==b'HMAPTEX1'
            if signature not in (b'HMAPGPU1',b'HMAPTEX1',b'HMAPTEX2'):
                raise ValueError('Invalid HMAP GPU mesh header: '+path)
            triangles,=struct.unpack_from('<i',header,8)
            if triangles<1:
                raise ValueError('Empty mesh cache: '+path)
            # Transfer the compact binary mesh to GPU in bounded chunks (no large Python bytes object).
            byte_count=triangles*(120 if pmx else 72 if textured else 48)
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
        if pmx:
            content=(vbo,'3f 1u 2f 3f 1f','in_pos','in_rgb','in_uv','in_normal','in_edge')
        elif textured:
            content=(vbo,'3f 1u 2f','in_pos','in_rgb','in_uv')
        else:
            content=(vbo,'3f 1u','in_pos','in_rgb')
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
                for role in ('texture','toon','sphere'):
                    if not segment.get(role):
                        continue
                    texture=(Path(path).parent/segment[role]).resolve()
                    if not texture.is_relative_to(Path(path).parent.resolve()):
                        raise ValueError('Texture must be inside the model directory')
                    textures.add(str(texture))
                    segment[role]=str(texture)
                # Adjacent materials with identical shader inputs share one draw.
                if pmx and segments and all(segments[-1].get(key)==segment.get(key) for key in
                        ('texture','toon','sphere','sphere_mode','two_sided','blend','edge_color','edge_size')):
                    segments[-1]['count']+=segment['count']
                else:
                    segments.append(segment)
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
        else:segments=[{'first':0,'count':triangles*3}]
        self.materials[path]=(textured,pmx,segments)
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
        textured,pmx,segments=self.materials[path]
        self.program['u_vertex_alpha'].value=textured
        self.program['u_pmx'].value=pmx
        self.program['u_outline'].value=False
        self.program['u_blend'].value=False
        if pmx:
            self.draw_pmx(vao, segments, fbo, ex, ey, ez+depth, basis)
        else:
            for segment in segments:
                self.bind_image(segment, 'texture', 0, 'u_textured')
                vao.render(self.moderngl.TRIANGLES,vertices=segment['count'],first=segment['first'])
        rgba=fbo.read(components=4,alignment=1)
        # OpenGL returns bottom-first pixels; map canvases are top-first.
        stride=width*4
        return b''.join(rgba[row*stride:(row+1)*stride] for row in range(height-1,-1,-1))

    def bind_image(self, segment, role, slot, uniform):
        path=segment.get(role)
        if uniform:
            self.program[uniform].value=bool(path)
        if path:
            self.textures[path][0].use(location=slot)

    def draw_pmx(self, vao, segments, fbo, ex, ey, ez, basis):
        # Transform the eye into model axes so all six maps share the same reflections.
        x,y,z=(ex*basis[i]+ey*basis[i+3]+ez*basis[i+6] for i in range(3))
        horizontal=(x*x+z*z)**.5
        right=(z/horizontal,0,-x/horizontal) if horizontal>1e-6 else (1,0,0)
        distance=max(1e-6,(horizontal*horizontal+y*y)**.5)
        up=(y*right[2]/distance,(z*right[0]-x*right[2])/distance,-y*right[0]/distance)
        self.program['u_sphere_right'].value=right
        self.program['u_sphere_up'].value=up

        def draw(segment):
            self.bind_image(segment,'texture',0,'u_textured')
            self.bind_image(segment,'toon',1,'u_has_toon')
            self.bind_image(segment,'sphere',2,None)
            self.program['u_sphere_mode'].value=segment.get('sphere_mode',0) if segment.get('sphere') else 0
            self.program['u_two_sided'].value=segment.get('two_sided',False)
            vao.render(self.moderngl.TRIANGLES,vertices=segment['count'],first=segment['first'])

        for segment in segments:
            if not segment.get('blend'):
                draw(segment)

        if any(segment.get('blend') for segment in segments):
            self.program['u_blend'].value=True
            self.ctx.enable(self.moderngl.BLEND)
            self.ctx.blend_func=(self.moderngl.SRC_ALPHA,self.moderngl.ONE_MINUS_SRC_ALPHA,
                                 self.moderngl.ONE,self.moderngl.ONE_MINUS_SRC_ALPHA)
            fbo.depth_mask=False
            try:
                for segment in segments:
                    if segment.get('blend'):
                        draw(segment)
            finally:
                fbo.depth_mask=True
                self.ctx.disable(self.moderngl.BLEND)
        # Fill the silhouette outside the visible surface. Thin hair cards and
        # seams retain their painted detail instead of acquiring dark pinholes.
        self.program['u_blend'].value=False
        self.program['u_outline'].value=True
        self.ctx.enable(self.moderngl.BLEND)
        self.ctx.blend_func=(self.moderngl.ONE_MINUS_DST_ALPHA,self.moderngl.ONE)
        fbo.depth_mask=False
        try:
            for segment in segments:
                if segment.get('edge_size',0)>0 and not segment.get('blend'):
                    self.program['u_edge_size'].value=segment['edge_size']
                    self.program['u_edge_color'].value=tuple(segment['edge_color'])
                    self.bind_image(segment,'texture',0,'u_textured')
                    vao.render(self.moderngl.TRIANGLES,vertices=segment['count'],first=segment['first'])
        finally:
            fbo.depth_mask=True
            self.ctx.disable(self.moderngl.BLEND)


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
