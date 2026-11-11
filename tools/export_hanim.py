"""Bake an FBX or Blender scene into a GPU-ready HoloMap animation.

blender -b --python-exit-code 1 -P tools/export_hanim.py -- --fbx source.fbx --output myclip
blender -b scene.blend --python-exit-code 1 -P tools/export_hanim.py -- --output myclip
"""

import argparse
from collections import namedtuple
import json
import math
from pathlib import Path
import shutil
import struct
import sys
import tempfile
import zlib

import bpy
import numpy as np


HEADER = struct.Struct("<8siiif6fi")
FRAME_ENTRY = struct.Struct("<i6H")
ATTRIBUTES = np.dtype([("rgba", "<u4"), ("uv", "<f4", 2)])
Surface = namedtuple("Surface", "faces uv face_materials materials")


def read_mesh(obj, depsgraph):
    evaluated = obj.evaluated_get(depsgraph)
    mesh = evaluated.to_mesh()
    try:
        positions = np.empty((len(mesh.vertices), 3), dtype="<f4")
        mesh.vertices.foreach_get("co", positions.ravel())
        matrix = np.asarray(evaluated.matrix_world, dtype=np.float32)
        positions = positions @ matrix[:3, :3].T + matrix[:3, 3]
        # Blender Z-up to HoloMap Y-up, with +Z toward the front of the display.
        positions = positions[:, [0, 2, 1]] * np.array([1, 1, -1], dtype=np.float32)

        mesh.calc_loop_triangles()
        triangles = mesh.loop_triangles
        loops = np.empty((len(triangles), 3), dtype=np.int32)
        triangles.foreach_get("loops", loops.ravel())
        if np.linalg.det(matrix[:3, :3]) < 0:
            loops = loops[:, [0, 2, 1]]
        face_materials = np.empty(len(triangles), dtype=np.int32)
        triangles.foreach_get("material_index", face_materials)
        vertices = np.empty(len(mesh.loops), dtype=np.int32)
        mesh.loops.foreach_get("vertex_index", vertices)
        uv = np.zeros((len(mesh.loops), 2), dtype=np.float32)
        if mesh.uv_layers.active:
            mesh.uv_layers.active.uv.foreach_get("vector", uv.ravel())
        materials = tuple(slot.material for slot in evaluated.material_slots)
        return positions, Surface(vertices[loops], uv[loops], face_materials, materials)
    finally:
        evaluated.to_mesh_clear()


def same_surface(first, current):
    return (first.materials == current.materials
            and np.array_equal(first.faces, current.faces)
            and np.array_equal(first.face_materials, current.face_materials)
            and np.array_equal(first.uv, current.uv))


def material_inputs(material):
    color = tuple(material.diffuse_color) if material else (.8, .8, .8, 1)
    nodes = material.node_tree.nodes if material and material.node_tree else ()
    node = next((node for node in nodes if node.type == "BSDF_PRINCIPLED"), None)
    if node is None:
        return color, None, False
    base = node.inputs["Base Color"]
    alpha = node.inputs["Alpha"]
    if base.is_linked:
        texture = base.links[0].from_node
        if texture.type == "TEX_IMAGE" and texture.image:
            return (1, 1, 1, alpha.default_value), texture.image, alpha.is_linked
        print(f"Warning: {material.name} has a color node that needs baking", flush=True)
    return (*base.default_value[:3], alpha.default_value), None, False


def copy_texture(source, stage, images):
    if source not in images:
        suffix = (source if isinstance(source, Path) else Path(source.filepath)).suffix.lower() or ".png"
        name = f"textures/image-{len(images):02d}{suffix}"
        destination = stage / name
        destination.parent.mkdir(exist_ok=True)
        if isinstance(source, Path):
            shutil.copyfile(source, destination)
        elif source.packed_file:
            destination.write_bytes(source.packed_file.data)
        else:
            original = Path(bpy.path.abspath(source.filepath, library=source.library))
            if not original.is_file():
                raise FileNotFoundError(f"Missing texture for {source.name}: {original}")
            shutil.copyfile(original, destination)
        images[source] = name
    return images[source]


