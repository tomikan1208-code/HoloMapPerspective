"""Read-only object bounds for selecting a Minecraft display derivative."""
import bpy,json
from mathutils import Vector
groups={}
graph=bpy.context.evaluated_depsgraph_get()
for item in graph.object_instances:
    obj=item.object
    if obj.type!='MESH' or not item.show_self or obj.hide_render:continue
    points=[item.matrix_world@Vector(p) for p in obj.bound_box]
    lo=[min(p[i] for p in points) for i in range(3)]
    hi=[max(p[i] for p in points) for i in range(3)]
    extent=[hi[i]-lo[i] for i in range(3)]
    if max(extent)>18:
        print('HMAP_CONTEXT',json.dumps(dict(name=obj.name,collections=[c.name for c in obj.users_collection],low=lo,high=hi),ensure_ascii=True),flush=True)
