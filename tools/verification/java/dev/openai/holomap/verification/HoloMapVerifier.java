package dev.openai.holomap.verification;

import dev.openai.holomap.*;
import dev.openai.holomap.Display;
import dev.openai.holomap.CubeGeometry.Face;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Runs only when explicitly installed, against real Paper maps, entities and persistence. */
public final class HoloMapVerifier extends JavaPlugin {
    private HoloMapPlugin plugin;
    private World world;
    private int assertions;
    private int verificationPhase;
    private final List<String> results=new ArrayList<>();

    @Override public void onEnable() {
        plugin=(HoloMapPlugin)Bukkit.getPluginManager().getPlugin("HoloMapPerspective");
        world=Bukkit.getWorlds().getFirst();
        verificationPhase=getConfig().getInt("phase",0)==0?1:2;
        Bukkit.getScheduler().runTaskLater(this,()->{
            try {
                if(getConfig().getInt("phase",0)==0)firstPhase();else secondPhase();
            }catch(Throwable failure){fail(failure);}
        },30L);
    }
    private void firstPhase() throws Exception {
        plugin.getConfig().set("cube-inward",false);
        for(CubeDisplay prior:new ArrayList<>(cubes().values()))
            if(prior.baseY==128&&((prior.baseX==128&&prior.baseZ==128)||(prior.baseX==160&&prior.baseZ==160)))command("remove "+prior.id);
        for(Display prior:new ArrayList<>(displays().values()))
            if(prior.supportX==192&&prior.supportY==128&&prior.supportZ==192)command("remove "+prior.id);
        if(world.getBlockAt(160,128,160).getType()==Material.DIAMOND_BLOCK)
            world.getBlockAt(160,128,160).setType(Material.AIR,false);
        check(plugin.isEnabled(),"production plugin enabled on Paper "+Bukkit.getVersion());
        check(plugin.palette(PerspectiveRaster.TRANSPARENT)==0,"transparent pixels map to palette 0");
        check((plugin.palette(0)&255)>=4,"opaque black does not map to transparency");
        CubeDisplay small=create("128 128 128 1 1 1 demo");
        CubeDisplay large=create("160 128 160 2 3 4 crystal");
        getConfig().set("small",small.id);getConfig().set("large",large.id);
        verifyEntities(small);verifyEntities(large);
        check(small.allFrames().size()==6,"1x1x1 has six physical frames");
        check(large.allFrames().size()==52,"2x3x4 has 52 physical frames");
        check(world.getBlockAt(128,128,128).isEmpty(),"transparent prism has no backing block");
        results.add("PASS: transparent 1x1x1 and 2x3x4 placement (58 frames), all six orientations and map IDs");

        int count=cubes().size();
        command("create "+world.getName()+" 128 128 128 1 1 1 demo");
        check(cubes().size()==count,"overlapping existing frames rejected");
        world.getBlockAt(176,128,176).setType(Material.DIAMOND_BLOCK,false);
        command("create "+world.getName()+" 176 128 176 1 1 1 demo");
        check(cubes().size()==count,"existing block rejected before placement");
        check(world.getBlockAt(176,128,176).getType()==Material.DIAMOND_BLOCK,"obstruction preserved");
        world.getBlockAt(176,128,176).setType(Material.AIR,false);
        command("create "+world.getName()+" 176 128 176 8 8 8 demo");
        command("create "+world.getName()+" 176 128 176 0 1 1 demo");
        command("create "+world.getName()+" 176 128 176 1 1 1 missing_model");
        command("create "+world.getName()+" 176 "+(world.getMaxHeight()-2)+" 176 1 3 1 demo");
        check(cubes().size()==count,"map limits, dimensions, missing models and world ceiling rejected");
        results.add("PASS: obstruction, overlap, invalid dimensions, map budget, missing model and world-height validation");

        for(Face face:Face.values()) {
            Location eye=small.faceCenter(face).add(face.nx()*3,face.ny()*3,face.nz()*3);
            Viewer a=new Viewer(eye);
            Viewer b=new Viewer(eye.clone().add(face.rx()*.6+face.ux()*.2,face.ry()*.6+face.uy()*.2,face.rz()*.6+face.uz()*.2));
            byte[] first=small.frameFor(a.player,face),second=small.frameFor(b.player,face);
            check(colored(first)>50,"geometry visible on "+face);
            check(differences(first,second)>30,"independent viewer perspective on "+face);
            check(first[0]==0,"transparent background on "+face);
            MapView map=Bukkit.getMap(small.mapIds.get(face).getFirst());
            Canvas canvas=new Canvas(map);
            MapRenderer renderer=map.getRenderers().getFirst();
            renderer.render(map,canvas.proxy,a.player);
            check(canvas.writes==16384,"first render explicitly initializes every pixel including transparency");
            check(Arrays.equals(first,canvas.pixels),"real map tile receives correct pixels on "+face);
            canvas.writes=0;renderer.render(map,canvas.proxy,a.player);
            check(canvas.writes==0,"unchanged canvas avoids pixel writes");
            small.removePlayer(a.id);small.removePlayer(b.id);
        }
        for(int angle=0;angle<360;angle+=15) {
            double radians=Math.toRadians(angle);
            Viewer viewer=new Viewer(new Location(world,128.5+4*Math.sin(radians),128.5,128.5+4*Math.cos(radians)));
            int visible=0;
            for(Face face:Face.values())visible+=colored(small.frameFor(viewer.player,face));
            check(visible>50,"360-degree runtime projection at "+angle);
            small.removePlayer(viewer.id);
        }
        Viewer behind=new Viewer(small.faceCenter(Face.SOUTH).add(0,0,-3));
        check(colored(small.frameFor(behind.player,Face.SOUTH))==0,"back faces are transparent");
        small.removePlayer(behind.id);
        Viewer far=new Viewer(small.faceCenter(Face.SOUTH).add(0,0,100));
        check(colored(small.frameFor(far.player,Face.SOUTH))==0,"distant viewers do not render");
        small.removePlayer(far.id);
        Viewer refresher=new Viewer(small.faceCenter(Face.SOUTH).add(0,0,3));
        small.refresh(refresher.player);
        check(refresher.sentMaps==6,"scheduled refresh explicitly pushes all nearby maps");
        results.add("PASS: player-specific transparent canvases, 360-degree views, top/bottom, hidden/distant faces and refresh dispatch");

        Display flat=createFlat();
        getConfig().set("flat",flat.id);
        check(flat.frameIds.size()==4,"2x2 flat wall placed");
        check(Math.abs(flat.center().getZ()-(192-CubeGeometry.MAP_OFFSET))<1e-8,"flat projection uses actual map plane");
        Viewer flatViewer=new Viewer(flat.center().add(0,0,-3));
        byte[] flatPixels=flat.frameFor(flatViewer.player);
        check(colored(flatPixels)>100,"flat wall renders model");
        MapView flatMap=Bukkit.getMap(flat.mapIds.getFirst());
        Canvas flatCanvas=new Canvas(flatMap);
        flatMap.getRenderers().getFirst().render(flatMap,flatCanvas.proxy,flatViewer.player);
        check(flatCanvas.writes==16384,"flat transparent canvas explicitly initialized");
        results.add("PASS: legacy planar command, 2x2 tile placement, transparent initialization and physical projection plane");

        command("model "+small.id+" crystal");check(small.model.equals("crystal"),"model changes");
        command("reload");check(plugin.models().getLoaded("crystal")!=null,"reload restores external OBJ cache");
        command("model "+small.id+" demo");
        Viewer animation=new Viewer(small.faceCenter(Face.SOUTH).add(0,0,3));
        byte[] before=small.frameFor(animation.player,Face.SOUTH);
        Bukkit.getScheduler().runTaskLater(this,()->{
            try {
                byte[] after=small.frameFor(animation.player,Face.SOUTH);
                check(differences(before,after)>50,"animation changes pixels across real server ticks");
                small.removePlayer(animation.id);
                results.add("PASS: external OBJ/MTL model switch, reload and timed animation");
                getConfig().set("phase",1);saveConfig();
                writeReport("PHASE_1_PASS: stop and restart for persistence/removal checks");
            }catch(Throwable failure){fail(failure);}
        },12L);
    }
    private void secondPhase() throws Exception {
        CubeDisplay small=cubes().get(getConfig().getInt("small"));
        CubeDisplay large=cubes().get(getConfig().getInt("large"));
        Display flat=displays().get(getConfig().getInt("flat"));
        check(small!=null&&large!=null&&flat!=null,"all display records restored after process restart");
        check(large.width==2&&large.height==3&&large.depth==4&&large.model.equals("crystal"),"rectangular dimensions and model persisted");
        verifyEntities(small);verifyEntities(large);
        for(int mapId:flat.mapIds)check(Bukkit.getMap(mapId).getRenderers().getFirst() instanceof TileRenderer,"flat renderers restored");
        for(int y=0;y<flat.height;y++)for(int x=0;x<flat.width;x++) {
            Vector right=flat.right();
            world.getChunkAt((flat.supportX+flat.facing.getModX()+right.getBlockX()*x)>>4,
                    (flat.supportZ+flat.facing.getModZ()+right.getBlockZ()*x)>>4).getEntities();
        }
        results.add("PASS: real server restart preserves dimensions, models, map IDs, UUIDs and contextual renderers");

        Set<UUID> frames=new HashSet<>(small.allFrames());frames.addAll(large.allFrames());frames.addAll(flat.frameIds);
        Set<Long> chunks=new HashSet<>();
        for(UUID frameId:frames) {
            Entity entity=Bukkit.getEntity(frameId);
            check(entity!=null,"persisted frame survives without backing support");
            chunks.add(((long)entity.getLocation().getChunk().getX()<<32)|(entity.getLocation().getChunk().getZ()&0xffffffffL));
        }
        world.getBlockAt(large.baseX,large.baseY,large.baseZ).setType(Material.DIAMOND_BLOCK,false);
        for(long chunk:chunks)world.unloadChunkRequest((int)(chunk>>32),(int)chunk);
        Bukkit.getScheduler().runTaskLater(this,()->{
            try {finishRemoval(small,large,flat,frames,chunks);}catch(Throwable failure){fail(failure);}
        },400L); // Let Paper's normal delayed-unload and recent-access tickets expire.
    }
    private void finishRemoval(CubeDisplay small,CubeDisplay large,Display flat,Set<UUID> frames,Set<Long> chunks) throws Exception {
        int unloaded=0;
        for(long chunk:chunks)if(!world.isChunkLoaded((int)(chunk>>32),(int)chunk))unloaded++;
        check(unloaded>0,"test frames unloaded before removal");
        command("remove "+small.id);command("remove "+large.id);command("remove "+flat.id);
        for(long chunk:chunks)world.getChunkAt((int)(chunk>>32),(int)chunk).getEntities();
        for(UUID id:frames)check(Bukkit.getEntity(id)==null,"removed persisted frame must not reappear after chunk reload: "+id);
        check(world.getBlockAt(large.baseX,large.baseY,large.baseZ).getType()==Material.DIAMOND_BLOCK,"AIR prism removal preserves later player blocks");
        world.getBlockAt(large.baseX,large.baseY,large.baseZ).setType(Material.AIR,false);
        for(int x=0;x<2;x++)for(int y=0;y<2;y++)world.getBlockAt(192-x,128+y,192).setType(Material.AIR,false);
        check(cubes().isEmpty()&&displays().isEmpty(),"all test display records removed");
        for(int id:small.mapIds.get(Face.SOUTH))check(Bukkit.getMap(id).getRenderers().isEmpty(),"removed map releases plugin renderer");
        results.add("PASS: removal after chunk unload, no orphan frames, modified blocks preserved and renderers released");

        plugin.getConfig().set("cube-material","BLACK_CONCRETE");
        CubeDisplay opaque=create("224 128 224 1 1 1 demo");
        check(world.getBlockAt(224,128,224).getType()==Material.BLACK_CONCRETE,"optional opaque backing still supported");
        world.getBlockAt(224,128,224).setType(Material.GOLD_BLOCK,false);
        command("remove "+opaque.id);
        check(world.getBlockAt(224,128,224).getType()==Material.GOLD_BLOCK,"opaque removal preserves changed block");
        world.getBlockAt(224,128,224).setType(Material.AIR,false);
        plugin.getConfig().set("cube-material","AIR");
        results.add("PASS: optional solid backing and modified-block protection");
        verifyFillChanges();
        getConfig().set("phase",2);saveConfig();
        writeReport("ALL_PASS");
    }
    private void verifyFillChanges() throws Exception {
        plugin.getConfig().set("cube-material","BARRIER");
        plugin.getConfig().set("cube-fill","shell");
        plugin.getConfig().set("cube-inward",true);
        CubeDisplay cube=create("232 128 232 2 4 2 demo");
        Set<UUID> frames=cube.allFrames();
        var corner=world.getBlockAt(231,127,231);
        var center=world.getBlockAt(232,129,232);
        check(cube.inward&&cube.backingOutside()&&cube.fillMode()==CubeFill.SHELL
                &&corner.getType()==Material.BARRIER&&center.isEmpty()&&frames.size()==40,
                "2x4x2 inward map box fits in an outer 4x6x4 shell");
        for(UUID id:frames) {
            ItemFrame frame=(ItemFrame)Bukkit.getEntity(id);
            var anchor=frame.getLocation().getBlock();
            check(frame instanceof GlowItemFrame&&!frame.isVisible()&&frame.isFixed()
                    &&frame.getItem().getType()==Material.FILLED_MAP,"visible map in an invisible locked glow frame");
            check(anchor.isEmpty()&&anchor.getRelative(frame.getFacing().getOppositeFace()).getType()==Material.BARRIER,
                    "frame occupies air with a barrier directly behind it");
        }
        ItemFrame sample=(ItemFrame)Bukkit.getEntity(frames.iterator().next());
        Player player=new Viewer(cube.faceCenter(Face.NORTH)).player;
        DamageSource attack=DamageSource.builder(DamageType.PLAYER_ATTACK).build();
        HangingBreakByEntityEvent lockedBreak=new HangingBreakByEntityEvent(sample,player,attack);
        Bukkit.getPluginManager().callEvent(lockedBreak);
        check(lockedBreak.isCancelled(),"locked frame is protected from player breaking");
        command("lock "+cube.id+" off");
        for(UUID id:frames) {
            ItemFrame frame=(ItemFrame)Bukkit.getEntity(id);
            check(!frame.isFixed()&&!frame.isInvulnerable(),"unlock applies to every frame");
            Object handle=frame.getClass().getMethod("getHandle").invoke(frame);
            check((boolean)handle.getClass().getMethod("survives").invoke(handle),"unlocked frame has valid vanilla backing");
        }
        HangingBreakByEntityEvent unlockedBreak=new HangingBreakByEntityEvent(sample,player,attack);
        Bukkit.getPluginManager().callEvent(unlockedBreak);
        check(!unlockedBreak.isCancelled(),"unlocked frame allows player breaking");
        center.setType(Material.BARRIER,false);
        command("fill "+cube.id+" solid");
        check(cube.fillMode()==CubeFill.SHELL,"cannot take ownership of a barrier added inside the shell");
        corner.setType(Material.DIAMOND_BLOCK,false);
        command("fill "+cube.id+" empty");
        check(cube.fillMode()==CubeFill.EMPTY&&center.getType()==Material.BARRIER&&corner.getType()==Material.DIAMOND_BLOCK,
                "empty mode preserves both a player barrier and a changed boundary block");
        check(world.getBlockAt(232,128,231).isEmpty(),"empty clears a generated shell block");
        HangingBreakEvent physicsBreak=new HangingBreakEvent(sample,HangingBreakEvent.RemoveCause.PHYSICS);
        Bukkit.getPluginManager().callEvent(physicsBreak);
        check(physicsBreak.isCancelled(),"clearing backing retains an unlocked map frame");
        corner.setType(Material.AIR,false);
        center.setType(Material.AIR,false);
        plugin.getConfig().set("cube-fill","solid");
        command("fill "+cube.id);
        check(cube.fillMode()==CubeFill.SOLID&&center.getType()==Material.BARRIER,"command without a mode applies the config");
        command("fill "+cube.id+" shell");
        check(center.isEmpty()&&frames.equals(cube.allFrames()),"hollowing a solid cube retains all frame UUIDs");
        command("lock "+cube.id+" on");
        for(UUID id:frames) {
            ItemFrame frame=(ItemFrame)Bukkit.getEntity(id);
            check(frame.isFixed()&&frame.isInvulnerable(),"relock protects every surviving frame");
        }
        center.setType(Material.BARRIER,false);
        command("remove "+cube.id);
        check(center.getType()==Material.BARRIER&&corner.isEmpty(),"removal clears only the cube's shell");
        center.setType(Material.AIR,false);
        plugin.getConfig().set("cube-material","AIR");
        results.add("PASS: inward backing, invisible filled maps, lock toggles, shell changes, and block ownership");
    }
    private Display createFlat() throws Exception {
        for(int x=0;x<2;x++)for(int y=0;y<2;y++)world.getBlockAt(192-x,128+y,192).setType(Material.STONE,false);
        Viewer viewer=new Viewer(new Location(world,192,129,189));
        viewer.hit=new RayTraceResult(new Vector(192.5,128.5,192),world.getBlockAt(192,128,192),BlockFace.NORTH);
        plugin.onCommand(viewer.player,plugin.getCommand("holomap"),"holomap",new String[]{"place","2","2","demo"});
        check(!displays().isEmpty(),"flat placement command succeeded");
        return displays().values().iterator().next();
    }
    private CubeDisplay create(String arguments) throws Exception {
        Set<Integer> before=new HashSet<>(cubes().keySet());
        command("create "+world.getName()+" "+arguments);
        return cubes().entrySet().stream().filter(e->!before.contains(e.getKey())).map(Map.Entry::getValue).findFirst()
                .orElseThrow(()->new AssertionError("Prism creation failed: "+arguments));
    }
    private void verifyEntities(CubeDisplay cube) {
        for(Face face:Face.values()) {
            int index=0;
            for(UUID id:cube.frameIds.get(face)) {
                int fw=face.tileWidth(cube.width,cube.height,cube.depth);
                int[] offset=face.support(cube.width,cube.height,cube.depth,index%fw,index/fw);
                world.getChunkAt((cube.baseX+offset[0]+face.nx())>>4,(cube.baseZ+offset[2]+face.nz())>>4).getEntities();
                Entity entity=Bukkit.getEntity(id);
                check(entity instanceof ItemFrame,"real ItemFrame exists on "+face);
                ItemFrame frame=(ItemFrame)entity;
                check(frame.getFacing()==BlockFace.valueOf(face.name()),"frame physical facing is "+face);
                check(!frame.isVisible()&&frame.isFixed(),"frame invisible and fixed");
                MapView map=((MapMeta)frame.getItem().getItemMeta()).getMapView();
                check(map!=null&&map.getId()==cube.mapIds.get(face).get(index++),"frame item references its map");
                check(map.isLocked()&&!map.isTrackingPosition(),"map terrain tracking disabled");
                check(map.getRenderers().size()==1&&map.getRenderers().getFirst() instanceof CubeTileRenderer,"real contextual map renderer installed");
            }
        }
    }
    @SuppressWarnings("unchecked") private Map<Integer,CubeDisplay> cubes() throws Exception {return (Map<Integer,CubeDisplay>)field("cubes");}
    @SuppressWarnings("unchecked") private Map<Integer,Display> displays() throws Exception {return (Map<Integer,Display>)field("displays");}
    private Object field(String name) throws Exception {Field field=HoloMapPlugin.class.getDeclaredField(name);field.setAccessible(true);return field.get(plugin);}
    private void command(String text){Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"holomap "+text);}
    private void check(boolean condition,String description){assertions++;if(!condition)throw new AssertionError(description);}
    private static int colored(byte[] pixels){int n=0;for(byte pixel:pixels)if(pixel!=0)n++;return n;}
    private static int differences(byte[] a,byte[] b){int n=0;for(int i=0;i<a.length;i++)if(a[i]!=b[i])n++;return n;}
    private void writeReport(String status) throws IOException {
        Files.createDirectories(getDataFolder().toPath());
        String report=status+"\n"+Bukkit.getVersion()+"\nAssertions: "+assertions+"\n"+String.join("\n",results)+"\n";
        Files.writeString(getDataFolder().toPath().resolve("phase-"+verificationPhase+".txt"),report);
        getLogger().info(report);
    }
    private void fail(Throwable failure) {
        getLogger().log(java.util.logging.Level.SEVERE,"VERIFICATION_FAILED",failure);
        try{writeReport("FAIL: "+failure);}catch(IOException ignored){}
    }
    private final class Viewer implements InvocationHandler {
        final UUID id=UUID.randomUUID();
        final Location eye;
        final Player player;
        RayTraceResult hit;
        int sentMaps;
        Viewer(Location eye){this.eye=eye;player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},this);}
        @Override public Object invoke(Object proxy,Method method,Object[] arguments) {
            return switch(method.getName()) {
                case "getUniqueId" -> id;
                case "getEyeLocation","getLocation" -> eye.clone();
                case "getWorld" -> eye.getWorld();
                case "getName" -> "HoloMapVerifier";
                case "hasPermission","isOnline","isValid","isOp" -> true;
                case "rayTraceBlocks" -> hit;
                case "sendMessage" -> null;
                case "sendMap" -> {sentMaps++;yield null;}
                case "toString" -> "VerifierPlayer("+id+")";
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy==arguments[0];
                default -> throw new UnsupportedOperationException("Verifier does not emulate "+method);
            };
        }
    }
    private static final class Canvas implements InvocationHandler {
        final byte[] pixels=new byte[16384];
        final MapCanvas proxy;
        final MapView view;
        int writes;
        Canvas(MapView view){this.view=view;Arrays.fill(pixels,(byte)-1);proxy=(MapCanvas)Proxy.newProxyInstance(MapCanvas.class.getClassLoader(),new Class<?>[]{MapCanvas.class},this);}
        @Override public Object invoke(Object proxy,Method method,Object[] arguments) {
            return switch(method.getName()) {
                case "setPixel" -> {pixels[(int)arguments[1]*128+(int)arguments[0]]=(byte)arguments[2];writes++;yield null;}
                case "getMapView" -> view;
                default -> throw new UnsupportedOperationException("Verifier does not emulate "+method);
            };
        }
    }
}
