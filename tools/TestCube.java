import dev.openai.holomap.*;
import dev.openai.holomap.CubeGeometry.Face;
import java.util.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** Standalone test; requires only JDK 21, no Minecraft or Bukkit installation. */
public class TestCube {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static int changed(int[] a,int[] b){int n=0;for(int i=0;i<a.length;i++)if(a[i]!=b[i])n++;return n;}
    public static void main(String[] args) throws Exception {
        for(int size=1;size<=3;size++){
            Set<String> global=new HashSet<>();
            for(Face face:Face.values()) {
                check(face.rx()*face.ux()+face.ry()*face.uy()+face.rz()*face.uz()==0,"right and up orthogonal");
                int nx=face.ry()*face.uz()-face.rz()*face.uy();
                int ny=face.rz()*face.ux()-face.rx()*face.uz();
                int nz=face.rx()*face.uy()-face.ry()*face.ux();
                check(nx==face.nx()&&ny==face.ny()&&nz==face.nz(),"right cross up must be the outward normal: "+face);
                Set<String> local=new HashSet<>();
                for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
                    int[] p=face.support(size,size,size,x,y);
                    check(p[0]>=0&&p[1]>=0&&p[2]>=0&&p[0]<size&&p[1]<size&&p[2]<size,"index out of bounds");
                    check(local.add(Arrays.toString(p)),"two tiles map to same support: "+face);
                    check(global.add(face+Arrays.toString(p)),"frame reused");
                    check((face.nx()==0 || p[0]==(face.nx()>0?size-1:0)) &&
                          (face.ny()==0 || p[1]==(face.ny()>0?size-1:0)) &&
                          (face.nz()==0 || p[2]==(face.nz()>0?size-1:0)),"not on correct cube face");
                }
                check(local.size()==size*size,"incomplete face "+face);
            }
            check(global.size()==6*size*size,"wrong total cube faces");
        }
        Mesh mesh=Mesh.demo().get(0);
        int W=180,H=180;
        BufferedImage sheet=new BufferedImage(3*W,2*(H+25),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=sheet.createGraphics();
        g.setFont(new Font("SansSerif",Font.BOLD,16));
        int i=0;
        for(Face face:new Face[]{Face.SOUTH,Face.NORTH,Face.EAST,Face.WEST,Face.UP,Face.DOWN}){
            Mesh projected=face.orient(mesh);
            int[] center=PerspectiveRaster.render(projected,W,H,0.0,0.0,2.0,1,1,.82,.5);
            int[] side=PerspectiveRaster.render(projected,W,H,.55,.25,2.0,1,1,.82,.5);
            check(changed(center,side)>500,"no viewpoint response for "+face);
            long foreground=Arrays.stream(center).filter(c->c!=PerspectiveRaster.TRANSPARENT).count();
            check(foreground>40,"no visible model on "+face);
            int xo=(i%3)*W,yo=(i/3)*(H+25);
            for(int y=0;y<H;y++)for(int x=0;x<W;x++)sheet.setRGB(xo+x,yo+y,center[y*W+x]);
            g.setColor(Color.WHITE);g.fillRect(xo,yo+H,W,25);
            g.setColor(Color.BLACK);g.drawString(face.name(),xo+10,yo+H+18);
            System.out.printf("%s: pixels=%d, movement-dependent-pixels=%d%n",face,foreground,changed(center,side));
            i++;
        }
        g.dispose();
        File out=new File(args.length>0?args[0]:"cube-six-faces.png");
        ImageIO.write(sheet,"png",out);
        System.out.println("PASS: 6 cube faces, support mapping 1-3, different views, projection output => "+out);
    }
}
