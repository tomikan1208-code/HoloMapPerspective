"""Hardware OpenGL regression: six textured map planes versus a real 3D mesh.

Compares interior coverage, colors and image centroids from the same eye position.
This is an offscreen geometry test, not a Minecraft client screenshot.
Requires the installed moderngl and numpy packages; no software GL override is set.
"""
import argparse
import importlib.util
import json
import math
import struct
from pathlib import Path

import moderngl
import numpy as np

FACES = {
    "NORTH": ((0, 0, -1), (-1, 0, 0), (0, 1, 0)),
    "SOUTH": ((0, 0, 1), (1, 0, 0), (0, 1, 0)),
    "EAST": ((1, 0, 0), (0, 0, -1), (0, 1, 0)),
    "WEST": ((-1, 0, 0), (0, 0, 1), (0, 1, 0)),
    "UP": ((0, 1, 0), (1, 0, 0), (0, 0, -1)),
    "DOWN": ((0, -1, 0), (1, 0, 0), (0, 0, 1)),
}
OFFSET = 1.01 / 128
CAMERA = """
uniform vec3 camera_eye;
uniform vec3 camera_right;
uniform vec3 camera_up;
uniform vec3 camera_forward;
vec4 project(vec3 point) {
    vec3 d=point-camera_eye;
    float z=dot(d,camera_forward);
    return vec4(1.7*dot(d,camera_right),1.7*dot(d,camera_up),z*1.0004-0.04,z);
}
"""
DIRECT_VERTEX = """#version 330
in vec3 position;
in uint rgb;
in vec3 normal;
uniform vec3 midpoint;
uniform float normalization;
uniform float model_size;
flat out vec3 color;
""" + CAMERA + """
void main() {
    gl_Position=project((position-midpoint)*normalization*model_size);
    float light=clamp(.64+.3*abs(normal.z)+.13*normal.y,.34,1.0);
    color=vec3(float((rgb>>16u)&255u),float((rgb>>8u)&255u),float(rgb&255u))/255.0*light;
}
"""
DIRECT_FRAGMENT = """#version 330
flat in vec3 color;
out vec4 result;
void main(){result=vec4(color,1);}
"""
PLANE_VERTEX = """#version 330
in vec3 position;
in vec2 uv;
out vec2 texture_uv;
""" + CAMERA + """
void main(){gl_Position=project(position);texture_uv=uv;}
"""
PLANE_FRAGMENT = """#version 330
uniform sampler2D image;
in vec2 texture_uv;
out vec4 result;
void main(){result=texture(image,texture_uv);if(result.a<.5)discard;}
"""


def neighborhood(array, reducer):
    padded = np.pad(array, 1, constant_values=False)
    return reducer([padded[y:y + array.shape[0], x:x + array.shape[1]] for y in range(3) for x in range(3)], axis=0)


def unit(vector):
    return vector / np.linalg.norm(vector)


