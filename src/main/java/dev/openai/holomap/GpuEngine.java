package dev.openai.holomap;

import org.bukkit.map.MapPalette;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Non-blocking GPU rendering. A viewer's entire scene is published in one operation. */
public final class GpuEngine implements AutoCloseable {
    private static final long ACTIVE_NANOS=250_000_000L;
    private static final long REST_NANOS=80_000_000L;
    private static final int MOVING_PIXEL_BUDGET=800_000;
    public record Request(String face,int widthPx,int heightPx,double eyeX,double eyeY,double eyeZ,
                          int widthTiles,int heightTiles,double scale,double depth,CubeGeometry.Face basis,
                          boolean visible) {}
    public record Scene(long tick,Map<String,byte[]> faces) {
        public Scene { faces=Map.copyOf(faces); }
        public byte[] pixels(String face){return faces.get(face);}
    }
    @FunctionalInterface public interface Delivery {
        boolean send(Scene scene)throws Exception;
        default void reset() {}
        default boolean ready() {return true;}
    }
    private static final class Frame {
        volatile Scene completed;
        volatile Submission latest;
        volatile Delivery delivery;
        volatile long activeAt;
        volatile long nextFrameAt;
        volatile int ping;
        volatile Submission rendered;
        long renderNanos=8_000_000L;
        final AtomicBoolean pending=new AtomicBoolean();
        long tick=-1000;
        long rateAt=System.nanoTime();
        int rateFrames;
        volatile double renderRate;
    }
    private record Submission(long tick,Models.Asset asset,ObjBinaryCache.Frame mesh,List<Request> requests,
                              long sampledAt,long previousAt,List<Request> previous,long revision,
                              boolean moving,boolean abrupt) {}
    private final HoloMapPlugin plugin;
    private final ThreadPoolExecutor jobs=new ThreadPoolExecutor(1,1,0L,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(48),r->{Thread t=new Thread(r,"HoloMap-GPU-IO");t.setDaemon(true);return t;},
            new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentHashMap<String,Frame> cache=new ConcurrentHashMap<>();
    // Only the GPU worker touches this table. Zero is an uncomputed opaque color.
    private final byte[] colors=new byte[1<<24];
    private final ConcurrentHashMap<Integer,byte[]> blanks=new ConcurrentHashMap<>();
    private final AtomicLong completedScenes=new AtomicLong();
    private final AtomicLong deliveredScenes=new AtomicLong();
    private final AtomicLong supersededScenes=new AtomicLong();
    private final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"HoloMap-Frame-Clock");t.setDaemon(true);return t;});
    private ScheduledFuture<?> pulse;
    private volatile int targetFps,animationFps;
    private volatile double viewDistanceSquared;
    private final long animationStart=System.nanoTime();
    private volatile double lastBatchMillis;
    private volatile String backendInfo="Not started";
    private Process process;
    private DataInputStream input;
    private DataOutputStream output;
    private volatile boolean closed;
    private long retryAfterMillis,lastWarningMillis;

    public GpuEngine(HoloMapPlugin plugin){this.plugin=plugin;configure();}
    public String status(){
        String streams=cache.entrySet().stream().filter(e->e.getValue().delivery!=null&&e.getValue().latest!=null
                        &&System.nanoTime()-e.getValue().activeAt<ACTIVE_NANOS)
                .map(e->"#"+e.getKey().split(":",2)[0]+":"+String.format(Locale.ROOT,"%.1f fps",e.getValue().renderRate))
                .sorted().collect(java.util.stream.Collectors.joining(", "));
        return backendInfo+"; queued: "+jobs.getQueue().size()
            +"; target: "+targetFps+" fps; complete scenes: "+completedScenes.get()+"; sent bundles: "+deliveredScenes.get()
            +"; superseded scenes: "+supersededScenes.get()
            +"; last batch: "+String.format(Locale.ROOT,"%.1f ms",lastBatchMillis)+(streams.isEmpty()?"":"; streams: "+streams);}
    public void start(){jobs.execute(()->{try{ensureProcess();}catch(IOException ex){backendInfo="GPU ERROR: "+ex.getMessage();plugin.getLogger().warning(backendInfo);}});}
    private void configure() {
        targetFps=Math.max(1,Math.min(60,plugin.getConfig().getInt("render-fps",20)));
        animationFps=Math.max(1,Math.min(60,plugin.getConfig().getInt("animation-fps",20)));
        double distance=plugin.getConfig().getDouble("view-distance",16);
        viewDistanceSquared=distance*distance;
        if(pulse!=null)pulse.cancel(false);
        // A 20 ms render should start again when it finishes, not wait until the
        // next 16.7 ms boundary and effectively fall to 30 frames per second.
        pulse=clock.scheduleWithFixedDelay(this::stream,0,2_000_000L,TimeUnit.NANOSECONDS);
    }
    public void reset(){cache.clear();jobs.getQueue().clear();configure();if(!closed)jobs.execute(this::stopProcess);}
    public void invalidate(int displayId){cache.keySet().removeIf(k->k.startsWith(displayId+":"));}
    public void forgetPlayer(UUID player){cache.keySet().removeIf(k->k.contains(":"+player+":"));}
    private static String key(int displayId,UUID player,String model){return displayId+":"+player+":"+model;}
    public boolean isStreaming(int displayId,UUID player,String model) {
        Frame frame=cache.get(key(displayId,player,model));return frame!=null&&frame.delivery!=null;
    }
    public void bindDelivery(int displayId,UUID player,String model,Delivery delivery) {
        Frame frame=cache.computeIfAbsent(key(displayId,player,model),k->new Frame());
        frame.activeAt=0;
        frame.rendered=null;
        frame.delivery=delivery;
    }
    private void stream() {
        if(closed)return;
        long now=System.nanoTime();
        cache.forEach((key,frame)->{
            Submission sample=frame.latest;
            Delivery delivery=frame.delivery;
            if(delivery==null||sample==null||now<frame.nextFrameAt||now-frame.activeAt>=ACTIVE_NANOS||!delivery.ready())return;
            if(sample==frame.rendered&&!sample.moving()&&sample.asset().frames().size()==1)return;
            submit(key,frame,true);
        });
    }

    /** Captures accepted movement packets as well as the regular server heartbeat. */
    public void sampleScene(int displayId,UUID player,String model,long tick,List<Request> requests,int ping,boolean teleport) {
        String key=key(displayId,player,model);
        Frame frame=cache.get(key);
        Models.Asset asset=plugin.models().asset(model);
        if(frame==null||asset==null)return;
        Delivery delivery=frame.delivery;
        if(delivery==null)return;
        long now=System.nanoTime();
        boolean returning=frame.activeAt==0||now-frame.activeAt>=ACTIVE_NANOS;
        frame.activeAt=now;
        frame.ping=ping;
        Submission prior=frame.latest;
        Request current=requests.getFirst();
        Request last=prior==null?current:prior.requests().getFirst();
        double dx=current.eyeX()-last.eyeX(),dy=current.eyeY()-last.eyeY(),dz=current.eyeZ()-last.eyeZ();
        double travel=dx*dx+dy*dy+dz*dz;
        boolean moving=travel>1e-8;
        boolean snap=teleport||returning||prior==null||travel>4;
        if(!snap&&!moving&&(!prior.moving()||now-prior.sampledAt()<REST_NANOS))return;

        boolean turn=false;
        if(!snap&&moving&&!prior.previous().isEmpty()) {
            Request before=prior.previous().getFirst();
            turn=ViewMotion.sharpTurn(dx,dy,dz,last.eyeX()-before.eyeX(),last.eyeY()-before.eyeY(),last.eyeZ()-before.eyeZ());
        }
        long revision=prior==null?0:prior.revision();
        if(snap||turn||!moving)revision++;
        if(returning)delivery.reset();
        frame.latest=new Submission(tick,asset,asset.at(tick,Math.min(20,animationFps)),List.copyOf(requests),now,
                snap?0:prior.sampledAt(),snap?List.of():prior.requests(),revision,moving&&!snap,turn);
        if((snap||turn)&&delivery.ready())submit(key,frame,true);
    }

    /** Every face uses the same eye position and animation tick; partial results stay private. */
    public Scene renderScene(int displayId,UUID player,String model,long tick,List<Request> requests) {
        Scene empty=emptyScene(requests);
        if(closed)return empty;
        String key=key(displayId,player,model);
        Frame frame=cache.computeIfAbsent(key,k->new Frame());
        Scene previous=frame.completed;
        if(frame.delivery!=null)return previous==null?empty:previous;
        int fps=Math.min(20,targetFps);
        int interval=(20+fps-1)/fps;
        Models.Asset asset=plugin.models().asset(model);
        if(asset==null)return previous==null?empty:previous;
        // A queued job picks up the newest pose when it starts, rather than an old position.
        Submission older=frame.latest;
        if(older==null||older.tick()!=tick)frame.latest=new Submission(tick,asset,asset.at(tick,Math.min(20,animationFps)),
                List.copyOf(requests),System.nanoTime(),0,List.of(),0,false,false);
        if(frame.pending.get()||tick-frame.tick<interval)return previous==null?empty:previous;
        frame.tick=tick;
        submit(key,frame,false);
        return previous==null?empty:previous;
    }
    private void submit(String key,Frame frame,boolean streaming) {
        if(!frame.pending.compareAndSet(false,true))return;
        if(streaming) {
            long now=System.nanoTime();
            long period=1_000_000_000L/targetFps;
            long due=frame.nextFrameAt;
            frame.nextFrameAt=(due!=0&&now>=due&&now-due<period?due:now)+period;
        }
        try {
            jobs.execute(()->{
                long start=System.nanoTime();
                try {
                    if(closed||cache.get(key)!=frame)return;
                    Submission captured=frame.latest;
                    if(captured==null)return;
                    Delivery delivery=frame.delivery;
                    if(streaming&&(delivery==null||!delivery.ready()||start-frame.activeAt>=ACTIVE_NANOS))return;
                    long lead=Math.min(25_000_000L,frame.renderNanos)+Math.min(20,Math.max(0,frame.ping)/2)*1_000_000L;
                    List<Request> requests=streaming?predicted(captured,start+lead):captured.requests();
                    int stride=streaming&&captured.moving()?motionStride(requests):1;
                    ObjBinaryCache.Frame mesh=streaming?captured.asset().frames().get((int)(
                            ((System.nanoTime()-animationStart)/1_000_000_000.0*animationFps)%captured.asset().frames().size())):captured.mesh();
                    Map<String,byte[]> rendered=new LinkedHashMap<>();
                    for(Request request:requests) {
                        if(streaming&&superseded(frame,captured))return;
                        byte[] pixels;
                        if(!request.visible())pixels=blank(request.widthPx()*request.heightPx());
                        else {
                            int w=request.widthPx()/stride,h=request.heightPx()/stride;
                            byte[] rgba=process(mesh.cache().toAbsolutePath().toString(),request,captured.asset(),w,h);
                            pixels=palette(rgba,w*h);
                            if(stride>1)pixels=upscale(pixels,w,h,stride);
                        }
                        rendered.put(request.face(),pixels);
                    }
                    // Publish once, including transparent back faces. Never expose a half-rendered cube.
                    if(!closed&&cache.get(key)==frame&&(!streaming||!superseded(frame,captured))) {
                        frame.completed=new Scene(captured.tick(),rendered);
                        completedScenes.incrementAndGet();
                        frame.renderNanos=System.nanoTime()-start;
                        lastBatchMillis=frame.renderNanos/1_000_000.0;
                        frame.rateFrames++;
                        long elapsed=System.nanoTime()-frame.rateAt;
                        if(elapsed>=1_000_000_000L){frame.renderRate=frame.rateFrames*1e9/elapsed;frame.rateAt=System.nanoTime();frame.rateFrames=0;}
                        if(streaming&&delivery==frame.delivery&&delivery.ready())try {
                            if(delivery.send(frame.completed))deliveredScenes.incrementAndGet();
                            frame.rendered=captured;
                        }catch(Exception ex){
                            frame.delivery=null;plugin.getLogger().fine("Map stream closed: "+ex.getMessage());
                        }
                    }
                }catch(Exception ex){
                    if(closed||cache.get(key)!=frame)return;
                    backendInfo="GPU ERROR: "+ex.getMessage();
                    long now=System.currentTimeMillis();
                    if(now-lastWarningMillis>=10_000) {
                        lastWarningMillis=now;plugin.getLogger().warning("HoloMap GPU: "+ex.getMessage());
                    }
                    stopProcess();
                }finally{frame.pending.set(false);}
            });
        }catch(RejectedExecutionException ex){frame.pending.set(false);}
    }
    private boolean superseded(Frame frame,Submission sample) {
        if(frame.latest.revision()==sample.revision())return false;
        supersededScenes.incrementAndGet();
        return true;
    }
    private static int motionStride(List<Request> requests) {
        int pixels=0;
        for(Request request:requests)if(request.visible())pixels+=request.widthPx()*request.heightPx();
        int stride=1;
        while(pixels/(stride*stride)>MOVING_PIXEL_BUDGET)stride*=2;
        return stride;
    }
    private static byte[] upscale(byte[] small,int width,int height,int stride) {
        int rowWidth=width*stride;
        byte[] full=new byte[rowWidth*height*stride];
        for(int y=0;y<height;y++) {
            int row=y*stride*rowWidth;
            for(int x=0;x<width;x++)Arrays.fill(full,row+x*stride,row+(x+1)*stride,small[y*width+x]);
            for(int dy=1;dy<stride;dy++)System.arraycopy(full,row,full,row+dy*rowWidth,rowWidth);
        }
        return full;
    }
    private List<Request> predicted(Submission sample,long now) {
        if(!sample.moving()||sample.previous().size()!=sample.requests().size())return sample.requests();
        List<Request> result=new ArrayList<>(sample.requests().size());
        for(int i=0;i<sample.requests().size();i++) {
            Request r=sample.requests().get(i),p=sample.previous().get(i);
            double dx=r.eyeX()-p.eyeX(),dy=r.eyeY()-p.eyeY(),dz=r.eyeZ()-p.eyeZ();
            double f=ViewMotion.fraction(sample.previousAt(),sample.sampledAt(),now,dx*dx+dy*dy+dz*dz,sample.abrupt());
            double x=ViewMotion.predict(p.eyeX(),r.eyeX(),f),y=ViewMotion.predict(p.eyeY(),r.eyeY(),f),z=ViewMotion.predict(p.eyeZ(),r.eyeZ(),f);
            // Visibility follows the predicted plane crossing; all faces share the same time.
            result.add(new Request(r.face(),r.widthPx(),r.heightPx(),x,y,z,r.widthTiles(),r.heightTiles(),r.scale(),r.depth(),r.basis(),
                    z>1e-4&&x*x+y*y+z*z<=viewDistanceSquared));
        }
        return result;
    }

    /** Planar displays are a one-face scene and use the same publication rules. */
    public byte[] render(int displayId,String suffix,UUID player,String model,long tick,
                         int widthPx,int heightPx,double eyeX,double eyeY,double eyeZ,
                         int widthTiles,int heightTiles,double scale,double depth,CubeGeometry.Face face) {
        Request request=new Request(suffix,widthPx,heightPx,eyeX,eyeY,eyeZ,widthTiles,heightTiles,scale,depth,face,eyeZ>1e-4);
        return renderScene(displayId,player,model,tick,List.of(request)).pixels(suffix);
    }
    private Scene emptyScene(List<Request> requests) {
        Map<String,byte[]> faces=new LinkedHashMap<>();
        for(Request request:requests)faces.put(request.face(),blank(request.widthPx()*request.heightPx()));
        return new Scene(-1,faces); // Placeholder is not a completed GPU frame.
    }
    @SuppressWarnings({"deprecation","removal"}) private byte[] palette(byte[] rgba,int count)throws IOException {
        if(rgba.length!=count*4)throw new IOException("Unexpected GPU framebuffer size: "+rgba.length);
        byte[] indices=new byte[count];
        for(int p=0,j=0;p<count;p++,j+=4) {
            if((rgba[j+3]&255)<128)continue;
            int rgb=(rgba[j]&255)<<16|(rgba[j+1]&255)<<8|(rgba[j+2]&255);
            byte index=colors[rgb];
            if(index==0) {
                index=MapPalette.matchColor((rgb>>16)&255,(rgb>>8)&255,rgb&255);
                colors[rgb]=index;
            }
            indices[p]=index;
        }
        return indices;
    }
    private byte[] blank(int length){return blanks.computeIfAbsent(length,n->new byte[n]);}
    private byte[] process(String meshPath,Request request,Models.Asset asset,int width,int height)throws IOException {
        ensureProcess();
        byte[] path=meshPath.getBytes(StandardCharsets.UTF_8);
        if(path.length>65535)throw new IOException("GPU mesh path too long");
        output.writeInt(path.length);output.write(path);
        output.writeInt(width);output.writeInt(height);
        for(double value:new double[]{request.eyeX(),request.eyeY(),request.eyeZ(),request.widthTiles(),request.heightTiles(),
                request.scale(),request.depth(),asset.midX(),asset.midY(),asset.midZ(),asset.normalization()})output.writeDouble(value);
        CubeGeometry.Face face=request.basis();
        double[] basis=face==null?new double[]{1,0,0,0,1,0,0,0,1}
                :new double[]{face.rx(),face.ry(),face.rz(),face.ux(),face.uy(),face.uz(),face.nx(),face.ny(),face.nz()};
        for(double value:basis)output.writeDouble(value);
        output.flush();
        int resultSize=input.readInt();
        if(resultSize<0) {
            int errorSize=-resultSize;if(errorSize>8192||errorSize<0)throw new IOException("Invalid GPU error size");
            throw new IOException(new String(input.readNBytes(errorSize),StandardCharsets.UTF_8));
        }
        if(resultSize!=width*height*4)throw new IOException("Bad GPU result size "+resultSize);
        byte[] result=input.readNBytes(resultSize);
        if(result.length!=resultSize)throw new EOFException("GPU process stopped before finishing frame");
        return result;
    }
    private void ensureProcess()throws IOException {
        if(process!=null&&process.isAlive())return;
        long now=System.currentTimeMillis();
        if(now<retryAfterMillis)throw new IOException("GPU unavailable; see gpu-renderer.log");
        retryAfterMillis=now+10_000;
        String python=plugin.getConfig().getString("gpu-python-command","python");
        File script=new File(plugin.getDataFolder(),"gpu/renderer.py");
        if(!script.isFile())throw new IOException("GPU helper not installed: "+script);
        List<String> command=new ArrayList<>(List.of(python.split("\\s+")));
        command.add("-u");command.add(script.getAbsolutePath());
        ProcessBuilder builder=new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.appendTo(new File(plugin.getDataFolder(),"gpu-renderer.log")));
        process=builder.start();
        input=new DataInputStream(new BufferedInputStream(process.getInputStream()));
        output=new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
        int size=input.readInt();if(size<5||size>1024)throw new IOException("Bad GPU startup response");
        String info=new String(input.readNBytes(size),StandardCharsets.UTF_8);
        if(!info.startsWith("READY "))throw new IOException(info);
        backendInfo=info;plugin.getLogger().info("HoloMap "+info);
    }
    private synchronized void stopProcess() {
        if(process!=null)process.destroyForcibly();process=null;input=null;output=null;
    }
    @Override public void close(){closed=true;clock.shutdownNow();jobs.shutdownNow();stopProcess();cache.clear();}
}
