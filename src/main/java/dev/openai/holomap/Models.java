package dev.openai.holomap;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** CPU fallback for small OBJ models; asynchronous preparation of GPU models and animations. */
public final class Models {
    record Sample(Path path,int pose) {}
    public record Asset(List<ObjBinaryCache.Frame> frames,double midX,double midY,double midZ,double normalization,
                        double extentX,double extentY,double extentZ,PackedAnimation.Info animation) {
        public double fittedSize(int width,int height,int depth,double margin) {
            return ModelFit.size(width,height,depth,extentX,extentY,extentZ,margin);
        }
        public int frameCount(){
            return animation==null?frames.size():animation.frameCount();
        }
        Sample atTick(long tick,int legacyFps){
            if(animation!=null)return atSeconds(tick/20.0,legacyFps);
            int index=(int)((tick*(long)legacyFps/20)%frames.size());
            return new Sample(frames.get(index).cache(),-1);
        }
        Sample atSeconds(double seconds,int legacyFps){
            double fps=animation==null?legacyFps:animation.fps();
            int index=(int)((seconds*fps)%frameCount());
            return animation==null?new Sample(frames.get(index).cache(),-1):new Sample(animation.mesh().cache(),index);
        }
    }
    private final HoloMapPlugin plugin;
    private final Map<String,List<Mesh>> cpuCache=new HashMap<>();
    private final Map<String,Asset> assets=new ConcurrentHashMap<>();
    private final Map<String,String> statuses=new ConcurrentHashMap<>();
    private final Set<String> loading=ConcurrentHashMap.newKeySet();
    private final ExecutorService converter=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"HoloMap-Model-Loader");t.setDaemon(true);return t;});
    private volatile boolean closed;
    private final AtomicInteger generation=new AtomicInteger();

    public Models(HoloMapPlugin plugin){this.plugin=plugin;cpuCache.put("demo",Mesh.demo());}
    public boolean isGpu(){return plugin.getConfig().getString("renderer-backend","gpu").equalsIgnoreCase("gpu");}

    /** Validates the model directory, then schedules GPU preprocessing without freezing the server tick. */
    public List<Mesh> load(String name) throws IOException {
        if(!name.matches("[a-zA-Z0-9_-]{1,40}"))throw new IOException("Model name must be letters, numbers, _ or -");
        if(isGpu()) {
            if(assets.containsKey(name)||loading.contains(name))return List.of();
            final List<Path> sourceFiles;
            if(name.equals("demo")) sourceFiles=List.of();
            else sourceFiles=findFrames(name);
            loading.add(name);statuses.put(name,"preparing model");
            final int myGeneration=generation.get();
            converter.submit(()->{
                try{
                    Asset asset=convert(name,sourceFiles);
                    if(!closed&&myGeneration==generation.get()) {
                        assets.put(name,asset);
                        statuses.put(name,"ready ("+asset.frameCount()+" frame(s))");
                    }
                    if(myGeneration==generation.get())plugin.getLogger().info("GPU model '"+name+"': "+statuses.get(name));
                }catch(Exception ex){
                    if(myGeneration==generation.get()) {
                        statuses.put(name,"FAILED: "+ex.getMessage());
                        plugin.getLogger().warning("Cannot prepare GPU model '"+name+"': "+ex.getMessage());
                    }
                }finally{if(myGeneration==generation.get())loading.remove(name);}
            });
            return List.of();
        }
        if(cpuCache.containsKey(name))return cpuCache.get(name);
        List<Path> paths=findFrames(name);
        List<Mesh> meshes=new ArrayList<>();
        for(Path p:paths)meshes.add(ObjLoader.read(p,plugin.getConfig().getInt("max-triangles",6000)));
        List<Mesh> normalized=Mesh.normalizedSequence(meshes);cpuCache.put(name,normalized);return normalized;
    }
    public Asset asset(String name){return assets.get(name);}
    public String status(String name){return statuses.getOrDefault(name,"not loaded");}
    public List<Mesh> getLoaded(String name){return cpuCache.get(name);}
    public void clear(){generation.incrementAndGet();assets.clear();loading.clear();statuses.clear();cpuCache.clear();cpuCache.put("demo",Mesh.demo());}
    public void close(){closed=true;converter.shutdownNow();}

    private List<Path> findFrames(String name)throws IOException{
        Path dir=plugin.getDataFolder().toPath().resolve("models").resolve(name);
        Path animation=dir.resolve("model.hanim");
        if(isGpu()&&Files.isRegularFile(animation))return List.of(animation);
        Path frames=dir.resolve("frames");
        List<Path> files;
        if(Files.isDirectory(frames))try(var stream=Files.list(frames)){
            files=stream.filter(p->p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".obj")).sorted().toList();
        }else if(isGpu()&&Files.isRegularFile(dir.resolve("model.hmesh")))files=List.of(dir.resolve("model.hmesh"));
        else if(Files.isRegularFile(dir.resolve("model.obj")))files=List.of(dir.resolve("model.obj"));
        else if(Files.isRegularFile(animation))throw new IOException("model.hanim requires renderer-backend: gpu");
        else throw new IOException("Put model.hanim (GPU), model.obj or frames/*.obj in "+dir);
        int max=Math.max(1,plugin.getConfig().getInt("max-animation-frames",120));
        if(files.isEmpty()||files.size()>max)throw new IOException("Frame count must be 1.."+max);
        return files;
    }
    private Asset convert(String name,List<Path> paths)throws IOException{
        Path out=plugin.getDataFolder().toPath().resolve("gpu-cache");
        List<ObjBinaryCache.Frame> prepared=new ArrayList<>();
        PackedAnimation.Info animation=null;
        if(name.equals("demo")){
            List<Mesh> demo=Mesh.demo();
            for(int i=0;i<demo.size();i++)prepared.add(ObjBinaryCache.writeBuiltIn(demo.get(i),out.resolve("builtin-demo-"+i+".hmesh")));
        }else{
            long maxBytes=Math.max(1,plugin.getConfig().getLong("max-obj-bytes",1_500_000_000L));
            int maxTriangles=Math.max(1,plugin.getConfig().getInt("max-triangles",3_000_000));
            if(paths.getFirst().getFileName().toString().equals("model.hanim")){
                int maxFrames=Math.max(1,plugin.getConfig().getInt("max-packed-animation-frames",3600));
                animation=PackedAnimation.inspect(paths.getFirst(),maxFrames,maxTriangles);
                prepared.add(animation.mesh());
            }else{
                for(Path p:paths){if(Thread.currentThread().isInterrupted())throw new IOException("Conversion cancelled");
                    ObjBinaryCache.Frame frame=p.toString().endsWith(".hmesh")?ObjBinaryCache.inspect(p):ObjBinaryCache.convert(p,out,maxBytes,maxTriangles);
                    if(frame.triangles()>maxTriangles)throw new IOException("Prepared mesh exceeds max-triangles: "+frame.triangles());
                    prepared.add(frame);}
            }
        }
        double[] low={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
        double[] high={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(ObjBinaryCache.Frame f:prepared)for(int j=0;j<3;j++){
            low[j]=Math.min(low[j],f.bounds()[j]);high[j]=Math.max(high[j],f.bounds()[j+3]);
        }
        double normalization=1.0/Math.max(.001,Math.max(high[0]-low[0],Math.max(high[1]-low[1],high[2]-low[2])));
        return new Asset(List.copyOf(prepared),(low[0]+high[0])*.5,(low[1]+high[1])*.5,
                (low[2]+high[2])*.5,normalization,(high[0]-low[0])*normalization,
                (high[1]-low[1])*normalization,(high[2]-low[2])*normalization,animation);
    }
    public List<String> installed(){
        ArrayList<String> all=new ArrayList<>();all.add("demo");
        Path dir=plugin.getDataFolder().toPath().resolve("models");
        if(Files.isDirectory(dir))try(var s=Files.list(dir)){
            s.filter(Files::isDirectory).map(p->p.getFileName().toString()).sorted().forEach(all::add);
        }catch(IOException ignored){}
        return all;
    }
}
