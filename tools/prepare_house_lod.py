"""Run with Blender -b <canonical blend> -P this_script -- <new output directory>.

Reads the current evaluated scene, simplifies copies of repeated meshes once,
and writes a standalone GPU mesh. Never saves or changes the source .blend file.
"""
import bpy
import json
import struct
import sys
from pathlib import Path
from collections import Counter
from mathutils import Vector

output = Path(sys.argv[sys.argv.index("--")+1])
output.mkdir(parents=True, exist_ok=True)
source_path = bpy.data.filepath
graph = bpy.context.evaluated_depsgraph_get()
instances = []
meshes = {}
excluded = []
for instance in graph.object_instances:
    obj = instance.object
    if obj.type != "MESH" or not instance.show_self or obj.hide_render:
        continue
    if obj.name == "EX_前面道路_幅7_1m_参考":
        excluded.append(obj.name)
        continue
    key = obj.data.as_pointer()
    if key not in meshes:
        evaluated = obj.to_mesh(preserve_all_data_layers=True, depsgraph=graph)
        copied = evaluated.copy()
        obj.to_mesh_clear()
        copied.calc_loop_triangles()
        meshes[key] = copied
    instances.append((key, instance.matrix_world.copy()))
counts = Counter(key for key, matrix in instances)
original_triangles = sum(len(mesh.loop_triangles)*counts[key] for key, mesh in meshes.items())
ratio = min(1., 450000/max(1, original_triangles))
print("HMAP_LOD_INPUT", len(instances), len(meshes), original_triangles, "ratio", ratio, flush=True)
temporary = bpy.data.scenes.new("HoloMap_Export_Only")
bpy.context.window.scene = temporary
reduced = {}
for index, (key, mesh) in enumerate(meshes.items()):
    obj = bpy.data.objects.new("HoloMap_Temporary", mesh)
    temporary.collection.objects.link(obj)
    temporary.view_layers[0].objects.active = obj
    obj.select_set(True)
    if len(mesh.loop_triangles) > 12:
        modifier = obj.modifiers.new("Preview_LOD", "DECIMATE")
        modifier.ratio = max(ratio, min(1., 12/len(mesh.loop_triangles)))
        modifier.use_collapse_triangulate = True
        bpy.ops.object.modifier_apply(modifier=modifier.name)
    obj.data.calc_loop_triangles()
    reduced[key] = obj.data.copy()
    bpy.data.objects.remove(obj, do_unlink=True)
    if index % 100 == 0:
        print("HMAP_LOD_MESH", index, len(meshes), flush=True)
low = [float("inf")]*3
high = [float("-inf")]*3
triangles = 0
with (output/"model.hmesh").open("wb") as binary:
    binary.write(bytes(36))
    for key, matrix in instances:
        mesh = reduced[key]
        positions = []
        for vertex in mesh.vertices:
            point = matrix @ vertex.co
            point = (point.x, point.z, -point.y)
            positions.append(point)
            for axis in range(3):
                low[axis], high[axis] = min(low[axis], point[axis]), max(high[axis], point[axis])
        colors = []
        for material in mesh.materials:
            color = material.diffuse_color[:3] if material else (.6, .6, .6)
            if material and material.use_nodes:
                shader = next((node for node in material.node_tree.nodes if node.type == "BSDF_PRINCIPLED"), None)
                if shader:
                    color = shader.inputs["Base Color"].default_value[:3]
            # Native Blender material colors are linear; map colors use display RGB.
            srgb = [12.92*c if c<=.0031308 else 1.055*c**(1/2.4)-.055 for c in color]
            channels = [round(max(0., min(1., c))*255) for c in srgb]
            colors.append(channels[0]<<16 | channels[1]<<8 | channels[2])
        block = bytearray()
        for triangle in mesh.loop_triangles:
            rgb = colors[triangle.material_index] if triangle.material_index < len(colors) else 0x999999
            for vertex in triangle.vertices:
                block.extend(struct.pack("<3fI", *positions[vertex], rgb))
            triangles += 1
        binary.write(block)
    binary.seek(0)
    binary.write(b"HMAPGPU1"+struct.pack("<i6f", triangles, *low, *high))
report = dict(source=source_path, source_saved=False, instances=len(instances), unique_meshes=len(meshes),
              excluded_reference_objects=excluded,
              original_triangles=original_triangles, output_triangles=triangles,
              bounds=[*low, *high], output_bytes=(output/"model.hmesh").stat().st_size)
(output/"preparation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print("HMAP_LOD_DONE", json.dumps(report, ensure_ascii=False), flush=True)
