package dev.openai.holomap;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Converts Wavefront OBJ to a compact triangle stream without retaining faces or lines in memory.
 * Format: 8 bytes 'HMAPGPU1', little-endian int triangle count, six floats min/max XYZ,
 * then per vertex [float x,y,z; uint32 0xRRGGBB], 3 vertices per triangle.
 * The cache is loaded directly into an OpenGL VBO; shared animation normalization is done in shader.
 */
public final class ObjBinaryCache {
    private ObjBinaryCache() {}
    public record Frame(Path cache, int triangles, float[] bounds) {}
    private static final byte[] MAGIC={'H','M','A','P','G','P','U','1'};
    private static final int HEADER=36;
    private static final Pattern FACE_SEPARATOR=Pattern.compile("/");

    public static Frame convert(Path source, Path cacheDir, long maxBytes, int maxTriangles) throws IOException {
        source=source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) throw new IOException("OBJ not found: "+source);
        long fileSize=Files.size(source);
        if(fileSize>maxBytes) throw new IOException("OBJ exceeds max-obj-bytes ("+fileSize+">"+maxBytes+"): "+source);
        Files.createDirectories(cacheDir);
        long mtime=Files.getLastModifiedTime(source).toMillis();
        long mtlTime=0;
        try(var paths=Files.list(source.getParent())) {
            for(Path p:paths.filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".mtl")).toList())
                mtlTime=Math.max(mtlTime,Files.getLastModifiedTime(p).toMillis());
        }
        String cacheName=hash(source.toString())+".hmesh";
        Path destination=cacheDir.resolve(cacheName);
        Path metadata=cacheDir.resolve(cacheName+".properties");
        Properties previous=new Properties();
        if(Files.isRegularFile(destination)&&Files.isRegularFile(metadata))try(InputStream in=Files.newInputStream(metadata)){
            previous.load(in);
            if(Long.toString(fileSize).equals(previous.getProperty("source-size"))
                    &&Long.toString(mtime).equals(previous.getProperty("source-time"))
                    &&Long.toString(mtlTime).equals(previous.getProperty("mtl-time"))) {
                Frame cached=inspect(destination);
                if(cached.triangles<=maxTriangles)return cached;
            }
        }catch(Exception ignored){}
        Path temporary=Files.createTempFile(cacheDir,"hmap-", ".tmp");
        try {
            Frame frame=generate(source,temporary,maxTriangles);
            try{Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException ex){Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);}
            Properties props=new Properties();
            props.setProperty("source-size",Long.toString(fileSize));
            props.setProperty("source-time",Long.toString(mtime));
            props.setProperty("mtl-time",Long.toString(mtlTime));
            try(OutputStream out=Files.newOutputStream(metadata)){props.store(out,"Regenerated automatically");}
            return new Frame(destination,frame.triangles,frame.bounds);
        }finally {Files.deleteIfExists(temporary);}
    }

    /** Cache a built-in demo mesh without passing through an OBJ text file. */
    public static Frame writeBuiltIn(Mesh mesh,Path destination) throws IOException {
        if(Files.isRegularFile(destination))return inspect(destination);
        Files.createDirectories(destination.getParent());
        Path tmp=Files.createTempFile(destination.getParent(),"demo-",".tmp");
        float[] bounds={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        try(FileChannel ch=FileChannel.open(tmp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf=ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN);
            buf.position(HEADER);
            for(Mesh.Triangle t:mesh.triangles())for(Mesh.Vec v:new Mesh.Vec[]{t.a(),t.b(),t.c()}){
                if(buf.remaining()<16)flush(ch,buf);
                buf.putFloat((float)v.x()).putFloat((float)v.y()).putFloat((float)v.z()).putInt(t.rgb());
                bounds[0]=Math.min(bounds[0],(float)v.x());bounds[1]=Math.min(bounds[1],(float)v.y());bounds[2]=Math.min(bounds[2],(float)v.z());
                bounds[3]=Math.max(bounds[3],(float)v.x());bounds[4]=Math.max(bounds[4],(float)v.y());bounds[5]=Math.max(bounds[5],(float)v.z());
            }
            flush(ch,buf);
            ByteBuffer header=ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
            header.put(MAGIC).putInt(mesh.triangles().size());for(float b:bounds)header.putFloat(b);header.flip();
            ch.position(0);while(header.hasRemaining())ch.write(header);
        }
        try{Files.move(tmp,destination,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException ex){Files.move(tmp,destination,StandardCopyOption.REPLACE_EXISTING);}
        return new Frame(destination,mesh.triangles().size(),bounds);
    }

    public static Frame inspect(Path cache) throws IOException {
        ByteBuffer header=ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        try(FileChannel fc=FileChannel.open(cache,StandardOpenOption.READ)) {
            while(header.hasRemaining() && fc.read(header)!=-1){}
            if(header.hasRemaining())throw new IOException("Truncated mesh header: "+cache);
            header.flip();
            byte[] signature=new byte[8];header.get(signature);
            int triangleBytes=switch(new String(signature,java.nio.charset.StandardCharsets.US_ASCII)) {
                case "HMAPGPU1" -> 48;
                case "HMAPTEX1" -> 72;
                case "HMAPTEX2" -> 120;
                default -> throw new IOException("Bad mesh cache version: "+cache);
            };
            int count=header.getInt(); if(count<=0)throw new IOException("Empty mesh cache: "+cache);
            float[] bounds=new float[6]; for(int i=0;i<6;i++)bounds[i]=header.getFloat();
            if(fc.size()!=HEADER+(long)triangleBytes*count)throw new IOException("Incomplete mesh cache: "+cache);
            return new Frame(cache,count,bounds);
        }
    }

    private static Frame generate(Path source,Path temp,int maxTriangles) throws IOException {
        FloatStore vertices=new FloatStore();
        Map<String,Integer> materials=new HashMap<>();
        int color=0x84BBDF,count=0;
        float[] bounds={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        try(BufferedReader reader=Files.newBufferedReader(source);
            FileChannel output=FileChannel.open(temp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf=ByteBuffer.allocateDirect(192*1024).order(ByteOrder.LITTLE_ENDIAN);
            buf.position(HEADER);
            String line;
            while((line=reader.readLine())!=null) {
                line=line.strip(); if(line.isEmpty()||line.charAt(0)=='#')continue;
                int comment=line.indexOf('#'); if(comment>=0)line=line.substring(0,comment).stripTrailing();
                String[] t=line.split("\\s+");if(t.length==0)continue;
                try {
                    switch(t[0]) {
                        case "v" -> {if(t.length>=4){float x=finite(t[1]),y=finite(t[2]),z=finite(t[3]);
                            vertices.add(x,y,z);bounds[0]=Math.min(bounds[0],x);bounds[1]=Math.min(bounds[1],y);bounds[2]=Math.min(bounds[2],z);
                            bounds[3]=Math.max(bounds[3],x);bounds[4]=Math.max(bounds[4],y);bounds[5]=Math.max(bounds[5],z);}}
                        case "mtllib" -> {if(t.length>=2){Path mtl=source.getParent().resolve(line.substring(7).trim()).normalize();
                            if(!Files.isRegularFile(mtl))mtl=source.resolveSibling(source.getFileName().toString().replaceFirst("(?i)\\.obj$",".mtl"));
                            if(mtl.startsWith(source.getParent())&&Files.isRegularFile(mtl))materials.putAll(readMaterials(mtl));}}
                        case "usemtl" -> {if(t.length>=2)color=materials.getOrDefault(t[1],0x84BBDF);}
                        case "f" -> {if(t.length<4||t.length>65)break;
                            int[] indices=new int[t.length-1];
                            for(int i=1;i<t.length;i++) {
                                int slash=t[i].indexOf('/');String number=slash<0?t[i]:t[i].substring(0,slash);
                                int idx=Integer.parseInt(number);
                                indices[i-1]=idx>0?idx-1:vertices.size()+idx;
                                if(indices[i-1]<0||indices[i-1]>=vertices.size())throw new IOException("Invalid vertex index");
                            }
                            for(int i=1;i<indices.length-1;i++){
                                if(count>=maxTriangles)throw new IOException("OBJ exceeds max-triangles: "+maxTriangles);
                                if(buf.remaining()<48)flush(output,buf);
                                putVertex(buf,vertices,indices[0],color);
                                putVertex(buf,vertices,indices[i],color);
                                putVertex(buf,vertices,indices[i+1],color);
                                count++;
                            }
                        }
                        default -> {}
                    }
                }catch(NumberFormatException | ArrayIndexOutOfBoundsException ex){throw new IOException("Malformed OBJ: "+source.getFileName()+" near '"+line.substring(0,Math.min(line.length(),100))+"'",ex);}
            }
            flush(output,buf);
            if(count==0)throw new IOException("OBJ has no triangles: "+source);
            ByteBuffer header=ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
            header.put(MAGIC).putInt(count);for(float b:bounds)header.putFloat(b);header.flip();
            output.position(0);while(header.hasRemaining())output.write(header);
        }
        return new Frame(temp,count,bounds);
    }
    private static void putVertex(ByteBuffer buf, FloatStore v,int index,int rgb){
        int k=index*3;buf.putFloat(v.at(k)).putFloat(v.at(k+1)).putFloat(v.at(k+2)).putInt(rgb);
    }
    private static void flush(FileChannel channel,ByteBuffer buf)throws IOException{
        buf.flip();while(buf.hasRemaining())channel.write(buf);buf.clear();
    }
    private static float finite(String s){float f=Float.parseFloat(s);if(!Float.isFinite(f))throw new NumberFormatException("Non finite vertex");return f;}
    private static String hash(String input) {
        try{byte[] d=MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d,0,12);}catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private static Map<String,Integer> readMaterials(Path source) throws IOException {
        Map<String,Integer> ret=new HashMap<>();String name=null;
        try(BufferedReader in=Files.newBufferedReader(source)){String line;while((line=in.readLine())!=null){
            String[] p=line.strip().split("\\s+");if(p.length<2)continue;
            if(p[0].equals("newmtl"))name=p[1];
            else if(p[0].equals("Kd")&&p.length>=4&&name!=null)try{
                int r=channel(p[1]),g=channel(p[2]),b=channel(p[3]);ret.put(name,r<<16|g<<8|b);
            }catch(NumberFormatException ignore){}
        }}return ret;
    }
    private static int channel(String s){return Math.max(0,Math.min(255,(int)(Float.parseFloat(s)*255+.5f)));}
    private static final class FloatStore {
        private float[] data=new float[16384];private int size;
        int size(){return size/3;}
        void add(float x,float y,float z){if(size+3>data.length)data=Arrays.copyOf(data,Math.max(data.length*2,size+3));
            data[size++]=x;data[size++]=y;data[size++]=z;}
        float at(int index){return data[index];}
    }
}
