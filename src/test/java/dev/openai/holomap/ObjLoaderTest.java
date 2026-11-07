package dev.openai.holomap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ObjLoaderTest {
    @TempDir Path directory;

    @Test void triangulatesNegativeIndicesAndLoadsDiffuseMaterialsWithInlineComments() throws Exception {
        Files.writeString(directory.resolve("colors.mtl"),"newmtl red\nKd 1 0 0\n");
        Path obj=directory.resolve("model.obj");
        Files.writeString(obj,"mtllib colors.mtl\nv -1 -1 0\nv 1 -1 0\nv 1 1 0\nv -1 1 0\nusemtl red\nf -4 -3 -2 -1 # quad\n");
        Mesh mesh=ObjLoader.read(obj,2);
        assertEquals(2,mesh.triangles().size());
        assertEquals(0xFF0000,mesh.triangles().getFirst().rgb());
        assertThrows(IOException.class,()->ObjLoader.read(obj,1));
    }

    @Test void rejectsInvalidIndicesAndNonFiniteVertices() throws Exception {
        Path obj=directory.resolve("invalid.obj");
        for(String source:List.of("v NaN 0 0\nf 1 1 1\n","v Infinity 0 0\nf 1 1 1\n","v 0 0 0\nf 1 2 3\n")) {
            Files.writeString(obj,source);
            assertThrows(IOException.class,()->ObjLoader.read(obj,100));
        }
    }

    @Test void sharedNormalizationFitsWideModelsAndPreservesFrameTranslation() {
        Mesh first=new Mesh(List.of(new Mesh.Triangle(new Mesh.Vec(-5,0,0),
                new Mesh.Vec(5,0,0),new Mesh.Vec(0,1,0),0xFFFFFF)));
        Mesh second=new Mesh(List.of(new Mesh.Triangle(new Mesh.Vec(-5,1,0),
                new Mesh.Vec(5,1,0),new Mesh.Vec(0,2,0),0xFFFFFF)));
        List<Mesh> frames=Mesh.normalizedSequence(List.of(first,second));
        assertEquals(.1,frames.get(1).triangles().getFirst().a().y()-frames.get(0).triangles().getFirst().a().y(),1e-9);
        for(Mesh mesh:frames)for(Mesh.Triangle t:mesh.triangles())
            for(Mesh.Vec v:List.of(t.a(),t.b(),t.c())) {
                assertTrue(Math.abs(v.x())<=.5);
                assertTrue(Math.abs(v.y())<=.5);
                assertTrue(Math.abs(v.z())<=.5);
            }
    }
}
