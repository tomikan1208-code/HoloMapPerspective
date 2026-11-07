package dev.openai.holomap;

import java.nio.file.*;
import java.io.*;
import java.util.*;
import static dev.openai.holomap.Mesh.*;

/** Minimal portable Wavefront OBJ reader: v, f, usemtl, mtllib, triangulation, diffuse Kd. */
public final class ObjLoader {
    private ObjLoader() {}
    public static Mesh read(Path file, int maxTriangles) throws IOException {
        if (Files.size(file)>10_000_000L) throw new IOException("OBJ too large (10 MB limit): "+file);
        List<Vec> vertices=new ArrayList<>();
        List<Triangle> tris=new ArrayList<>();
        Map<String,Integer> materials=new HashMap<>();
        int color=0x84BBDF;
        // UTF-8 read handles standard ASCII OBJ and comments.
        for (String s: Files.readAllLines(file)) {
            s=s.split("#",2)[0].trim(); if(s.isEmpty()) continue;
            String[] p=s.split("\\s+");
            try {
                switch(p[0]) {
                    case "v" -> {if (p.length>=4) vertices.add(new Vec(coordinate(p[1]),coordinate(p[2]),coordinate(p[3])));}
                    case "mtllib" -> {
                        if (p.length>=2) {Path mtl=file.getParent().resolve(p[1]).normalize();
                            if (mtl.startsWith(file.getParent()) && Files.isRegularFile(mtl)) materials.putAll(readMaterials(mtl));}
                    }
                    case "usemtl" -> {if(p.length>=2) color=materials.getOrDefault(p[1],0x84BBDF);}
                    case "f" -> {
                        int n=p.length-1; if(n<3 || n>64) continue;
                        Vec[] polygon=new Vec[n];
                        for (int i=0;i<n;i++) {
                            int idx=Integer.parseInt(p[i+1].split("/",-1)[0]);
                            idx=(idx>0)?idx-1:vertices.size()+idx;
                            polygon[i]=vertices.get(idx);
                        }
                        for(int i=1;i<n-1;i++) {
                            if(tris.size()>=maxTriangles) throw new IOException("OBJ triangle limit exceeded: "+maxTriangles);
                            tris.add(new Triangle(polygon[0],polygon[i],polygon[i+1],color));
                        }
                    }
                    default -> {}
                }
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                throw new IOException("Invalid OBJ token: "+s+" in "+file.getFileName(),e);
            }
        }
        if(tris.isEmpty()) throw new IOException("OBJ has no supported faces: "+file.getFileName());
        return new Mesh(tris);
    }
    private static double coordinate(String token) {
        double value=Double.parseDouble(token);
        if(!Double.isFinite(value))throw new NumberFormatException("Non-finite vertex coordinate");
        return value;
    }
    private static Map<String,Integer> readMaterials(Path file) throws IOException {
        Map<String,Integer> result=new HashMap<>(); String name=null;
        for (String s: Files.readAllLines(file)) {
            String[] p=s.trim().split("\\s+"); if(p.length<2) continue;
            if(p[0].equals("newmtl")) name=p[1];
            if(p[0].equals("Kd") && p.length>=4 && name!=null) {
                try { int r=byteColor(p[1]),g=byteColor(p[2]),b=byteColor(p[3]); result.put(name,(r<<16)|(g<<8)|b); }
                catch(NumberFormatException ignore) {}
            }
        }
        return result;
    }
    private static int byteColor(String n) {return Math.max(0,Math.min(255,(int)Math.round(Double.parseDouble(n)*255)));}
}