def build_surface(surfaces, overrides, stage):
    sources = []
    attributes = []
    indices = []
    segments = []
    images = {}
    vertex_start = corner_start = 0
    for obj, surface in surfaces.items():
        selected = []
        for slot in np.unique(surface.face_materials):
            faces = surface.face_materials == slot
            if not np.isfinite(surface.uv[faces]).all():
                raise ValueError(f"Invalid UVs on {obj.name}")
            material = surface.materials[slot] if 0 <= slot < len(surface.materials) else None
            color, image, use_alpha = material_inputs(material)
            if obj.name in overrides:
                color, image = (1, 1, 1, color[3]), overrides[obj.name]
            elif material is None:
                print(f"Warning: {obj.name} has {int(faces.sum())} triangles without a material", flush=True)
            source = surface.faces[faces].ravel()
            uv = surface.uv[faces].reshape(-1, 2)
            keys = np.column_stack((source, uv.view(np.int32)))
            unique, inverse = np.unique(keys, axis=0, return_inverse=True)
            selected.append(unique[:, 0].copy())
            values = np.empty(len(unique), dtype=ATTRIBUTES)
            red, green, blue, alpha = np.rint(np.clip(color, 0, 1)*255).astype(int).tolist()
            values["rgba"] = alpha << 24 | red << 16 | green << 8 | blue
            values["uv"] = np.ascontiguousarray(unique[:, 1:]).view(np.float32)
            attributes.append(values)
            indices.append(np.asarray(inverse.reshape(-1)+vertex_start, dtype="<u4"))
            segment = {"first": corner_start, "count": len(source)}
            if image is not None:
                segment["texture"] = copy_texture(image, stage, images)
                if not use_alpha:
                    segment["texture_alpha"] = False
            segments.append(segment)
            vertex_start += len(unique)
            corner_start += len(source)
        if selected:
            sources.append((obj, np.concatenate(selected)))
    if not corner_start:
        raise ValueError("The scene has no visible triangles")
    return sources, attributes, indices, segments


def write_clip(path, spool, attributes, indices, segments, bounds, count, fps):
    vertices = sum(map(len, attributes))
    triangles = sum(map(len, indices)) // 3
    low, high = bounds
    extent = high.astype(np.float64)-low
    scale = np.divide(65535, extent, out=np.zeros(3), where=extent > 0)
    manifest = json.dumps({"segments": segments}, separators=(",", ":")).encode("utf-8")
    if len(manifest) > 1_048_576:
        raise ValueError("Too many material ranges")
    spool.seek(0)
    table = bytearray()
    with path.open("wb") as file:
        file.write(HEADER.pack(b"HMAPANI1", triangles, vertices, count, fps, *low, *high, len(manifest)))
        file.write(manifest)
        for block in attributes + indices:
            file.write(block.tobytes())
        table_start = file.tell()
        file.seek(count*FRAME_ENTRY.size, 1)
        for _ in range(count):
            positions = np.frombuffer(spool.read(vertices*12), dtype="<f4").reshape(-1, 3)
            quantized = np.rint((positions.astype(np.float64)-low)*scale).clip(0, 65535).astype("<u2")
            compressed = zlib.compress(quantized.tobytes(), level=3)
            table.extend(FRAME_ENTRY.pack(len(compressed), *quantized.min(axis=0), *quantized.max(axis=0)))
            file.write(compressed)
        file.seek(table_start)
        file.write(table)
    return triangles, vertices


