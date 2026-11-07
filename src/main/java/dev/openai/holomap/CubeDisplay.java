package dev.openai.holomap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.*;

/** Six transparent map faces sharing a single world-space model. */
public final class CubeDisplay {
    public final int id,baseX,baseY,baseZ,width,height,depth;
    public final World world;
    public final String blockMaterial;
    public final boolean inward;
    private CubeFill fillMode;
    private boolean backingOutside;
    public String model;
    private final HoloMapPlugin plugin;
    public final EnumMap<CubeGeometry.Face,List<Integer>> mapIds;
    public final EnumMap<CubeGeometry.Face,List<UUID>> frameIds;
    private final Map<UUID,EnumMap<CubeGeometry.Face,FrameCache>> cache=new HashMap<>();
    private final Map<UUID,GpuView> gpuViews=new HashMap<>();
    private static final class GpuView {
        long tick=Long.MIN_VALUE;
        GpuEngine.Scene scene;
    }
    private record OrientedFrame(int index, Mesh mesh) {}
    private final EnumMap<CubeGeometry.Face,OrientedFrame> orientedMeshes=new EnumMap<>(CubeGeometry.Face.class);

    private static final class FrameCache {
        long tick=Long.MIN_VALUE;
        byte[] pixels;
    }

    public CubeDisplay(HoloMapPlugin plugin,int id,World world,int baseX,int baseY,int baseZ,
                       int width,int height,int depth,String model,String blockMaterial,boolean inward,CubeFill fillMode,
                       boolean backingOutside,
                       Map<CubeGeometry.Face,List<Integer>> maps,
                       Map<CubeGeometry.Face,List<UUID>> frames) {
        this.plugin=plugin;this.id=id;this.world=world;
        this.baseX=baseX;this.baseY=baseY;this.baseZ=baseZ;
        this.width=width;this.height=height;this.depth=depth;
        this.model=model;this.blockMaterial=blockMaterial;this.inward=inward;this.fillMode=fillMode;
        this.backingOutside=inward&&backingOutside;
        mapIds=new EnumMap<>(CubeGeometry.Face.class);
        frameIds=new EnumMap<>(CubeGeometry.Face.class);
        for(CubeGeometry.Face face:CubeGeometry.Face.values()) {
            mapIds.put(face,new ArrayList<>(maps.getOrDefault(face,List.of())));
            frameIds.put(face,new ArrayList<>(frames.getOrDefault(face,List.of())));
        }
    }

    public CubeFill fillMode() {return fillMode;}
    public boolean backingOutside() {return backingOutside;}
    void setFill(CubeFill fillMode, boolean backingOutside) {
        this.fillMode=fillMode;
        this.backingOutside=backingOutside;
    }

    public Location faceCenter(CubeGeometry.Face f) {
        CubeGeometry.Face basis=f.screenBasis(inward);
        double offset=f.screenDepth(width,height,depth,inward);
        return new Location(world,
                baseX+width*.5+basis.nx()*offset,
                baseY+height*.5+basis.ny()*offset,
                baseZ+depth*.5+basis.nz()*offset);
    }

    public void installRenderers() {
        for(CubeGeometry.Face face:CubeGeometry.Face.values())
            for(int y=0;y<face.tileHeight(width,height,depth);y++)
                for(int x=0;x<face.tileWidth(width,height,depth);x++) {
                int id=mapIds.get(face).get(y*face.tileWidth(width,height,depth)+x);
                MapView view=Bukkit.getMap(id);
                if(view==null){plugin.getLogger().warning("Cube #"+this.id+" missing map "+id);continue;}
                for(MapRenderer old:new ArrayList<>(view.getRenderers()))view.removeRenderer(old);
                view.setTrackingPosition(false);
                view.setLocked(true);
                view.addRenderer(new CubeTileRenderer(this,face,x,y));
            }
    }

