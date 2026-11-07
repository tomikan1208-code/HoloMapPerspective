"""Export an animated Blender scene to OBJ keyframes, for HoloMapPerspective.

Run:
  blender -b scene.blend -P tools/export_obj_animation.py -- \
    --output models/mycharacter/frames --start 1 --end 60 --step 3

For Blender 4.x. Select the meshes to export before saving scene.blend.
If no mesh is selected, export all visible meshes.

Notes: Blender's OBJ export writes static geometry for each evaluated frame.
Materials: only MTL diffuse Kd colors are used by this HoloMap prototype.
"""
import bpy
import sys
from pathlib import Path
import argparse

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
p = argparse.ArgumentParser()
p.add_argument("--output", required=True)
p.add_argument("--start", type=int, default=1)
p.add_argument("--end", type=int, default=60)
p.add_argument("--step", type=int, default=3)
a = p.parse_args(argv)
if a.step <= 0 or a.end < a.start:
    raise ValueError("Invalid frame range")
output = Path(a.output).resolve()
output.mkdir(parents=True, exist_ok=True)
scene = bpy.context.scene
selected = any(o.select_get() and o.type == 'MESH' for o in scene.objects)
for i, frame in enumerate(range(a.start, a.end + 1, a.step)):
    scene.frame_set(frame)
    filename = str(output / f"{i:04d}.obj")
    bpy.ops.wm.obj_export(filepath=filename, export_selected_objects=selected,
                          export_materials=True, export_normals=False, export_uv=False)
    print(f"Exported frame {i + 1}: {filename}")
print("Copy the whole model folder into server/plugins/HoloMapPerspective/models/")
