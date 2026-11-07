package dev.openai.holomap;

import dev.openai.holomap.CubeGeometry.Face;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GeometryRasterTest {
    @Test void all125PrismSizesCoverSixFacesWithoutDuplicateTiles() {
        for(int w=1;w<=5;w++)for(int h=1;h<=5;h++)for(int d=1;d<=5;d++) {
            int total=0;
            for(Face face:Face.values()) {
                Set<String> tiles=new HashSet<>();
                for(int y=0;y<face.tileHeight(w,h,d);y++)for(int x=0;x<face.tileWidth(w,h,d);x++) {
                    int[] p=face.support(w,h,d,x,y);
                    assertTrue(p[0]>=0&&p[0]<w&&p[1]>=0&&p[1]<h&&p[2]>=0&&p[2]<d);
                    assertTrue(tiles.add(Arrays.toString(p)),face.name());
                    if(face.nx()!=0)assertEquals(face.nx()>0?w-1:0,p[0]);
                    if(face.ny()!=0)assertEquals(face.ny()>0?h-1:0,p[1]);
                    if(face.nz()!=0)assertEquals(face.nz()>0?d-1:0,p[2]);
                }
                total+=tiles.size();
            }
            assertEquals(2*(w*h+w*d+h*d),total);
        }
    }

    @Test void faceBasesMatchVanillaMapsIncludingCeilingAndFloor() {
        double[][] expected={{-2,3,-5},{2,3,5},{-5,3,2},{5,3,-2},{2,-5,3},{2,5,-3}};
        int i=0;
        for(Face face:Face.values()) {
            assertArrayEquals(expected[i++],face.project(2,3,5));
            assertEquals(face.nx(),face.ry()*face.uz()-face.rz()*face.uy());
            assertEquals(face.ny(),face.rz()*face.ux()-face.rx()*face.uz());
            assertEquals(face.nz(),face.rx()*face.uy()-face.ry()*face.ux());
        }
    }

    @Test void emptyPixelsAreTransparentButBlackGeometryIsOpaque() {
        Mesh black=triangle(0,0);
        int[] pixels=PerspectiveRaster.render(black,128,128,0,0,2,1,1,.82,.5);
        assertEquals(PerspectiveRaster.TRANSPARENT,pixels[0]);
        assertEquals(0,pixels[64*128+64]);
        assertTrue(Arrays.stream(pixels).filter(p->p==0).count()>100);
        assertTrue(Arrays.stream(pixels).filter(p->p==PerspectiveRaster.TRANSPARENT).count()>100);
    }

    @Test void hiddenSideAndEmptyMeshAreCompletelyTransparent() {
        assertTrue(Arrays.stream(PerspectiveRaster.render(triangle(0,0xFFFFFF),32,32,0,0,-2,1,1,.82,.5))
                .allMatch(p->p==PerspectiveRaster.TRANSPARENT));
        assertTrue(Arrays.stream(PerspectiveRaster.render(new Mesh(List.of()),32,32,0,0,2,1,1,.82,.5))
                .allMatch(p->p==PerspectiveRaster.TRANSPARENT));
    }

    @Test void nearTriangleOccludesFarTriangleRegardlessOfSubmissionOrder() {
        Mesh.Triangle far=triangle(-.2,0xFF0000).triangles().getFirst();
        Mesh.Triangle near=triangle(.2,0x00FF00).triangles().getFirst();
        for(List<Mesh.Triangle> order:List.of(List.of(far,near),List.of(near,far))) {
            int rgb=PerspectiveRaster.render(new Mesh(order),128,128,0,0,2,1,1,.82,.5)[64*128+64];
            assertTrue((rgb&0x00FF00)>0);
            assertEquals(0,rgb&0xFF00FF);
        }
    }

    @Test void everyAngleAroundPrismAndBothPolesShowGeometry() {
        Mesh model=Mesh.demo().getFirst();
        int w=2,h=3,d=4;
        for(int angle=0;angle<360;angle+=5) {
            double radians=Math.toRadians(angle),eyeX=5*Math.sin(radians),eyeZ=5*Math.cos(radians);
            long visible=0;
            for(Face face:Face.values()) {
                double offset=face.thickness(w,h,d)*.5+CubeGeometry.MAP_OFFSET;
                double[] eye=face.project(eyeX-face.nx()*offset,-face.ny()*offset,eyeZ-face.nz()*offset);
                if(eye[2]<=.1)continue;
                int fw=face.tileWidth(w,h,d),fh=face.tileHeight(w,h,d);
                int[] pixels=PerspectiveRaster.render(face.orient(model),fw*64,fh*64,
                        eye[0],eye[1],eye[2],fw,fh,.82*Math.min(w,Math.min(h,d))/fh,offset);
                visible+=Arrays.stream(pixels).filter(p->p!=PerspectiveRaster.TRANSPARENT).count();
            }
            assertTrue(visible>50,"angle "+angle);
        }
        for(Face face:List.of(Face.UP,Face.DOWN))
            assertTrue(Arrays.stream(PerspectiveRaster.render(face.orient(model),128,128,0,0,3,1,1,.82,.5))
                    .filter(p->p!=PerspectiveRaster.TRANSPARENT).count()>50);
    }

    @Test void parallaxChangesAllSixFacesAndAnimationChangesShape() {
        for(Face face:Face.values()) {
            Mesh model=face.orient(Mesh.demo().getFirst());
            int[] left=PerspectiveRaster.render(model,128,128,-.55,.2,3,1,1,.82,.5);
            int[] right=PerspectiveRaster.render(model,128,128,.55,-.2,3,1,1,.82,.5);
            assertTrue(differences(left,right)>100,face.name());
        }
        int[] first=PerspectiveRaster.render(Mesh.demo().get(0),128,128,0,0,3,1,1,.82,.5);
        int[] later=PerspectiveRaster.render(Mesh.demo().get(10),128,128,0,0,3,1,1,.82,.5);
        assertTrue(differences(first,later)>100);
    }

    @Test void adjacentFacesShadeTheSameWorldTriangleIdentically() {
        Mesh triangle=new Mesh(List.of(new Mesh.Triangle(new Mesh.Vec(-.3,-.3,-.2),
                new Mesh.Vec(.3,-.3,.2),new Mesh.Vec(0,.3,0),0x8AD2E7)));
        Set<Integer> colors=new HashSet<>();
        for(Face face:List.of(Face.SOUTH,Face.NORTH,Face.EAST,Face.WEST)) {
            int[] pixels=PerspectiveRaster.render(face.orient(triangle),128,128,0,0,3,1,1,.82,.5,face);
            assertTrue(Arrays.stream(pixels).anyMatch(c->c!=PerspectiveRaster.TRANSPARENT));
            for(int pixel:pixels)if(pixel!=PerspectiveRaster.TRANSPARENT)colors.add(pixel);
        }
        assertEquals(1,colors.size(),"screen seams must not change a triangle's color");
    }

    private static Mesh triangle(double z,int rgb) {
        return new Mesh(List.of(new Mesh.Triangle(new Mesh.Vec(-.4,-.4,z),
                new Mesh.Vec(.4,-.4,z),new Mesh.Vec(0,.4,z),rgb)));
    }
    private static int differences(int[] a,int[] b) {
        int count=0;for(int i=0;i<a.length;i++)if(a[i]!=b[i])count++;return count;
    }
}