    public byte[] frameFor(Player player,CubeGeometry.Face face) {
        if(plugin.models().isGpu())return gpuFrameFor(player,face);
        FrameCache entry=cache.computeIfAbsent(player.getUniqueId(),k->new EnumMap<>(CubeGeometry.Face.class))
                .computeIfAbsent(face,k->new FrameCache());
        long tick=plugin.ticks();
        int fps=Math.max(1,Math.min(20,plugin.getConfig().getInt("render-fps",5)));
        int interval=Math.max(1,20/fps);
        if(entry.pixels!=null && tick-entry.tick<interval)return entry.pixels;
        entry.tick=tick;

        Location eye=player.getEyeLocation();
        Location center=faceCenter(face);
        double ex=eye.getX()-center.getX(),ey=eye.getY()-center.getY(),ez=eye.getZ()-center.getZ();
        CubeGeometry.Face basis=face.screenBasis(inward);
        double[] eyeLocal=basis.project(ex,ey,ez);
        double maxDistance=plugin.getConfig().getDouble("view-distance",16);
        // Do not attempt perspective projection from behind the display plane.
        if(eye.getWorld()!=world || eye.distanceSquared(center)>maxDistance*maxDistance || eyeLocal[2]<=.08) {
            entry.pixels=blank(face);return entry.pixels;
        }
        List<Mesh> frames=plugin.models().getLoaded(model);
        if(frames==null||frames.isEmpty()){entry.pixels=blank(face);return entry.pixels;}
        int animFps=Math.max(1,Math.min(20,plugin.getConfig().getInt("animation-fps",10)));
        int frameIndex=(int)((tick*animFps/20)%frames.size());
        // Cache each mesh's six oriented geometries, not one giant rendered bitmap.
        OrientedFrame oriented=orientedMeshes.get(face);
        if(oriented==null||oriented.index()!=frameIndex){
            oriented=new OrientedFrame(frameIndex,basis.orient(frames.get(frameIndex)));
            orientedMeshes.put(face,oriented);
        }
        Mesh mesh=oriented.mesh();
        int screenW=face.tileWidth(width,height,depth);
        int screenH=face.tileHeight(width,height,depth);
        // The rasterizer scales by face height internally. Choose a world-space
        // scale tied to the smallest prism dimension, not the face orientation.
        double scale=plugin.getConfig().getDouble("model-scale",.82)
                * Math.min(width,Math.min(height,depth))/(double)screenH;
        int[] rgb=PerspectiveRaster.render(mesh,screenW*128,screenH*128,
                eyeLocal[0],eyeLocal[1],eyeLocal[2],screenW,screenH,
                scale,face.screenDepth(width,height,depth,inward),basis);
        entry.pixels=new byte[rgb.length];
        for(int i=0;i<rgb.length;i++)entry.pixels[i]=plugin.palette(rgb[i]);
        return entry.pixels;
    }
    private byte[] gpuFrameFor(Player player,CubeGeometry.Face requestedFace) {
        GpuView view=gpuViews.computeIfAbsent(player.getUniqueId(),key->new GpuView());
        long tick=plugin.ticks();
        if(view.scene==null||view.tick!=tick) {
            // Capture once on the server thread; tiles cannot switch images mid-refresh.
            view.scene=plugin.gpu().renderScene(id,player.getUniqueId(),model,tick,requestsFor(player.getEyeLocation()));
            view.tick=tick;
        }
        return view.scene.pixels(requestedFace.name());
    }
    private List<GpuEngine.Request> requestsFor(Location eye) {
        double distance=plugin.getConfig().getDouble("view-distance",16);
        double margin=plugin.getConfig().getDouble("model-scale",.82);
        Models.Asset asset=plugin.models().asset(model);
        double size=asset==null?margin*Math.min(width,Math.min(height,depth)):asset.fittedSize(width,height,depth,margin);
        List<GpuEngine.Request> requests=new ArrayList<>(6);
        for(CubeGeometry.Face face:CubeGeometry.Face.values()) {
            Location center=faceCenter(face);
            CubeGeometry.Face basis=face.screenBasis(inward);
            double[] local=basis.project(eye.getX()-center.getX(),eye.getY()-center.getY(),eye.getZ()-center.getZ());
            int sw=face.tileWidth(width,height,depth),sh=face.tileHeight(width,height,depth);
            boolean visible=eye.getWorld()==world&&eye.distanceSquared(center)<=distance*distance&&local[2]>1e-4;
            requests.add(new GpuEngine.Request(face.name(),sw*128,sh*128,local[0],local[1],local[2],sw,sh,size/sh,
                    face.screenDepth(width,height,depth,inward),basis,visible));
        }
        return requests;
    }
    public void sampleEye(Player player,Location eye,boolean teleport) {
        if(eye.getWorld()!=world||!isStreaming(player.getUniqueId()))return;
        plugin.gpu().sampleScene(id,player.getUniqueId(),model,plugin.ticks(),requestsFor(eye),player.getPing(),teleport);
    }
    private byte[] blank(CubeGeometry.Face face){
        return new byte[face.tileWidth(width,height,depth)*128
                *face.tileHeight(width,height,depth)*128];
    }
    public void refresh(Player player) {
        if(player.getWorld()!=world)return;
        Location eye=player.getEyeLocation();
        double distance=plugin.getConfig().getDouble("view-distance",16);
        boolean nearby=false;
        for(CubeGeometry.Face face:CubeGeometry.Face.values())
            if(eye.distanceSquared(faceCenter(face))<=distance*distance){nearby=true;break;}
        if(!nearby)return;
        if(plugin.models().isGpu()&&plugin.nativeMaps()!=null) {
            if(!isStreaming(player.getUniqueId())) {
                List<MapPatches.Tile> tiles=new ArrayList<>();
                for(CubeGeometry.Face face:CubeGeometry.Face.values()) {
                    int w=face.tileWidth(width,height,depth),h=face.tileHeight(width,height,depth);
                    for(int y=0;y<h;y++)for(int x=0;x<w;x++)
                        tiles.add(new MapPatches.Tile(mapIds.get(face).get(y*w+x),face.name(),w,h,x,y));
                }
                GpuEngine.Delivery delivery=plugin.nativeMaps().capture(player,tiles);
                if(delivery!=null)plugin.gpu().bindDelivery(id,player.getUniqueId(),model,delivery);
            }
            if(isStreaming(player.getUniqueId())){sampleEye(player,eye,false);return;}
        }
        for(CubeGeometry.Face face:CubeGeometry.Face.values()) {
            if(eye.distanceSquared(faceCenter(face))>distance*distance)continue;
            for(int id:mapIds.get(face)) {
                MapView view=Bukkit.getMap(id);
                if(view!=null)player.sendMap(view);
            }
        }
    }
    public boolean isStreaming(UUID player){return plugin.gpu().isStreaming(id,player,model);}
    public void suppressVanillaUpdate(MapView map,Player player){plugin.nativeMaps().suppressVanillaUpdate(map,player);}
    public void uninstallRenderers() {
        for(List<Integer> ids:mapIds.values())for(int id:ids) {
            MapView map=Bukkit.getMap(id);
            if(map!=null)for(MapRenderer renderer:map.getRenderers())
                if(renderer instanceof CubeTileRenderer)map.removeRenderer(renderer);
        }
        invalidate();
    }
    public void invalidate(){cache.clear();gpuViews.clear();orientedMeshes.clear();plugin.gpu().invalidate(id);}
    public void removePlayer(UUID uuid){
        cache.remove(uuid);gpuViews.remove(uuid);plugin.gpu().forgetPlayer(uuid);
        for(List<Integer> ids:mapIds.values())for(int id:ids){
            MapView map=Bukkit.getMap(id);
            if(map!=null)for(MapRenderer renderer:map.getRenderers())
                if(renderer instanceof CubeTileRenderer tile)tile.clearPlayer(uuid);
        }
    }
    public void save(ConfigurationSection out){
        out.set("world",world.getName());out.set("x",baseX);out.set("y",baseY);out.set("z",baseZ);
        out.set("width",width);out.set("height",height);out.set("depth",depth);
        out.set("model",model);out.set("block-material",blockMaterial);
        out.set("inward",inward);
        out.set("fill",fillMode.key());
        out.set("backing-outside",backingOutside);
        for(CubeGeometry.Face face:CubeGeometry.Face.values()) {
            out.set("maps."+face.name(),mapIds.get(face));
            out.set("frames."+face.name(),frameIds.get(face).stream().map(UUID::toString).toList());
        }
    }
    public Set<UUID> allFrames(){
        Set<UUID> result=new HashSet<>();
        for(List<UUID> ids:frameIds.values())result.addAll(ids);
        return result;
    }
    public int mapCount(){return 2*(width*height+width*depth+height*depth);}
}
