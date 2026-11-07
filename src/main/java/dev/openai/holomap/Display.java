package dev.openai.holomap;

import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Vector;
import java.util.*;

/** A tile wall whose center is a 3D-projection plane shared by all maps. */
public final class Display {
    public final int id,width,height;
    public final World world;
    public final BlockFace facing;
    public final int supportX,supportY,supportZ;
    public String model;
    public final List<Integer> mapIds;
    public final List<UUID> frameIds;
    private final HoloMapPlugin plugin;
    private final Map<UUID,FrameCache> viewerCache=new HashMap<>();
    private static final class FrameCache {
        long tick=-100000;
        byte[] pixel;
    }
    public Display(HoloMapPlugin plugin,int id,World world,BlockFace facing,
                   int sx,int sy,int sz,int width,int height,String model,
                   List<Integer> mapIds,List<UUID> frameIds) {
        this.plugin=plugin;this.id=id;this.world=world;this.facing=facing;
        this.supportX=sx;this.supportY=sy;this.supportZ=sz;
        this.width=width;this.height=height;this.model=model;
        this.mapIds=new ArrayList<>(mapIds);this.frameIds=new ArrayList<>(frameIds);
    }
    public Vector normal() {return facing.getDirection().clone();}
    public Vector right() {return new Vector(0,1,0).crossProduct(normal()).normalize();}
    public Location center() {
        Vector r=right();
        double offset=.5+CubeGeometry.MAP_OFFSET;
        return new Location(world,supportX+.5+facing.getModX()*offset+r.getX()*(width-1)/2.0,
                supportY+.5+(height-1)/2.0,
                supportZ+.5+facing.getModZ()*offset+r.getZ()*(width-1)/2.0);
    }
    public void installRenderers() {
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int i=y*width+x;
            MapView map=Bukkit.getMap(mapIds.get(i));
            if(map==null) {plugin.getLogger().warning("Missing map ID "+mapIds.get(i)+" in display "+id);continue;}
            for(MapRenderer old:new ArrayList<>(map.getRenderers()))map.removeRenderer(old);
            map.setTrackingPosition(false);
            map.setLocked(true);
            map.addRenderer(new TileRenderer(this,x,y));
        }
    }
    public byte[] frameFor(Player viewer) {
        UUID uuid=viewer.getUniqueId();
        FrameCache c=viewerCache.computeIfAbsent(uuid,k->new FrameCache());
        long tick=plugin.ticks();
        int interval=Math.max(1,20/Math.max(1,plugin.getConfig().getInt("render-fps",5)));
        if(c.pixel!=null && tick-c.tick<interval)return c.pixel;
        c.tick=tick;
        Location eye=viewer.getEyeLocation(),center=center();
        if(eye.getWorld()!=world || eye.distanceSquared(center)>Math.pow(plugin.getConfig().getDouble("view-distance",16),2)) {
            c.pixel=background(); return c.pixel;
        }
        Vector d=eye.toVector().subtract(center.toVector());
        Vector n=normal(),r=right();
        double eyeX=d.dot(r),eyeY=d.getY(),eyeZ=d.dot(n);
        if(plugin.models().isGpu()) {
            c.pixel=plugin.gpu().render(id,"flat",uuid,model,tick,width*128,height*128,
                    eyeX,eyeY,eyeZ,width,height,
                    plugin.getConfig().getDouble("model-scale",.82),
                    plugin.getConfig().getDouble("model-depth",.75),null);
            return c.pixel;
        }
        List<Mesh> frames=plugin.models().getLoaded(model);
        if(frames==null) {c.pixel=background();return c.pixel;}
        int animFps=Math.max(1,Math.min(20,plugin.getConfig().getInt("animation-fps",10)));
        Mesh mesh=frames.get((int)((tick*animFps/20)%frames.size()));
        int[] rgb=PerspectiveRaster.render(mesh,width*128,height*128,eyeX,eyeY,eyeZ,
                width,height,plugin.getConfig().getDouble("model-scale",.82),
                plugin.getConfig().getDouble("model-depth",.75));
        c.pixel=new byte[rgb.length];
        for(int i=0;i<rgb.length;i++)c.pixel[i]=plugin.palette(rgb[i]);
        return c.pixel;
    }
    private byte[] background(){
        return new byte[width*128*height*128];
    }
    public void refresh(Player player) {
        if(player.getWorld()!=world || player.getEyeLocation().distanceSquared(center())>
                Math.pow(plugin.getConfig().getDouble("view-distance",16),2))return;
        if(plugin.models().isGpu()&&plugin.nativeMaps()!=null) {
            if(!isStreaming(player.getUniqueId())) {
                List<MapPatches.Tile> tiles=new ArrayList<>();
                for(int y=0;y<height;y++)for(int x=0;x<width;x++)
                    tiles.add(new MapPatches.Tile(mapIds.get(y*width+x),"flat",width,height,x,y));
                GpuEngine.Delivery delivery=plugin.nativeMaps().capture(player,tiles);
                if(delivery!=null)plugin.gpu().bindDelivery(id,player.getUniqueId(),model,delivery);
            }
            if(isStreaming(player.getUniqueId())){frameFor(player);return;}
        }
        for(int id:mapIds) {
            MapView map=Bukkit.getMap(id);
            if(map!=null)player.sendMap(map);
        }
    }
    public boolean isStreaming(UUID player){return plugin.gpu().isStreaming(id,player,model);}
    public void suppressVanillaUpdate(MapView map,Player player){plugin.nativeMaps().suppressVanillaUpdate(map,player);}
    public void uninstallRenderers() {
        for(int id:mapIds) {
            MapView map=Bukkit.getMap(id);
            if(map!=null)for(MapRenderer renderer:map.getRenderers())
                if(renderer instanceof TileRenderer)map.removeRenderer(renderer);
        }
        invalidate();
    }
    public void invalidate() {viewerCache.clear();plugin.gpu().invalidate(id);}
    public void removePlayer(UUID player){
        viewerCache.remove(player);plugin.gpu().forgetPlayer(player);
        for(int id:mapIds){
            MapView view=Bukkit.getMap(id);
            if(view!=null)for(MapRenderer renderer:view.getRenderers())
                if(renderer instanceof TileRenderer tile)tile.clearPlayer(player);
        }
    }
    public void save(ConfigurationSection s) {
        s.set("world",world.getName()); s.set("facing",facing.name());
        s.set("x",supportX);s.set("y",supportY);s.set("z",supportZ);
        s.set("width",width);s.set("height",height);s.set("model",model);
        s.set("maps",new ArrayList<>(mapIds));
        s.set("frames",frameIds.stream().map(UUID::toString).toList());
    }
    public static ItemStack filled(MapView map) {
        ItemStack item=new ItemStack(Material.FILLED_MAP);
        MapMeta meta=(MapMeta)item.getItemMeta();meta.setMapView(map);
        item.setItemMeta(meta);return item;
    }
}