def export_clip(scene, frames, fps, output, overrides, excluded):
    if output.exists():
        raise FileExistsError(f"Choose a new model directory: {output}")
    scene.frame_set(frames.start)
    objects = [obj for obj in scene.objects if obj.type == "MESH" and obj.visible_get()]
    if set(excluded) - {obj.name for obj in objects}:
        raise ValueError("Excluded object refers to a missing or hidden mesh")
    objects = [obj for obj in objects if obj.name not in excluded]
    if set(overrides) - {obj.name for obj in objects}:
        raise ValueError("Texture override refers to a missing or hidden mesh")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent, prefix=".hanim-") as temp:
        stage = Path(temp) / "model"
        stage.mkdir()
        graph = bpy.context.evaluated_depsgraph_get()
        initial = {obj: read_mesh(obj, graph) for obj in objects}
        surfaces = {obj: data[1] for obj, data in initial.items()}
        sources, attributes, indices, segments = build_surface(surfaces, overrides, stage)
        low, high = np.full(3, np.inf, dtype="<f4"), np.full(3, -np.inf, dtype="<f4")
        with tempfile.TemporaryFile(dir=temp) as spool:
            for i, frame in enumerate(frames):
                if i:
                    scene.frame_set(frame)
                    graph = bpy.context.evaluated_depsgraph_get()
                for obj, used in sources:
                    positions, surface = read_mesh(obj, graph) if i else initial[obj]
                    if not same_surface(surfaces[obj], surface):
                        raise ValueError(f"{obj.name}: topology, UVs or materials changed at frame {frame}")
                    positions = positions[used]
                    if not np.isfinite(positions).all():
                        raise ValueError(f"{obj.name}: non-finite positions at frame {frame}")
                    low = np.minimum(low, positions.min(axis=0))
                    high = np.maximum(high, positions.max(axis=0))
                    spool.write(positions.tobytes())
                if (i+1) % 120 == 0:
                    print(f"Sampled {i+1}/{len(frames)} frames", flush=True)
            triangles, vertices = write_clip(stage / "model.hanim", spool, attributes, indices, segments,
                                             (low, high), len(frames), fps)
        stage.rename(output)
    print(f"Exported {len(frames)} frames at {fps:g} fps, {triangles} triangles, {vertices} indexed vertices")
    print(output / "model.hanim")


def import_fbx(path):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    if "fbx_import" in dir(bpy.ops.wm):
        result = bpy.ops.wm.fbx_import(filepath=str(path))
    else:
        result = bpy.ops.import_scene.fbx(filepath=str(path), use_image_search=True)
    if "FINISHED" not in result:
        raise ValueError(f"Could not import FBX: {path}")
    scene = bpy.context.scene
    keys = [obj.data.shape_keys for obj in scene.objects if obj.type == "MESH" and obj.data.shape_keys]
    actions = {data.animation_data.action for data in [*scene.objects, *keys]
               if data.animation_data and data.animation_data.action}
    if not actions:
        return scene.frame_current, scene.frame_current
    return math.floor(min(a.frame_range[0] for a in actions)), math.ceil(max(a.frame_range[1] for a in actions))


def main():
    args = sys.argv[sys.argv.index("--")+1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fbx", type=Path, help="Import this FBX instead of using the open Blender scene")
    parser.add_argument("--output", required=True, type=Path, help="New HoloMap model directory")
    parser.add_argument("--start", type=int)
    parser.add_argument("--end", type=int)
    parser.add_argument("--step", type=int, default=1)
    parser.add_argument("--texture", action="append", default=[], metavar="OBJECT=IMAGE",
                        help="Replace the diffuse texture on a named mesh using its existing UVs")
    parser.add_argument("--exclude", action="append", default=[], metavar="OBJECT",
                        help="Omit a named mesh from the exported clip")
    options = parser.parse_args(args)
    scene = bpy.context.scene
    start, end = import_fbx(options.fbx.resolve()) if options.fbx else (scene.frame_start, scene.frame_end)
    scene = bpy.context.scene
    start = options.start if options.start is not None else start
    end = options.end if options.end is not None else end
    if options.step < 1 or end < start:
        parser.error("Invalid frame range or step")
    overrides = {}
    for override in options.texture:
        obj, separator, file = override.partition("=")
        if not separator or not obj or not Path(file).is_file() or obj in overrides:
            parser.error(f"Invalid texture override: {override}")
        overrides[obj] = Path(file).resolve()
    fps = scene.render.fps / scene.render.fps_base / options.step
    export_clip(scene, range(start, end+1, options.step), fps, options.output.resolve(), overrides, set(options.exclude))


if __name__ == "__main__":
    main()
