package dev.openai.holomap;

import java.util.Arrays;
import static dev.openai.holomap.Mesh.*;

/**
 * Pure Java CPU triangle rasterizer. The map is a fixed window (z=0), and the
 * player's eye is the projection center. A character lives behind this window.
 * No camera rotation trick or pre-rendered viewpoint switching is involved.
 */
public final class PerspectiveRaster {
    /** Outside the 24-bit RGB range, so a black model pixel stays opaque. */
    public static final int TRANSPARENT = -1;
    private PerspectiveRaster() { }
    private record P(double x,double y,double reciprocalDepth) {}
    public static int[] render(Mesh mesh,int widthPx,int heightPx,
                               double eyeX,double eyeY,double eyeZ,
                               double widthBlocks,double heightBlocks,double scale,double depth) {
        return render(mesh,widthPx,heightPx,eyeX,eyeY,eyeZ,widthBlocks,heightBlocks,scale,depth,null);
    }
    public static int[] render(Mesh mesh,int widthPx,int heightPx,
                               double eyeX,double eyeY,double eyeZ,
                               double widthBlocks,double heightBlocks,double scale,double depth,CubeGeometry.Face face) {
        int[] pixels=new int[widthPx*heightPx];
        Arrays.fill(pixels,TRANSPARENT);
        if(eyeZ<=.1) return pixels;
        float[] zbuffer=new float[pixels.length];
        Arrays.fill(zbuffer,Float.NEGATIVE_INFINITY);
        double size=heightBlocks*scale;
        for (Triangle t: mesh.triangles()) {
            Vec a=place(t.a(),size,depth),b=place(t.b(),size,depth),c=place(t.c(),size,depth);
            if(a.z()>=eyeZ || b.z()>=eyeZ || c.z()>=eyeZ) continue;
            P ap=project(a,eyeX,eyeY,eyeZ,widthBlocks,heightBlocks,widthPx,heightPx);
            P bp=project(b,eyeX,eyeY,eyeZ,widthBlocks,heightBlocks,widthPx,heightPx);
            P cp=project(c,eyeX,eyeY,eyeZ,widthBlocks,heightBlocks,widthPx,heightPx);
            double area=edge(ap.x,ap.y,bp.x,bp.y,cp.x,cp.y);
            if(Math.abs(area)<1e-7) continue;
            int minX=Math.max(0,(int)Math.floor(Math.min(ap.x,Math.min(bp.x,cp.x))));
            int minY=Math.max(0,(int)Math.floor(Math.min(ap.y,Math.min(bp.y,cp.y))));
            int maxX=Math.min(widthPx-1,(int)Math.ceil(Math.max(ap.x,Math.max(bp.x,cp.x))));
            int maxY=Math.min(heightPx-1,(int)Math.ceil(Math.max(ap.y,Math.max(bp.y,cp.y))));
            if(minX>maxX||minY>maxY) continue;
            Vec normal=b.sub(a).cross(c.sub(a));
            // Adjacent transparent screens must shade the same world triangle identically.
            if(face!=null)normal=face.toWorld(normal);
            double norm=Math.max(1e-6,normal.norm());
            double light=Math.max(.34,Math.min(1.0,.64+.3*Math.abs(normal.z())/norm+.13*normal.y()/norm));
            int rgb=shade(t.rgb(),light);
            for(int y=minY;y<=maxY;y++) for(int x=minX;x<=maxX;x++) {
                double u=edge(bp.x,bp.y,cp.x,cp.y,x+.5,y+.5)/area;
                double v=edge(cp.x,cp.y,ap.x,ap.y,x+.5,y+.5)/area;
                double w=1-u-v;
                if(u < -1e-7 || v < -1e-7 || w < -1e-7) continue;
                float near=(float)(u*ap.reciprocalDepth+v*bp.reciprocalDepth+w*cp.reciprocalDepth);
                int at=y*widthPx+x;
                if(near>zbuffer[at]) {zbuffer[at]=near; pixels[at]=rgb;}
            }
        }
        return pixels;
    }
    private static Vec place(Vec v,double scale,double depth){
        return new Vec(v.x()*scale,v.y()*scale,v.z()*scale-depth);
    }
    private static P project(Vec v,double eyeX,double eyeY,double eyeZ,double width,double height,int px,int py) {
        double t=eyeZ/(eyeZ-v.z());
        double xx=eyeX+(v.x()-eyeX)*t;
        double yy=eyeY+(v.y()-eyeY)*t;
        return new P((xx/width+.5)*px,(.5-yy/height)*py,1.0/(eyeZ-v.z()));
    }
    private static double edge(double ax,double ay,double bx,double by,double px,double py){
        return (px-ax)*(by-ay)-(py-ay)*(bx-ax);
    }
    private static int shade(int rgb,double light) {
        int r=(int)(((rgb>>16)&255)*light),g=(int)(((rgb>>8)&255)*light),b=(int)((rgb&255)*light);
        return (r<<16)|(g<<8)|b;
    }
}
