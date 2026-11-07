import dev.openai.holomap.*;
import dev.openai.holomap.CubeGeometry.Face;
import java.util.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** Pure-Java test for all six rectangular prism faces and perspective motion. */
public final class TestPrism {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static long diff(int[] a,int[] b){long n=0;for(int i=0;i<a.length;i++)if(a[i]!=b[i])n++;return n;}
    private static String point(int[] p){return Arrays.toString(p);}
    public static void main(String[] args) throws Exception {
        int combos=0;
        for(int w=1;w<=5;w++)for(int h=1;h<=5;h++)for(int d=1;d<=5;d++) {
            int total=0;
            for(Face f:Face.values()) {
                int fw=f.tileWidth(w,h,d),fh=f.tileHeight(w,h,d);
                check(fw>0&&fh>0,"bad face size");
                check(fw==Math.abs(f.rx())*w+Math.abs(f.ry())*h+Math.abs(f.rz())*d,"width mismatch");
                Set<String> seen=new HashSet<>();
                for(int ty=0;ty<fh;ty++)for(int tx=0;tx<fw;tx++) {
                    int[] p=f.support(w,h,d,tx,ty);
                    check(p[0]>=0&&p[0]<w&&p[1]>=0&&p[1]<h&&p[2]>=0&&p[2]<d,"invalid index "+f+" "+point(p));
                    check(seen.add(point(p)),"overlapping tiles on face "+f);
                    check((f.nx()==0||p[0]==(f.nx()>0?w-1:0))&&
                            (f.ny()==0||p[1]==(f.ny()>0?h-1:0))&&
                            (f.nz()==0||p[2]==(f.nz()>0?d-1:0)),"support on wrong side: "+f);
                }
                check(seen.size()==fw*fh,"missing tiles");
                total+=seen.size();
            }
            check(total==2*(w*h+w*d+h*d),"wrong map count for "+w+"x"+h+"x"+d);
            combos++;
        }
        int w=2,h=3,d=4;
        BufferedImage sheet=new BufferedImage(3*240,2*240,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=sheet.createGraphics();g.setFont(new Font("SansSerif",Font.BOLD,16));
        Mesh demo=Mesh.demo().get(0);
        int i=0;
        for(Face f:new Face[]{Face.SOUTH,Face.NORTH,Face.EAST,Face.WEST,Face.UP,Face.DOWN}) {
            int fw=f.tileWidth(w,h,d),fh=f.tileHeight(w,h,d),thickness=f.thickness(w,h,d);
            double scale=Math.min(w,Math.min(h,d))*.82/fh;
            Mesh oriented=f.orient(demo);
            int pw=fw*42,ph=fh*42;
            double eyeZ=thickness*.5+2.0;
            int[] a=PerspectiveRaster.render(oriented,pw,ph,0,0,eyeZ,fw,fh,scale,thickness*.5);
            int[] b=PerspectiveRaster.render(oriented,pw,ph,.55,.25,eyeZ,fw,fh,scale,thickness*.5);
            long foreground=Arrays.stream(a).filter(c->c!=PerspectiveRaster.TRANSPARENT).count();
            check(foreground>30,"no geometry drawn on "+f);
            check(diff(a,b)>30,"no perspective change on "+f);
            BufferedImage frame=new BufferedImage(pw,ph,BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<ph;y++)for(int x=0;x<pw;x++)frame.setRGB(x,y,a[y*pw+x]);
            int col=i%3,row=i/3,tx=col*240+(240-pw)/2,ty=row*240+(220-ph)/2;
            g.setColor(new Color(0xDDE3E9));g.fillRect(col*240,row*240,240,240);
            g.drawImage(frame,tx,ty,null);
            g.setColor(Color.BLACK);g.drawString(f.name()+" ("+fw+"×"+fh+")",col*240+16,row*240+234);
            System.out.println(f+" face "+fw+"x"+fh+", foreground="+foreground+", changed="+diff(a,b));
            i++;
        }
        g.dispose();
        File out=new File(args.length>0?args[0]:"prism-six-faces.png");ImageIO.write(sheet,"png",out);
        System.out.println("PASS: "+combos+" non-square/regular geometries; "+w+"x"+h+"x"+d+" = 52 maps; six perspective faces; "+out);
    }
}
