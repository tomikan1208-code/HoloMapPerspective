package dev.openai.holomap;

import java.util.ArrayList;
import java.util.List;

/**
 * Six outward-facing cube screens. Each face's (right, up, normal) vectors are
 * fixed in world coordinates. A single world-aligned model is projected onto
 * every screen, so a viewer walking around the cube sees the model's reverse
 * side, not six identical, front-facing images.
 *
 * No Bukkit dependency: the coordinate math can be tested independently.
 */
public final class CubeGeometry {
    // Vanilla 26.3 invisible-frame map plane: 1 pixel + MapRenderer's .01 pixel offset.
    public static final double MAP_OFFSET = 1.01 / 128.0;
    private CubeGeometry() {}

    public enum Face {
        NORTH( 0, 0,-1, -1, 0, 0,  0, 1, 0),
        SOUTH( 0, 0, 1,  1, 0, 0,  0, 1, 0),
        EAST ( 1, 0, 0,  0, 0,-1,  0, 1, 0),
        WEST (-1, 0, 0,  0, 0, 1,  0, 1, 0),
        UP   ( 0, 1, 0,  1, 0, 0,  0, 0,-1),
        DOWN ( 0,-1, 0,  1, 0, 0,  0, 0, 1);

        private final int[] n,r,u;
        Face(int nx,int ny,int nz,int rx,int ry,int rz,int ux,int uy,int uz) {
            n=new int[]{nx,ny,nz};r=new int[]{rx,ry,rz};u=new int[]{ux,uy,uz};
        }
        public int nx(){return n[0];}public int ny(){return n[1];}public int nz(){return n[2];}
        public int rx(){return r[0];}public int ry(){return r[1];}public int rz(){return r[2];}
        public int ux(){return u[0];}public int uy(){return u[1];}public int uz(){return u[2];}
        public double[] project(double x,double y,double z){
            return new double[]{x*r[0]+y*r[1]+z*r[2],x*u[0]+y*u[1]+z*u[2],x*n[0]+y*n[1]+z*n[2]};
        }
        public Mesh.Vec toWorld(Mesh.Vec v) {
            return new Mesh.Vec(v.x()*r[0]+v.y()*u[0]+v.z()*n[0],
                    v.x()*r[1]+v.y()*u[1]+v.z()*n[1],v.x()*r[2]+v.y()*u[2]+v.z()*n[2]);
        }
        /** Width and height of a given map face, in tiles. X=width, Y=height, Z=depth. */
        public int tileWidth(int width,int height,int depth) {
            return Math.abs(r[0])*width + Math.abs(r[1])*height + Math.abs(r[2])*depth;
        }
        public int tileHeight(int width,int height,int depth) {
            return Math.abs(u[0])*width + Math.abs(u[1])*height + Math.abs(u[2])*depth;
        }
        public int thickness(int width,int height,int depth) {
            return Math.abs(n[0])*width + Math.abs(n[1])*height + Math.abs(n[2])*depth;
        }
        /** Index of the solid support block, relative to the rectangular prism's minimum corner. */
        public int[] support(int width,int height,int depth,int tileX,int tileY) {
            int fw=tileWidth(width,height,depth),fh=tileHeight(width,height,depth);
            if(width<1||height<1||depth<1||tileX<0||tileX>=fw||tileY<0||tileY>=fh)
                throw new IllegalArgumentException("Invalid rectangular face tile");
            int[] dimensions={width,height,depth},result=new int[3];
            for(int axis=0;axis<3;axis++) {
                if(n[axis]!=0)result[axis]=n[axis]>0?dimensions[axis]-1:0;
                else if(r[axis]!=0)result[axis]=r[axis]>0?tileX:dimensions[axis]-1-tileX;
                else result[axis]=u[axis]>0?tileY:dimensions[axis]-1-tileY;
            }
            return result;
        }
        /** Converts the shared X/Y/Z model into the oriented face coordinate system. */
        public Mesh orient(Mesh mesh) {
            List<Mesh.Triangle> tris=new ArrayList<>(mesh.triangles().size());
            for(Mesh.Triangle tri:mesh.triangles()) {
                tris.add(new Mesh.Triangle(orient(tri.a()),orient(tri.b()),orient(tri.c()),tri.rgb()));
            }
            return new Mesh(tris);
        }
        private Mesh.Vec orient(Mesh.Vec v){
            double[] q=project(v.x(),v.y(),v.z());
            return new Mesh.Vec(q[0],q[1],q[2]);
        }
    }
}
