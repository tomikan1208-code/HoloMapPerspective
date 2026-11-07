package dev.openai.holomap.verification;

import dev.openai.holomap.*;
import dev.openai.holomap.CubeGeometry.Face;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.block.BlockFace;
import org.bukkit.map.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Explicitly enabled, isolated Paper + real GPU regression for the 0.5.0 blank-cache bug. */
public final class GpuSeamVerifier extends JavaPlugin {
    private HoloMapPlugin plugin;
    private World world;
    private CubeDisplay small,large,inward;
    private Viewer viewer;
    private int state,ticks,assertions,caseIndex,readyAt;
    private long requestedTick,lastSceneTick=-1,benchmarkStart;
    private int benchmarkFrames;
    private final AtomicLong packetBundles=new AtomicLong();
    private final List<String> streamModels=List.of("demo","Sparxie","home");
    private int streamIndex;
    private final Map<CubeDisplay,AtomicLong> concurrentStreams=new LinkedHashMap<>();
    private final List<Pose> poses=new ArrayList<>();
    private boolean finished;
    private record Pose(CubeDisplay cube,Location eye) {}

    @Override public void onEnable() {
        if(!Boolean.getBoolean("holomap.gpu.verification")) {
            getLogger().warning("Not enabled: requires -Dholomap.gpu.verification=true on an isolated server.");return;
        }
        world=Bukkit.getWorld("holomap_gpu_test");
        if(world==null)throw new IllegalStateException("Refusing to use a non-test world");
        plugin=(HoloMapPlugin)Bukkit.getPluginManager().getPlugin("HoloMapPerspective");
        Bukkit.getScheduler().runTaskLater(this,()->{
            try {
                check(plugin.models().isGpu(),"hardware GPU mode enabled");
                plugin.getConfig().set("cube-inward",false);
                verifyVanillaSuppression();
                @SuppressWarnings("unchecked") Map<Integer,CubeDisplay> old=(Map<Integer,CubeDisplay>)field(plugin,"cubes");
                for(CubeDisplay prior:new ArrayList<>(old.values()))
                    if(prior.world==world&&prior.baseY==100&&Set.of(0,8,16,24,32).contains(prior.baseX)&&prior.baseZ==0)
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"holomap remove "+prior.id);
                small=create("0 100 0 1 2 1 demo");
                large=create("8 100 0 4 4 4 demo");
                viewer=new Viewer(small.faceCenter(Face.SOUTH).add(0,0,3));
                Bukkit.getScheduler().runTaskTimer(this,this::advance,1,1);
            }catch(Throwable ex){finish(ex);}
        },20L);
    }
    private void advance() {
        if(finished)return;
        try {
            ticks++;
            if(ticks>1200)throw new AssertionError("GPU regression timed out in state "+state+" case "+caseIndex);
            if(state==0) {
                if(colored(small.frameFor(viewer.player,Face.SOUTH))>50)state=1;
            }else if(state==1) {
                viewer.eye=small.faceCenter(Face.NORTH).add(0,0,-3);
                small.frameFor(viewer.player,Face.SOUTH); // Populate the old back-face blank cache.
                state=2;
            }else if(state==2) {
                viewer.eye=small.faceCenter(Face.SOUTH).add(0,0,3);
                byte[] first=small.frameFor(viewer.player,Face.SOUTH);
                byte[] next=small.frameFor(viewer.player,Face.SOUTH);
                check(Arrays.equals(first,next),"REGRESSION: front face returns foreground to first tile but cached blank to next tile after walking behind");
                if(colored(first)>50) {
                    buildPoses();setPose(0);state=3;
                }
            }else if(state==3) {
                Pose pose=poses.get(caseIndex);
                CubeDisplay cube=pose.cube();
                for(Face face:Face.values())cube.frameFor(viewer.player,face);
                long completed=sceneTick(cube);
                if(completed<requestedTick)return;
                int colored=0;
                Object frozen=scene(cube);
                for(Face face:Face.values()) {
                    byte[] pixels=cube.frameFor(viewer.player,face);
                    check(pixels==cube.frameFor(viewer.player,face),"all tiles keep one immutable face buffer during the tick");
                    check(scene(cube)==frozen,"all six faces use one completed scene");
                    colored+=colored(pixels);
                    verifyTiles(cube,face,pixels);
                }
                check(colored>50,"geometry visible at view "+caseIndex);
                caseIndex++;
                if(caseIndex<poses.size())setPose(caseIndex);
                else {
                    viewer.eye=new Location(world,large.baseX+large.width*.5+6,large.baseY+large.height*.5+4,large.baseZ+large.depth*.5+6);
                    requestedTick=plugin.ticks();state=4;
                }
            }else if(state==4) {
                large.frameFor(viewer.player,Face.SOUTH);
                if(sceneTick(large)>=requestedTick) {
                    benchmarkStart=System.nanoTime();readyAt=ticks;state=5;
                    lastSceneTick=sceneTick(large);
                }
            }else if(state==5) {
                large.refresh(viewer.player);
                long completed=sceneTick(large);
                if(completed!=lastSceneTick){benchmarkFrames++;lastSceneTick=completed;}
                if(ticks-readyAt>=100) {
                    double fps=benchmarkFrames*1e9/(System.nanoTime()-benchmarkStart);
                    check(fps>=15,"4x4x4 three-face GPU throughput >=15 completed scenes/s, got "+fps);
                    getConfig().set("completed_scenes_per_second",fps);
                    getConfig().set("poses",poses.size());
                    getConfig().set("renderer",plugin.gpu().status());
                    getConfig().set("map_refresh_calls",viewer.sentMaps);
                    saveConfig();state=6;
                }
            }else if(state==6) {
                String model=streamModels.get(streamIndex);
                plugin.gpu().invalidate(large.id);large.invalidate();large.model=model;plugin.models().load(model);
                state=7;
            }else if(state==7) {
                if(plugin.models().asset(large.model)==null)return;
                check(plugin.nativeMaps()!=null,"Paper 26.3 native packet adapter available");
                List<MapPatches.Tile> tiles=new ArrayList<>();
                for(Face face:Face.values())for(int y=0;y<4;y++)for(int x=0;x<4;x++)
                    tiles.add(new MapPatches.Tile(large.mapIds.get(face).get(y*4+x),face.name(),4,4,x,y));
                plugin.gpu().bindDelivery(large.id,viewer.id,large.model,plugin.nativeMaps().delivery(tiles,packet->{
                    if(!packet.getClass().getSimpleName().equals("ClientboundBundlePacket"))throw new AssertionError("Not a native bundle");
                    packetBundles.incrementAndGet();
                }));
                readyAt=ticks;state=8;
            }else if(state==8||state==9) {
                double phase=(ticks-readyAt)*.1;
                viewer.eye=new Location(world,large.baseX+large.width*.5+6+Math.sin(phase),large.baseY+large.height*.5+4,
                        large.baseZ+large.depth*.5+6);
                large.refresh(viewer.player);
                if(state==8&&ticks-readyAt>=40) {
                    packetBundles.set(0);benchmarkStart=System.nanoTime();readyAt=ticks;state=9;
                }else if(state==9&&ticks-readyAt>=100) {
                    double fps=packetBundles.get()*1e9/(System.nanoTime()-benchmarkStart);
                    check(fps>10,"native stream delivers changed map bundles for "+large.model+": "+fps);
                    if(large.model.equals("demo"))check(fps>=40,"native 60fps clock exceeds 20TPS: "+fps);
                    getConfig().set("native_bundles_per_second."+large.model,fps);
                    Models.Asset asset=plugin.models().asset(large.model);
                    getConfig().set("triangles."+large.model,asset.frames().getFirst().triangles());
                    if(large.model.equals("Sparxie"))check(asset.fittedSize(2,4,2,.82)>2.5,"tall prism fits actual model extents");
                    getLogger().info("Native map stream "+large.model+": "+fps+" bundles/s");saveConfig();
                    plugin.gpu().invalidate(large.id);
                    streamIndex++;if(streamIndex<streamModels.size())state=6;else state=10;
                }
            }else if(state==10) {
                CubeDisplay character=create("16 100 0 2 4 2 Sparxie"),demo=create("24 100 0 2 4 2 demo");
                large.invalidate();large.model="home";
                for(CubeDisplay cube:List.of(large,character,demo)) {
                    AtomicLong count=new AtomicLong();concurrentStreams.put(cube,count);
                    List<MapPatches.Tile> tiles=new ArrayList<>();
                    for(Face face:Face.values()) {
                        int w=face.tileWidth(cube.width,cube.height,cube.depth),h=face.tileHeight(cube.width,cube.height,cube.depth);
                        for(int y=0;y<h;y++)for(int x=0;x<w;x++)
                            tiles.add(new MapPatches.Tile(cube.mapIds.get(face).get(y*w+x),face.name(),w,h,x,y));
                    }
                    plugin.gpu().bindDelivery(cube.id,viewer.id,cube.model,plugin.nativeMaps().delivery(tiles,packet->count.incrementAndGet()));
                }
                readyAt=ticks;state=11;
            }else if(state==11||state==12) {
                viewer.eye=new Location(world,17+Math.sin((ticks-readyAt)*.1),106,10);
                for(CubeDisplay cube:concurrentStreams.keySet())cube.refresh(viewer.player);
                if(state==11&&ticks-readyAt>=40) {
                    concurrentStreams.values().forEach(count->count.set(0));benchmarkStart=System.nanoTime();readyAt=ticks;state=12;
                }else if(state==12&&ticks-readyAt>=100) {
                    for(var entry:concurrentStreams.entrySet()) {
                        double fps=entry.getValue().get()*1e9/(System.nanoTime()-benchmarkStart);
                        check(fps>10,"three simultaneous models stream "+entry.getKey().model+": "+fps);
                        getConfig().set("three_displays_bundles_per_second."+entry.getKey().model,fps);
                        getLogger().info("Three-display stream "+entry.getKey().model+": "+fps+" bundles/s");
                    }
                    for(CubeDisplay cube:concurrentStreams.keySet())plugin.gpu().invalidate(cube.id);
                    plugin.getConfig().set("cube-inward",true);
                    plugin.getConfig().set("cube-glow",true);
                    plugin.getConfig().set("cube-material","BARRIER");
                    inward=create("32 100 0 2 4 2 Sparxie");
                    verifyInwardFrames();
                    viewer.eye=new Location(world,33,102,2.20);
                    state=13;
                }
            }else if(state==13) {
                int visible=colored(inward.frameFor(viewer.player,Face.NORTH));
                if(visible<1000)return;
                check(colored(inward.frameFor(viewer.player,Face.SOUTH))==0,"near outward boundary stays transparent");
                getConfig().set("near_sparxie_far_face_pixels",visible);
                List<MapPatches.Tile> tiles=new ArrayList<>();
                for(Face face:Face.values()) {
                    int w=face.tileWidth(2,4,2),h=face.tileHeight(2,4,2);
                    for(int y=0;y<h;y++)for(int x=0;x<w;x++)
                        tiles.add(new MapPatches.Tile(inward.mapIds.get(face).get(y*w+x),face.name(),w,h,x,y));
                }
                plugin.gpu().bindDelivery(inward.id,viewer.id,inward.model,
                        plugin.nativeMaps().delivery(tiles,packet->packetBundles.incrementAndGet()));
                readyAt=ticks;state=14;
            }else if(state==14||state==15) {
                int step=ticks-readyAt;
                // Fast fly-by, reversals every half second, and a vertical bob.
                double angle=.32*(step/10%2==0?step%10:10-step%10)+.13;
                double x=Math.sin(angle),z=Math.cos(angle);
                double radius=1.22/Math.max(Math.abs(x),Math.abs(z));
                viewer.eye=new Location(world,33+radius*x,102+.4*Math.sin(step*.63),1+radius*z);
                inward.sampleEye(viewer.player,viewer.eye,step%29==0);
                if(state==14&&step>=30) {
                    packetBundles.set(0);benchmarkStart=System.nanoTime();readyAt=ticks;state=15;
                }else if(state==15&&step>=100) {
                    double fps=packetBundles.get()*1e9/(System.nanoTime()-benchmarkStart);
                    check(fps>=35,"near, fast 2x4x2 Sparxie sends >=35 bundles/s, got "+fps);
                    getConfig().set("inward_fast_motion_bundles_per_second",fps);
                    getLogger().info("Near inward Sparxie fast motion: "+fps+" bundles/s");
                    saveConfig();finish(null);
                }
            }
        }catch(Throwable ex){finish(ex);}
    }
    private void buildPoses() {
        for(CubeDisplay cube:List.of(small,large)) {
            for(double elevation:new double[]{0,4,-4})for(int angle=0;angle<360;angle+=30) {
                double a=Math.toRadians(angle);
                poses.add(new Pose(cube,new Location(world,cube.baseX+cube.width*.5+6*Math.sin(a),
                        cube.baseY+cube.height*.5+elevation,cube.baseZ+cube.depth*.5+6*Math.cos(a))));
            }
            for(int sign:new int[]{-1,1})poses.add(new Pose(cube,new Location(world,cube.baseX+cube.width*.5,
                    cube.baseY+cube.height*.5+sign*6,cube.baseZ+cube.depth*.5)));
        }
    }
    private void setPose(int index) {viewer.eye=poses.get(index).eye();requestedTick=plugin.ticks()+1;}
    private void verifyTiles(CubeDisplay cube,Face face,byte[] expected) {
        int width=face.tileWidth(cube.width,cube.height,cube.depth),height=face.tileHeight(cube.width,cube.height,cube.depth);
        for(int ty=0;ty<height;ty++)for(int tx=0;tx<width;tx++) {
            MapView map=Bukkit.getMap(cube.mapIds.get(face).get(ty*width+tx));
            Canvas canvas=new Canvas();
            map.getRenderers().getFirst().render(map,canvas.proxy,viewer.player);
            // A fresh canvas with an existing renderer may contain only changed pixels.
            if(canvas.writes==0)continue;
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)if(canvas.written[y*128+x])
                if(canvas.pixels[y*128+x]!=expected[((height-1-ty)*128+y)*width*128+tx*128+x])
                    throw new AssertionError("map tile changed to a different GPU frame mid-refresh: "+face);
            assertions++;
        }
    }
    private Object scene(CubeDisplay cube)throws Exception {
        Object cache=field(cube,"gpuViews");
        Object view=((Map<?,?>)cache).get(viewer.id);
        return field(view,"scene");
    }
    private long sceneTick(CubeDisplay cube)throws Exception {Object scene=scene(cube);return (long)scene.getClass().getMethod("tick").invoke(scene);}
    private static Object field(Object target,String name)throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    @SuppressWarnings("unchecked") private CubeDisplay create(String arguments)throws Exception {
        Map<Integer,CubeDisplay> cubes=(Map<Integer,CubeDisplay>)field(plugin,"cubes");
        Set<Integer> before=new HashSet<>(cubes.keySet());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"holomap create "+world.getName()+" "+arguments);
        return cubes.entrySet().stream().filter(entry->!before.contains(entry.getKey())).map(Map.Entry::getValue).findFirst().orElseThrow();
    }
    private static int colored(byte[] pixels){int count=0;for(byte pixel:pixels)if(pixel!=0)count++;return count;}
    private void verifyInwardFrames() {
        check(inward.inward&&inward.mapCount()==40,"inward 2x4x2 prism uses 40 maps");
        for(Face face:Face.values()) {
            Face basis=face.screenBasis(true);
            int width=face.tileWidth(2,4,2),height=face.tileHeight(2,4,2);
            for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                var entity=Bukkit.getEntity(inward.frameIds.get(face).get(y*width+x));
                check(entity instanceof GlowItemFrame,"inward frame uses full light");
                GlowItemFrame frame=(GlowItemFrame)entity;
                check(!frame.isVisible()&&frame.isFixed()&&frame.getFacing()==BlockFace.valueOf(basis.name()),"fixed inward orientation "+face);
                Location screen=frame.getLocation().add(basis.nx()*(CubeGeometry.MAP_OFFSET-.03125),
                        basis.ny()*(CubeGeometry.MAP_OFFSET-.03125),basis.nz()*(CubeGeometry.MAP_OFFSET-.03125));
                Location center=inward.faceCenter(face);
                double[] local=basis.project(screen.getX()-center.getX(),screen.getY()-center.getY(),screen.getZ()-center.getZ());
                check(Math.abs(local[0]-(x+.5-width*.5))<1e-6&&Math.abs(local[1]-(y+.5-height*.5))<1e-6
                        &&Math.abs(local[2])<1e-6,"frame's physical map plane and tile placement "+face);
            }
        }
    }
    private void verifyVanillaSuppression()throws Exception {
        check(plugin.nativeMaps()!=null,"native Paper adapter constructed");
        MapView map=Bukkit.createMap(world);
        Field worldMap=map.getClass().getDeclaredField("worldMap");worldMap.setAccessible(true);
        Object data=worldMap.get(map);
        Class<?> playerClass=Class.forName("net.minecraft.world.entity.player.Player");
        Object holder=data.getClass().getMethod("getHoldingPlayer",playerClass).invoke(data,new Object[]{null});
        Field dirty=holder.getClass().getDeclaredField("dirtyData");dirty.setAccessible(true);
        Method suppress=NativeMaps.class.getDeclaredMethod("suppress",MapView.class,Object.class);suppress.setAccessible(true);
        try {
            check(dirty.getBoolean(holder),"automatic map initially wants to send stale canvas pixels");
            suppress.invoke(plugin.nativeMaps(),map,null);
            check(!dirty.getBoolean(holder),"native map renderer suppresses stale automatic color patches");
            Method mark=holder.getClass().getDeclaredMethod("markColorsDirty",int.class,int.class);mark.setAccessible(true);mark.invoke(holder,1,1);
            check(dirty.getBoolean(holder),"Bukkit changes can mark map dirty again");
            suppress.invoke(plugin.nativeMaps(),map,null);
            check(!dirty.getBoolean(holder),"automatic map dirtiness is cleared again before packet creation");
        }finally {
            ((Map<?,?>)data.getClass().getField("carriedByPlayers").get(data)).remove(null);
            ((List<?>)data.getClass().getField("carriedBy").get(data)).remove(holder);
        }
    }
    private void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
    private void finish(Throwable failure) {
        if(finished)return;finished=true;
        String report=(failure==null?"PASS":"FAIL: "+failure)+"\nPlugin: "+plugin.getPluginMeta().getVersion()
                +"\nAssertions: "+assertions+"\nViews checked: "+caseIndex+"\nGPU: "+plugin.gpu().status()+"\n";
        try{Files.createDirectories(getDataFolder().toPath());Files.writeString(getDataFolder().toPath().resolve("result.txt"),report);}
        catch(Exception ex){getLogger().severe(ex.toString());}
        if(failure==null)getLogger().info(report);else getLogger().log(java.util.logging.Level.SEVERE,report,failure);
        Bukkit.getScheduler().runTaskLater(this,Bukkit::shutdown,5);
    }
    private final class Viewer implements InvocationHandler {
        final UUID id=UUID.randomUUID();
        final Player player;
        Location eye;
        int sentMaps;
        Viewer(Location eye){this.eye=eye;player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},this);}
        @Override public Object invoke(Object proxy,Method method,Object[] arguments) {
            return switch(method.getName()) {
                case "getUniqueId" -> id;
                case "getPing" -> 0;
                case "getWorld" -> world;
                case "getEyeLocation","getLocation" -> eye.clone();
                case "getName" -> "GpuSeamVerifier";
                case "sendMap" -> {
                    sentMaps++;MapView map=(MapView)arguments[0];
                    Canvas canvas=new Canvas();for(MapRenderer renderer:map.getRenderers())renderer.render(map,canvas.proxy,player);
                    yield null;
                }
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy==arguments[0];
                case "toString" -> "GpuSeamVerifier";
                default -> throw new UnsupportedOperationException(method.toString());
            };
        }
    }
    private static final class Canvas implements InvocationHandler {
        final byte[] pixels=new byte[16384];
        final boolean[] written=new boolean[16384];
        final MapCanvas proxy=(MapCanvas)Proxy.newProxyInstance(MapCanvas.class.getClassLoader(),new Class<?>[]{MapCanvas.class},this);
        int writes;
        @Override public Object invoke(Object proxy,Method method,Object[] arguments) {
            if(method.getName().equals("setPixel")) {
                int index=(int)arguments[1]*128+(int)arguments[0];pixels[index]=(byte)arguments[2];written[index]=true;writes++;return null;
            }
            throw new UnsupportedOperationException(method.toString());
        }
    }
}