def run(arguments):
    spec = importlib.util.spec_from_file_location("hmap_test_renderer", arguments.renderer)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    renderer = module.GpuRenderer()
    context = renderer.ctx
    direct = context.program(vertex_shader=DIRECT_VERTEX, fragment_shader=DIRECT_FRAGMENT)
    plane = context.program(vertex_shader=PLANE_VERTEX, fragment_shader=PLANE_FRAGMENT)
    data = arguments.mesh.read_bytes()
    assert data[:8] == b"HMAPGPU1"
    count, *bounds = struct.unpack_from("<i6f", data, 8)
    dtype = np.dtype([("position", "<f4", (3,)), ("rgb", "<u4")])
    vertices = np.frombuffer(data, dtype=dtype, offset=36, count=count * 3)
    triangles = vertices["position"].reshape(-1, 3, 3)
    normals = np.cross(triangles[:, 1]-triangles[:, 0], triangles[:, 2]-triangles[:, 0])
    normals /= np.maximum(np.linalg.norm(normals, axis=1, keepdims=True), 1e-10)
    reference = np.empty(count * 3, dtype=np.dtype([("position", "<f4", (3,)), ("rgb", "<u4"), ("normal", "<f4", (3,))]))
    reference["position"] = vertices["position"]
    reference["rgb"] = vertices["rgb"]
    reference["normal"] = np.repeat(normals, 3, axis=0)
    vbo = context.buffer(reference.tobytes())
    mesh_vao = context.vertex_array(direct, [(vbo, "3f 1u 3f", "position", "rgb", "normal")])
    low, high = np.array(bounds[:3]), np.array(bounds[3:])
    middle = (low + high) / 2
    normalization = 1 / max(.001, max(high-low))
    size = 256
    target_color = context.renderbuffer((size, size), components=4)
    target_depth = context.depth_renderbuffer((size, size))
    target = context.framebuffer(color_attachments=[target_color], depth_attachment=target_depth)
    cases = []
    for dimensions in ((1, 2, 1), (2, 4, 2), (4, 4, 4)):
        for elevation in (0, 4, -4):
            for angle in range(0, 360, 15 if not arguments.quick else 90):
                radians = math.radians(angle)
                cases.append((dimensions, np.array((6*math.sin(radians), elevation, 6*math.cos(radians))), angle))
        for sign in (-1, 1):
            cases.append((dimensions, np.array((0., sign*6., 0.)), "pole"))
    results = []
    if arguments.near:
        cases = []
        for clearance in (.15,.3,.6):
            for angle in range(0,360,15):
                radians=math.radians(angle)
                x,z=math.sin(radians),math.cos(radians)
                radius=(1+clearance)/max(abs(x),abs(z))
                cases.append(((2,4,2),np.array((x*radius,.35,z*radius)),angle))
    for dimensions, eye, angle in cases:
        dimensions = np.array(dimensions)
        model_size = .82 * min(dimensions)
        forward = unit(-eye)
        right = unit(np.cross(forward, np.array((0., 1., 0.)) if abs(forward[1]) < .99 else np.array((0., 0., 1.))))
        up = np.cross(right, forward)
        for program in (direct, plane):
            for name, value in (("camera_eye", eye), ("camera_right", right), ("camera_up", up), ("camera_forward", forward)):
                program[name].value = tuple(value)
        direct["midpoint"].value = tuple(middle)
        direct["normalization"].value = normalization
        direct["model_size"].value = float(model_size)
        target.use(); context.viewport = (0, 0, size, size); target.clear(0, 0, 0, 0, depth=1)
        mesh_vao.render(moderngl.TRIANGLES)
        expected = np.frombuffer(target.read(components=4, alignment=1), dtype=np.uint8).reshape(size, size, 4).copy()
        maps = []
        map_pixels = 0
        for name, (normal, horizontal, vertical) in FACES.items():
            n, r, u = np.array(normal), np.array(horizontal), np.array(vertical)
            sign = -1 if arguments.inward else 1
            depth = float(sign*(abs(n)@dimensions)/2+OFFSET)
            if arguments.inward:
                if n[1]: u = -u
                else: r = -r
                n = -n
            bw, bh = int(abs(r)@dimensions), int(abs(u)@dimensions)
            center = n*depth
            local = np.array((r@(eye-center), u@(eye-center), n@(eye-center)))
            if local[2] <= 1e-4:
                continue
            values = [*local, bw, bh, model_size/bh, depth, *middle, normalization, *r, *u, *n]
            rgba = renderer.render(str(arguments.mesh), bw*128, bh*128, values)
            pixels = np.frombuffer(rgba, dtype=np.uint8).reshape(bh*128, bw*128, 4)
            map_pixels += int(np.count_nonzero(pixels[:,:,3]>127))
            texture = context.texture((bw*128, bh*128), 4, pixels[::-1].tobytes())
            texture.filter = (moderngl.NEAREST, moderngl.NEAREST)
            points = []
            for x, y in ((0, 0), (1, 0), (1, 1), (0, 0), (1, 1), (0, 1)):
                point = center+r*(x-.5)*bw+u*(y-.5)*bh
                points.append((*point, x, y))
            buffer = context.buffer(np.array(points, dtype="f4").tobytes())
            vao = context.vertex_array(plane, [(buffer, "3f 2f", "position", "uv")])
            maps.append((texture, buffer, vao))
        target.use(); context.viewport = (0, 0, size, size); target.clear(0, 0, 0, 0, depth=1)
        for texture, _, vao in maps:
            texture.use(); vao.render(moderngl.TRIANGLES)
        actual = np.frombuffer(target.read(components=4, alignment=1), dtype=np.uint8).reshape(size, size, 4).copy()
        for texture, buffer, vao in maps:
            vao.release(); buffer.release(); texture.release()
        expected_mask, actual_mask = expected[:, :, 3] > 127, actual[:, :, 3] > 127
        interior = neighborhood(expected_mask, np.min)
        holes = int(np.count_nonzero(interior & ~neighborhood(actual_mask, np.max)))
        stable = interior & actual_mask
        # Compare flat-color interiors on both images. Single-pixel triangle/material
        # boundaries may sample different texels on a finite-resolution map.
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for image in (expected, actual):
                    stable &= np.max(np.abs(image[:, :, :3].astype(int)-np.roll(image[:, :, :3], (dy, dx), (0, 1)).astype(int)), axis=2) <= 2
        color_errors = int(np.count_nonzero(stable & (np.max(np.abs(actual[:, :, :3].astype(int)-expected[:, :, :3].astype(int)), axis=2) > 3)))
        center_error = float(np.linalg.norm(np.mean(np.argwhere(expected_mask), axis=0)-np.mean(np.argwhere(actual_mask), axis=0)))
        results.append(dict(dimensions=dimensions.tolist(), angle=angle, elevation=float(eye[1]), holes=holes,
                            color_errors=color_errors, stable_pixels=int(stable.sum()), center_error=center_error,
                            map_pixels=map_pixels))
    failures = [row for row in results if row["holes"] > 0 or row["color_errors"] > max(2, row["stable_pixels"]*.002) or row["center_error"] > .7]
    report = dict(result="PASS" if not failures else "FAIL", gpu=renderer.name, cases=len(results),
                  minimum_map_pixels=min(row["map_pixels"] for row in results),
                  median_map_pixels=int(np.median([row["map_pixels"] for row in results])),
                  worst_interior_holes=max(row["holes"] for row in results),
                  worst_color_errors=max(row["color_errors"] for row in results),
                  worst_center_error=round(max(row["center_error"] for row in results), 4), failures=failures[:12])
    print(json.dumps(report, indent=2))
    if arguments.report:
        arguments.report.parent.mkdir(parents=True, exist_ok=True)
        arguments.report.write_text(json.dumps(report, indent=2), encoding="utf-8")
    return 0 if not failures else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--renderer", type=Path, default=Path(__file__).resolve().parents[1]/"src/main/resources/gpu/renderer.py")
    parser.add_argument("--mesh", required=True, type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--quick", action="store_true")
    parser.add_argument("--inward", action="store_true")
    parser.add_argument("--near", action="store_true")
    raise SystemExit(run(parser.parse_args()))
