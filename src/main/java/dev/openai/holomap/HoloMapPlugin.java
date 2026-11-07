package dev.openai.holomap;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.map.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.io.*;
import java.util.*;

/** Paper 26.3 server-side map projection. Native map streaming with a Bukkit fallback. */
public final class HoloMapPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private final Map<Integer,Display> displays=new TreeMap<>();
    private final Map<Integer,CubeDisplay> cubes=new TreeMap<>();
    private final Map<Integer,Byte> paletteCache=new HashMap<>();
    private Models models;
    private GpuEngine gpu;
    private NativeMaps nativeMaps;
    private long tick;
    private int nextId=1;
    private File displayFile;

    @Override public void onEnable() {
        saveDefaultConfig();
        migrateGpuDefaults();
        displayFile=new File(getDataFolder(),"displays.yml");
        new File(getDataFolder(),"models").mkdirs();
        File gpuHelper=new File(getDataFolder(),"gpu/renderer.py");
        saveResource("gpu/renderer.py",true);
        try{nativeMaps=new NativeMaps();}
        catch(ReflectiveOperationException ex){getLogger().warning("Native map streaming unavailable; Bukkit fallback is limited to 20 fps: "+ex);}
        gpu=new GpuEngine(this);
        models=new Models(this);
        if(models.isGpu())gpu.start();
        Objects.requireNonNull(getCommand("holomap")).setExecutor(this);
        Objects.requireNonNull(getCommand("holomap")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this,this);
        loadDisplays();
        getServer().getScheduler().runTaskTimer(this,()->{
            tick++;
            int fps=Math.max(1,Math.min(20,getConfig().getInt("render-fps",20)));
            if(tick % Math.max(1,20/fps)!=0)return;
            for(Player player:Bukkit.getOnlinePlayers()) {
                for(Display display:displays.values())display.refresh(player);
                for(CubeDisplay cube:cubes.values())cube.refresh(player);
            }
        },1L,1L);
        getLogger().info("HoloMapPerspective enabled with "+displays.size()+" planar display(s), "+cubes.size()+" cube(s)");
    }
    /** Move the uploaded v0.4 config's old default values to the new GPU/20fps defaults once.
     * Explicit non-default numbers remain unchanged. The marker prevents subsequent overrides. */
    private void migrateGpuDefaults() {
        if(getConfig().contains("gpu-configuration-version",true))return;
        if(getConfig().getInt("render-fps",5)==5)getConfig().set("render-fps",20);
        if(getConfig().getInt("animation-fps",10)==10)getConfig().set("animation-fps",20);
        if(getConfig().getInt("max-triangles",6000)==6000)getConfig().set("max-triangles",3_000_000);
        getConfig().set("renderer-backend","gpu");
        getConfig().set("gpu-python-command",getConfig().getString("gpu-python-command","python"));
        getConfig().set("max-obj-bytes",getConfig().getLong("max-obj-bytes",1_500_000_000L));
        getConfig().set("gpu-configuration-version",1);
        saveConfig();
    }

    @Override public void onDisable() {
        saveDisplays();
        models.close();gpu.close();
        for(Display display:displays.values())display.uninstallRenderers();
        for(CubeDisplay cube:cubes.values())cube.uninstallRenderers();
        displays.clear();cubes.clear();
    }
    public Models models(){return models;}
    public GpuEngine gpu(){return gpu;}
    public NativeMaps nativeMaps(){return nativeMaps;}
    public long ticks(){return tick;}
    @SuppressWarnings("deprecation") public byte palette(int rgb) {
        if(rgb==PerspectiveRaster.TRANSPARENT)return 0;
        return paletteCache.computeIfAbsent(rgb,c->MapPalette.matchColor((c>>16)&255,(c>>8)&255,c&255));
    }
    private void loadDisplays() {
        if(!displayFile.isFile()) return;
        YamlConfiguration data=YamlConfiguration.loadConfiguration(displayFile);
        nextId=Math.max(1,data.getInt("next-id",1));
        ConfigurationSection section=data.getConfigurationSection("displays");
        if(section!=null) for(String key:section.getKeys(false)) try {
            int id=Integer.parseInt(key);
            ConfigurationSection c=section.getConfigurationSection(key);
            if(c==null)continue;
            World world=Bukkit.getWorld(Objects.requireNonNull(c.getString("world")));
            if(world==null){getLogger().warning("World not loaded: "+c.getString("world"));continue;}
            BlockFace face=BlockFace.valueOf(Objects.requireNonNull(c.getString("facing")));
            int w=c.getInt("width"),h=c.getInt("height");
            if(w<1||h<1||w>3||h>3||!face.isCartesian()||face.getModY()!=0) continue;
            List<Integer> mapIds=c.getIntegerList("maps");
            List<UUID> frames=new ArrayList<>();for(String s:c.getStringList("frames"))frames.add(UUID.fromString(s));
            if(mapIds.size()!=w*h || frames.size()!=w*h){getLogger().warning("Display "+id+" tile count mismatch");continue;}
            String model=c.getString("model","demo");
            models.load(model);
            Display d=new Display(this,id,world,face,c.getInt("x"),c.getInt("y"),c.getInt("z"),w,h,model,mapIds,frames);
            displays.put(id,d);d.installRenderers();nextId=Math.max(nextId,id+1);
        } catch(Exception ex){getLogger().warning("Could not load display "+key+": "+ex.getMessage());}
        ConfigurationSection cubesSection=data.getConfigurationSection("cubes");
        if(cubesSection!=null)for(String key:cubesSection.getKeys(false))try {
            int id=Integer.parseInt(key);
            ConfigurationSection c=cubesSection.getConfigurationSection(key);
            if(c==null)continue;
            World world=Bukkit.getWorld(Objects.requireNonNull(c.getString("world")));
            if(world==null){getLogger().warning("Cube world not loaded: "+c.getString("world"));continue;}
            // Backward-compatible: v0.2.0 saved only "size".
            int oldSize=c.getInt("size",1);
            int width=c.getInt("width",oldSize),height=c.getInt("height",oldSize),depth=c.getInt("depth",oldSize);
            if(width<1||height<1||depth<1||width>16||height>16||depth>16)continue;
            Map<CubeGeometry.Face,List<Integer>> maps=new EnumMap<>(CubeGeometry.Face.class);
            Map<CubeGeometry.Face,List<UUID>> frames=new EnumMap<>(CubeGeometry.Face.class);
            for(CubeGeometry.Face face:CubeGeometry.Face.values()) {
                List<Integer> ids=c.getIntegerList("maps."+face.name());
                List<UUID> uuids=new ArrayList<>();
                for(String value:c.getStringList("frames."+face.name()))uuids.add(UUID.fromString(value));
                int tiles=face.tileWidth(width,height,depth)*face.tileHeight(width,height,depth);
                if(ids.size()!=tiles||uuids.size()!=tiles)
                    throw new IllegalStateException("Wrong tile count on "+face);
                maps.put(face,ids);frames.put(face,uuids);
            }
            String model=c.getString("model","demo");
            models.load(model);
            CubeDisplay cube=new CubeDisplay(this,id,world,c.getInt("x"),c.getInt("y"),c.getInt("z"),
                    width,height,depth,model,c.getString("block-material","BLACK_CONCRETE"),c.getBoolean("inward"),maps,frames);
            cubes.put(id,cube);cube.installRenderers();nextId=Math.max(nextId,id+1);
        }catch(Exception ex){getLogger().warning("Could not load cube "+key+": "+ex.getMessage());}
    }
    private void saveDisplays() {
        if(displayFile==null)return;
        YamlConfiguration data=new YamlConfiguration();
        data.set("next-id",nextId);
        for(Display d:displays.values())d.save(data.createSection("displays."+d.id));
        for(CubeDisplay c:cubes.values())c.save(data.createSection("cubes."+c.id));
        try {data.save(displayFile);}catch(IOException ex){getLogger().severe("Saving displays failed: "+ex.getMessage());}
    }
    @EventHandler public void quit(PlayerQuitEvent e){
        UUID id=e.getPlayer().getUniqueId();
        for(Display d:displays.values())d.removePlayer(id);
        for(CubeDisplay c:cubes.values())c.removePlayer(id);
        gpu.forgetPlayer(id);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void move(PlayerMoveEvent event) {
        if(event instanceof PlayerTeleportEvent||!event.hasChangedPosition())return;
        sampleMovement(event,false);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void teleport(PlayerTeleportEvent event) {
        if(event.getTo().getWorld()!=event.getFrom().getWorld()) {
            gpu.forgetPlayer(event.getPlayer().getUniqueId());
            return;
        }
        sampleMovement(event,true);
    }
    private void sampleMovement(PlayerMoveEvent event,boolean teleport) {
        if(nativeMaps==null||!models.isGpu())return;
        Player player=event.getPlayer();
        Location eye=event.getTo().clone().add(0,player.getEyeHeight(),0);
        for(Display display:displays.values())display.sampleEye(player,eye,teleport);
        for(CubeDisplay cube:cubes.values())cube.sampleEye(player,eye,teleport);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!sender.hasPermission("holomap.admin")){sender.sendMessage("§cNo permission.");return true;}
        if(args.length==0||args[0].equalsIgnoreCase("help")){help(sender);return true;}
        try {switch(args[0].toLowerCase(Locale.ROOT)) {
            case "place" -> place(sender,args);
            case "cube" -> placeCube(sender,args);
            case "create" -> createAt(sender,args);
            case "remove" -> remove(sender,args);
            case "model" -> changeModel(sender,args);
            case "models" -> sender.sendMessage("§bModels: §f"+String.join(", ",models.installed()));
            case "status" -> {
                sender.sendMessage("§bRenderer: §f"+(models.isGpu()?"GPU/OpenGL":"CPU fallback"));
                sender.sendMessage("§bGPU: §f"+gpu.status());
                for(String name:models.installed()) sender.sendMessage("§7"+name+": "+models.status(name));
            }
            case "list" -> {
                sender.sendMessage("§bDisplays: "+(displays.size()+cubes.size()));
                for(Display d:displays.values())sender.sendMessage("§7#"+d.id+" §f[flat] "+d.world.getName()+" " +d.supportX+","+d.supportY+","+d.supportZ+" "+d.width+"x"+d.height+" "+d.model);
                for(CubeDisplay c:cubes.values())sender.sendMessage("§7#"+c.id+" §f[cube] "+c.world.getName()+" " +c.baseX+","+c.baseY+","+c.baseZ+" "+c.width+"x"+c.height+"x"+c.depth+" "+c.model);
            }
            case "reload" -> {
                reloadConfig();models.clear();gpu.reset();paletteCache.clear();
                for(Display d:displays.values()){models.load(d.model);d.invalidate();d.installRenderers();}
                for(CubeDisplay c:cubes.values()){models.load(c.model);c.invalidate();c.installRenderers();}
                sender.sendMessage("§aSettings and model cache reloaded.");
            }
            default -> help(sender);
        }} catch(Exception ex){sender.sendMessage("§c"+ex.getMessage());getLogger().warning("Command failed: "+ex);}
        return true;
    }
    private void help(CommandSender sender) {
        sender.sendMessage("§b--- HoloMapPerspective ---");
        sender.sendMessage("§f/holomap place [width] [height] [model] §7look at a wall block (bottom-left)");
        sender.sendMessage("§f/holomap cube <X> <Y> <Z> [model] §7rectangular prism; look at ground block TOP");
        sender.sendMessage("§f/holomap create <world> <x> <y> <z> <X> <Y> <Z> [model] §7place at coordinates (also console)");
        sender.sendMessage("§f/holomap list §7find display IDs");
        sender.sendMessage("§f/holomap model <id> <name> §7switch model or animation");
        sender.sendMessage("§f/holomap remove <id> §7remove only this display's frames");
        sender.sendMessage("§f/holomap models §7list folders, demo model included");
        sender.sendMessage("§f/holomap reload §7reload OBJ files and config");
        sender.sendMessage("§f/holomap status §7check GPU and OBJ preparation status");
    }
    private int number(String text,int min,int max) {
        int value=Integer.parseInt(text);
        if(value<min||value>max)throw new IllegalArgumentException("Value must be "+min+".."+max);
        return value;
    }
    private void place(CommandSender sender,String[] args) throws Exception {
        if(!(sender instanceof Player p)){sender.sendMessage("§cRun in game, looking at a wall block.");return;}
        if(args.length>4)throw new IllegalArgumentException("Usage: /holomap place [width] [height] [model]");
        int max=Math.min(3,Math.max(1,getConfig().getInt("max-display-size",3)));
        int w=args.length>1?number(args[1],1,max):1;
        int h=args.length>2?number(args[2],1,max):1;
        String name=args.length>3?args[3]:"demo";
        models.load(name); // validate fully before any map or entity is created
        RayTraceResult hit=p.rayTraceBlocks(8);
        if(hit==null||hit.getHitBlock()==null||hit.getHitBlockFace()==null)throw new IllegalArgumentException("Look at a solid, vertical wall block within 8 blocks.");
        BlockFace face=hit.getHitBlockFace();
        if(face!=BlockFace.NORTH&&face!=BlockFace.SOUTH&&face!=BlockFace.EAST&&face!=BlockFace.WEST)
            throw new IllegalArgumentException("Only vertical wall faces (N/E/S/W) are supported.");
        Block start=hit.getHitBlock();
        Vector right=new Vector(0,1,0).crossProduct(face.getDirection()).normalize();
        int rx=right.getBlockX(),rz=right.getBlockZ();
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            Block support=start.getRelative(rx*x,y,rz*x);
            Block front=support.getRelative(face);
            if(!support.getType().isSolid() || !front.isEmpty())
                throw new IllegalArgumentException("All "+w+"x"+h+" positions must have a solid wall and empty space in front. Obstructed at "+front.getX()+","+front.getY()+","+front.getZ());
            requireNoFrame(front);
        }
        int id=nextId++;
        List<Integer> mapIds=new ArrayList<>();List<UUID> frameIds=new ArrayList<>();
        try {
            for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
                Block front=start.getRelative(rx*x,y,rz*x).getRelative(face);
                MapView map=Bukkit.createMap(p.getWorld());mapIds.add(map.getId());
                ItemFrame frame=spawnFrame(front,face,map);
                frameIds.add(frame.getUniqueId());
            }
            Display d=new Display(this,id,p.getWorld(),face,start.getX(),start.getY(),start.getZ(),w,h,name,mapIds,frameIds);
            displays.put(id,d);d.installRenderers();saveDisplays();
            sender.sendMessage("§aCreated HoloMap #"+id+" ("+w+"x"+h+", "+name+"). Move side-to-side to see parallax.");
        } catch(Exception ex) {
            Display display=displays.remove(id);
            if(display!=null)display.uninstallRenderers();
            for(UUID uuid:frameIds) {Entity e=Bukkit.getEntity(uuid);if(e!=null)e.remove();}
            throw ex;
        }
    }
    private void placeCube(CommandSender sender,String[] args) throws Exception {
        if(!(sender instanceof Player p))throw new IllegalArgumentException("Use this in game, while looking at the TOP of a ground block.");
        if(args.length<4||args.length>5)
            throw new IllegalArgumentException("Usage: /holomap cube <X> <Y> <Z> [model], e.g. /holomap cube 2 3 4 demo");
        int max=Math.min(16,Math.max(1,getConfig().getInt("max-prism-axis",8)));
        int width=number(args[1],1,max),height=number(args[2],1,max),depth=number(args[3],1,max);
        String name=args.length==5?args[4]:"demo";
        RayTraceResult hit=p.rayTraceBlocks(12);
        if(hit==null || hit.getHitBlock()==null || hit.getHitBlockFace()!=BlockFace.UP)
            throw new IllegalArgumentException("Look at the TOP of a solid block within 12 blocks, for the prism's NW corner.");
        Block foundation=hit.getHitBlock();
        int bx=foundation.getX(),by=foundation.getY()+2,bz=foundation.getZ();
        World world=foundation.getWorld();
        createCube(sender,world,bx,by,bz,width,height,depth,name);
    }
    private void createAt(CommandSender sender,String[] args) throws Exception {
        if(args.length<8||args.length>9)throw new IllegalArgumentException(
                "Usage: /holomap create <world> <x> <y> <z> <X> <Y> <Z> [model]");
        World world=Bukkit.getWorld(args[1]);
        if(world==null)throw new IllegalArgumentException("World not loaded: "+args[1]);
        int max=Math.min(16,Math.max(1,getConfig().getInt("max-prism-axis",8)));
        createCube(sender,world,number(args[2],-29999900,29999900),
                number(args[3],world.getMinHeight()+1,world.getMaxHeight()-2),
                number(args[4],-29999900,29999900),number(args[5],1,max),
                number(args[6],1,max),number(args[7],1,max),args.length==9?args[8]:"demo");
    }
    private void createCube(CommandSender sender,World world,int bx,int by,int bz,
                            int width,int height,int depth,String name) throws Exception {
        if(by<=world.getMinHeight()||by+height>=world.getMaxHeight())
            throw new IllegalArgumentException("The prism and its frames must fit inside the world's height limits.");
        int maxMaps=Math.max(6,getConfig().getInt("max-prism-maps",96));
        int totalMaps=2*(width*height+width*depth+height*depth);
        if(totalMaps>maxMaps)throw new IllegalArgumentException(
                "This prism needs "+totalMaps+" maps; max-prism-maps allows "+maxMaps+". Smaller dimensions are recommended.");
        models.load(name);
        Material material=Material.matchMaterial(getConfig().getString("cube-material","AIR"));
        if(material==null || (!material.isAir()&&(!material.isBlock()||!material.isSolid())))
            throw new IllegalArgumentException("Invalid cube-material: choose AIR or a solid block.");
        boolean transparent=material.isAir()||material==Material.BARRIER||!material.isOccluding();
        boolean inward=transparent&&getConfig().getBoolean("cube-inward",true);
        boolean glow=getConfig().getBoolean("cube-glow",true);
        // Require empty volume + all six exterior face-neighbor layers.
        for(int x=-1;x<=width;x++)for(int y=-1;y<=height;y++)for(int z=-1;z<=depth;z++) {
            boolean core=x>=0&&x<width&&y>=0&&y<height&&z>=0&&z<depth;
            int outside=(x<0||x>=width?1:0)+(y<0||y>=height?1:0)+(z<0||z>=depth?1:0);
            if(!core&&outside!=1)continue;
            Block b=world.getBlockAt(bx+x,by+y,bz+z);
            if(!b.isEmpty())throw new IllegalArgumentException("Prism needs empty space at "+b.getX()+","+b.getY()+","+b.getZ());
            requireNoFrame(b);
        }
        int id=nextId++;
        Map<CubeGeometry.Face,List<Integer>> maps=new EnumMap<>(CubeGeometry.Face.class);
        Map<CubeGeometry.Face,List<UUID>> frames=new EnumMap<>(CubeGeometry.Face.class);
        for(CubeGeometry.Face face:CubeGeometry.Face.values()){maps.put(face,new ArrayList<>());frames.put(face,new ArrayList<>());}
        List<Block> blocks=new ArrayList<>();
        List<UUID> createdFrames=new ArrayList<>();
        try {
            if(!material.isAir())for(int x=0;x<width;x++)for(int y=0;y<height;y++)for(int z=0;z<depth;z++) {
                Block b=world.getBlockAt(bx+x,by+y,bz+z);
                b.setType(material,false);blocks.add(b);
            }
            for(CubeGeometry.Face face:CubeGeometry.Face.values()) {
                BlockFace normal=BlockFace.valueOf(face.screenBasis(inward).name());
                int fw=face.tileWidth(width,height,depth),fh=face.tileHeight(width,height,depth);
                for(int y=0;y<fh;y++)for(int x=0;x<fw;x++) {
                    int[] offset=face.support(width,height,depth,x,y,inward);
                    Block support=world.getBlockAt(bx+offset[0],by+offset[1],bz+offset[2]);
                    Block anchor=inward?support:support.getRelative(normal);
                    MapView map=Bukkit.createMap(world);
                    maps.get(face).add(map.getId());
                    ItemFrame frame=spawnFrame(anchor,normal,map,glow);
                    createdFrames.add(frame.getUniqueId());
                    frames.get(face).add(frame.getUniqueId());
                }
            }
            CubeDisplay cube=new CubeDisplay(this,id,world,bx,by,bz,width,height,depth,
                    name,material.name(),inward,maps,frames);
            cubes.put(id,cube);cube.installRenderers();saveDisplays();
            sender.sendMessage("§aHoloMap prism #"+id+" created ("+width+"x"+height+"x"+depth+", "+cube.mapCount()+" maps). Walk around all six faces.");
        }catch(Exception ex){
            CubeDisplay cube=cubes.remove(id);
            if(cube!=null)cube.uninstallRenderers();
            for(UUID uuid:createdFrames){Entity entity=Bukkit.getEntity(uuid);if(entity!=null)entity.remove();}
            for(Block block:blocks)if(block.getType()==material)block.setType(Material.AIR,false);
            throw ex;
        }
    }

    private void requireNoFrame(Block block) {
        block.getChunk().getEntities(); // Paper can load blocks before the chunk's entities.
        for(Entity entity:block.getWorld().getNearbyEntities(block.getLocation().add(.5,.5,.5),.49,.49,.49))
            if(entity instanceof Hanging)throw new IllegalArgumentException(
                    "An existing hanging entity obstructs "+block.getX()+","+block.getY()+","+block.getZ());
    }
    private ItemFrame spawnFrame(Block air,BlockFace normal,MapView map) {
        return spawnFrame(air,normal,map,false);
    }
    private ItemFrame spawnFrame(Block anchor,BlockFace normal,MapView map,boolean glow) {
        Class<? extends ItemFrame> type=glow?GlowItemFrame.class:ItemFrame.class;
        return anchor.getWorld().spawn(anchor.getLocation().add(.5,.5,.5),type,frame->{
            frame.setFixed(true);
            if(!frame.setFacingDirection(normal,true))throw new IllegalStateException("Could not orient item frame.");
            frame.setRotation(Rotation.NONE);
            frame.setVisible(false);
            frame.setInvulnerable(true);
            frame.setPersistent(true);
            frame.setItem(Display.filled(map),false);
        });
    }

    private void remove(CommandSender sender,String[] args) {
        if(args.length<2)throw new IllegalArgumentException("/holomap remove <id>");
        int id=Integer.parseInt(args[1]);Display d=displays.get(id);
        if(d==null){
            CubeDisplay cube=cubes.get(id);
            if(cube==null)throw new IllegalArgumentException("No display or cube with id "+id);
            // UUID lookups require the corresponding chunks to be loaded.
            for(CubeGeometry.Face face:CubeGeometry.Face.values())
                for(int y=0;y<face.tileHeight(cube.width,cube.height,cube.depth);y++)
                for(int x=0;x<face.tileWidth(cube.width,cube.height,cube.depth);x++){
                int[] p=face.support(cube.width,cube.height,cube.depth,x,y,cube.inward);
                int outside=cube.inward?0:1;
                cube.world.getChunkAt((cube.baseX+p[0]+outside*face.nx())>>4,
                        (cube.baseZ+p[2]+outside*face.nz())>>4).getEntities();
            }
            for(UUID uuid:cube.allFrames()){
                Entity entity=Bukkit.getEntity(uuid);
                if(entity instanceof ItemFrame)entity.remove();
            }
            Material blockMaterial=Material.matchMaterial(cube.blockMaterial);
            if(blockMaterial!=null&&!blockMaterial.isAir())for(int x=0;x<cube.width;x++)for(int y=0;y<cube.height;y++)for(int z=0;z<cube.depth;z++){
                Block block=cube.world.getBlockAt(cube.baseX+x,cube.baseY+y,cube.baseZ+z);
                if(block.getType()==blockMaterial)block.setType(Material.AIR,false);
            }
            cube.uninstallRenderers();cubes.remove(id);saveDisplays();
            sender.sendMessage("§aRemoved HoloMap prism #"+id+"; blocks changed since placement were preserved.");
            return;
        }
        // Ensure supporting chunks are present so Bukkit can resolve persisted UUIDs.
        Vector r=d.right();
        for(int y=0;y<d.height;y++)for(int x=0;x<d.width;x++) {
            int bx=d.supportX+d.facing.getModX()+r.getBlockX()*x;
            int bz=d.supportZ+d.facing.getModZ()+r.getBlockZ()*x;
            d.world.getChunkAt(bx>>4,bz>>4).getEntities();
        }
        for(UUID uuid:d.frameIds){Entity e=Bukkit.getEntity(uuid);if(e instanceof ItemFrame)e.remove();}
        d.uninstallRenderers();displays.remove(id);saveDisplays();
        sender.sendMessage("§aRemoved HoloMap #"+id+".");
    }
    private void changeModel(CommandSender sender,String[] args) throws Exception {
        if(args.length<3)throw new IllegalArgumentException("/holomap model <id> <name>");
        int id=Integer.parseInt(args[1]);
        Display d=displays.get(id);
        CubeDisplay cube=cubes.get(id);
        if(d==null&&cube==null)throw new IllegalArgumentException("Display not found.");
        models.load(args[2]);
        if(d!=null){d.model=args[2];d.invalidate();}
        else{cube.model=args[2];cube.invalidate();}
        saveDisplays();
        sender.sendMessage("§aDisplay #"+id+" uses "+args[2]);
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length==1)return matches(args[0],List.of("help","place","cube","create","list","models","model","remove","reload","status"));
        if(args[0].equalsIgnoreCase("place")) {
            if(args.length==2 || args.length==3)return matches(args[args.length-1],List.of("1","2","3"));
            if(args.length==4)return matches(args[3],models.installed());
        }
        if(args[0].equalsIgnoreCase("cube")){
            if(args.length>=2 && args.length<=4)
                return matches(args[args.length-1],List.of("1","2","3","4","5","6","7","8"));
            if(args.length==5)return matches(args[4],models.installed());
        }
        if((args[0].equalsIgnoreCase("remove")||args[0].equalsIgnoreCase("model"))&&args.length==2){
            List<String> ids=new ArrayList<>();
            for(int id:displays.keySet())ids.add(String.valueOf(id));
            for(int id:cubes.keySet())ids.add(String.valueOf(id));
            return matches(args[1],ids);
        }
        if(args[0].equalsIgnoreCase("model")&&args.length==3)return matches(args[2],models.installed());
        return List.of();
    }
    private List<String> matches(String prefix,List<String> values){return values.stream().filter(s->s.startsWith(prefix)).toList();}
}
