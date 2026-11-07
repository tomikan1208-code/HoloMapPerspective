package dev.openai.holomap;

import java.util.*;

/** Per-viewer map differences; independent of Bukkit and safe on the GPU delivery thread. */
public final class MapPatches {
    public record Tile(int mapId,String face,int width,int height,int x,int y) {}
    public record Patch(int mapId,int x,int y,int width,int height,byte[] pixels) {}
    private final List<Tile> tiles;
    private final Map<Integer,byte[]> previous=new HashMap<>();
    public MapPatches(List<Tile> tiles){this.tiles=List.copyOf(tiles);}
    public void reset(){previous.clear();}
    public List<Patch> update(Map<String,byte[]> faces) {
        List<Patch> patches=new ArrayList<>();
        for(Tile tile:tiles) {
            byte[] face=faces.get(tile.face());if(face==null)continue;
            if(face.length!=tile.width()*tile.height()*16384)throw new IllegalArgumentException("Wrong face size");
            byte[] old=previous.get(tile.mapId());
            boolean first=old==null;
            if(first){old=new byte[16384];previous.put(tile.mapId(),old);}
            int left=128,right=-1,top=128,bottom=-1,stride=tile.width()*128;
            for(int y=0;y<128;y++) {
                int start=((tile.height()-1-tile.y())*128+y)*stride+tile.x()*128;
                if(!first&&Arrays.equals(face,start,start+128,old,y*128,y*128+128))continue;
                for(int x=0;x<128;x++)if(first||face[start+x]!=old[y*128+x]) {
                    left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
                }
            }
            if(right<0)continue;
            int width=right-left+1,height=bottom-top+1;
            byte[] data=new byte[width*height];
            for(int row=0;row<height;row++) {
                int start=((tile.height()-1-tile.y())*128+top+row)*stride+tile.x()*128+left;
                System.arraycopy(face,start,data,row*width,width);
                System.arraycopy(face,start,old,(top+row)*128+left,width);
            }
            patches.add(new Patch(tile.mapId(),left,top,width,height,data));
        }
        return patches;
    }
}
