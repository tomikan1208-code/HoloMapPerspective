package dev.openai.holomap;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;

/** Source-only test: runs without Bukkit/Paper dependency. */
public class TestRaster {
    public static void main(String[] args) throws Exception {
        Mesh model=ObjLoader.read(Path.of(args[0]),6000).normalized();
        int[] left=PerspectiveRaster.render(model,128,128,-0.65,0,2.5,1,1,.82,.75);
        int[] right=PerspectiveRaster.render(model,128,128,0.65,0,2.5,1,1,.82,.75);
        int changed=0,modelPixels=0;
        for(int i=0;i<left.length;i++){
            if(left[i]!=right[i])changed++;
            if(left[i]!=PerspectiveRaster.TRANSPARENT)modelPixels++;
        }
        if(modelPixels<100)throw new AssertionError("The 3D mesh was not rendered");
        if(changed<100)throw new AssertionError("Perspective did not change enough: "+changed);
        BufferedImage demo=new BufferedImage(128*3,128,BufferedImage.TYPE_INT_RGB);
        double[] viewpoints={-.65,0,.65};
        for(int n=0;n<3;n++){
            int[] p=PerspectiveRaster.render(model,128,128,viewpoints[n],0,2.5,1,1,.82,.75);
            demo.setRGB(n*128,0,128,128,p,0,128);
        }
        Path out=Path.of(args[1]);Files.createDirectories(out.getParent());ImageIO.write(demo,"PNG",out.toFile());
        System.out.println("Raster test PASSED: colored="+modelPixels+", changed="+changed+". Wrote "+out);
    }
}
