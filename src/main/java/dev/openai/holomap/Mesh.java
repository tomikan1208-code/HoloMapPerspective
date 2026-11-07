package dev.openai.holomap;

import java.util.*;

/** Small, immutable triangle mesh in Y-up, Z-toward-viewer coordinates. */
public final class Mesh {
    public record Vec(double x, double y, double z) {
        Vec add(Vec b) { return new Vec(x+b.x,y+b.y,z+b.z); }
        Vec sub(Vec b) { return new Vec(x-b.x,y-b.y,z-b.z); }
        Vec mul(double s) { return new Vec(x*s,y*s,z*s); }
        Vec cross(Vec b) { return new Vec(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x); }
        double norm() { return Math.sqrt(x*x+y*y+z*z); }
    }
    public record Triangle(Vec a, Vec b, Vec c, int rgb) { }
    private final List<Triangle> triangles;
    public Mesh(List<Triangle> input) { triangles = List.copyOf(input); }
    public List<Triangle> triangles() { return triangles; }

    /** Fit all three axes inside a unit cube, so wide models also fit a prism. */
    public Mesh normalized() {
        if (triangles.isEmpty()) return this;
        double x0=Double.POSITIVE_INFINITY, y0=x0, z0=x0;
        double x1=Double.NEGATIVE_INFINITY, y1=x1, z1=x1;
        for (Triangle t: triangles) for (Vec v: new Vec[]{t.a,t.b,t.c}) {
            x0=Math.min(x0,v.x); y0=Math.min(y0,v.y); z0=Math.min(z0,v.z);
            x1=Math.max(x1,v.x); y1=Math.max(y1,v.y); z1=Math.max(z1,v.z);
        }
        double k = 1.0 / Math.max(0.001, Math.max(x1-x0,Math.max(y1-y0,z1-z0)));
        Vec mid = new Vec((x0+x1)*.5,(y0+y1)*.5,(z0+z1)*.5);
        List<Triangle> output = new ArrayList<>(triangles.size());
        for (Triangle t: triangles) output.add(new Triangle(t.a.sub(mid).mul(k), t.b.sub(mid).mul(k), t.c.sub(mid).mul(k), t.rgb));
        return new Mesh(output);
    }


    /** Stable transform for an OBJ frame sequence; do not auto-center each frame separately. */
    public static List<Mesh> normalizedSequence(List<Mesh> frames) {
        if (frames.isEmpty()) return List.of();
        double[] low={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
        double[] high={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for (Mesh m:frames) for(Triangle t:m.triangles) for(Vec v:new Vec[]{t.a,t.b,t.c}) {
            double[] val={v.x,v.y,v.z};
            for(int i=0;i<3;i++){low[i]=Math.min(low[i],val[i]);high[i]=Math.max(high[i],val[i]);}
        }
        Vec mid=new Vec((low[0]+high[0])*.5,(low[1]+high[1])*.5,(low[2]+high[2])*.5);
        double scale=1.0/Math.max(1e-3,Math.max(high[0]-low[0],Math.max(high[1]-low[1],high[2]-low[2])));
        List<Mesh> result=new ArrayList<>();
        for(Mesh m:frames){
            List<Triangle> tris=new ArrayList<>(m.triangles.size());
            for(Triangle t:m.triangles)tris.add(new Triangle(t.a.sub(mid).mul(scale),t.b.sub(mid).mul(scale),t.c.sub(mid).mul(scale),t.rgb));
            result.add(new Mesh(tris));
        }
        return List.copyOf(result);
    }

    public static final class Builder {
        private final List<Triangle> triangles = new ArrayList<>();
        /** Box can be tilted around a Y axis, and animated arm boxes use X-axis swing. */
        public Builder box(Vec center, double sx, double sy, double sz, int rgb, double rotY, double rotX) {
            Vec[] v = new Vec[8]; int i=0;
            for (int az=0;az<2;az++) for (int ay=0;ay<2;ay++) for (int ax=0;ax<2;ax++) {
                double x=(ax-.5)*sx, y=(ay-.5)*sy, z=(az-.5)*sz;
                double yy = y*Math.cos(rotX)-z*Math.sin(rotX);
                double zz = y*Math.sin(rotX)+z*Math.cos(rotX);
                double xx = x*Math.cos(rotY)+zz*Math.sin(rotY);
                double zzz = -x*Math.sin(rotY)+zz*Math.cos(rotY);
                v[i++] = new Vec(xx+center.x,yy+center.y,zzz+center.z);
            }
            // axis-aligned box faces, two triangles per quad
            quad(v,0,1,3,2,rgb); quad(v,4,6,7,5,rgb);
            quad(v,0,4,5,1,rgb); quad(v,2,3,7,6,rgb);
            quad(v,0,2,6,4,rgb); quad(v,1,5,7,3,rgb);
            return this;
        }
        private void quad(Vec[] v,int a,int b,int c,int d,int rgb){
            triangles.add(new Triangle(v[a],v[b],v[c],rgb));
            triangles.add(new Triangle(v[a],v[c],v[d],rgb));
        }
        public Mesh build() { return new Mesh(triangles); }
    }
    public static List<Mesh> demo() {
        List<Mesh> meshes=new ArrayList<>();
        for (int frame=0;frame<40;frame++) {
            double t=2*Math.PI*frame/40.0;
            double bob=.025*Math.sin(t*2);
            double yaw=.20*Math.sin(t);
            Builder b=new Builder();
            b.box(new Vec(0,.08+bob,0),.36,.42,.20,0x62D2CB,yaw,0); // torso
            b.box(new Vec(0,.37+bob,0),.31,.31,.30,0xDDEDEB,yaw,.08*Math.sin(t)); // head
            b.box(new Vec(-.072,.40+bob,.155),.045,.055,.015,0x253A5C,0,0); // eyes
            b.box(new Vec(.072,.40+bob,.155),.045,.055,.015,0x253A5C,0,0);
            b.box(new Vec(-.25,.09+bob,0),.11,.35,.13,0x63A7C4,0,.65*Math.sin(t));
            b.box(new Vec(.25,.09+bob,0),.11,.35,.13,0x63A7C4,0,-.65*Math.sin(t));
            b.box(new Vec(-.10,-.25+bob,0),.13,.30,.14,0x343B66,0,-.45*Math.sin(t));
            b.box(new Vec(.10,-.25+bob,0),.13,.30,.14,0x343B66,0,.45*Math.sin(t));
            meshes.add(b.build());
        }
        return List.copyOf(meshes);
    }
}
