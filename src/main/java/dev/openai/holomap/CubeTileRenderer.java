package dev.openai.holomap;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A specific 128x128 tile on an outward face of a 6-face holographic cube. */
public final class CubeTileRenderer extends MapRenderer {
    private final CubeDisplay cube;
    private final CubeGeometry.Face face;
    private final int tileX,tileY;
    private final Map<UUID,byte[]> previous=new HashMap<>();

    public CubeTileRenderer(CubeDisplay cube,CubeGeometry.Face face,int tileX,int tileY){
        super(true);this.cube=cube;this.face=face;this.tileX=tileX;this.tileY=tileY;
    }
    @Override @SuppressWarnings("deprecation")
    public void render(MapView view,MapCanvas canvas,Player player) {
        if(cube.isStreaming(player.getUniqueId())){cube.suppressVanillaUpdate(view,player);return;}
        byte[] all=cube.frameFor(player,face);
        UUID uuid=player.getUniqueId();
        byte[] old=previous.get(uuid);
        boolean first=(old==null);
        if(first){old=new byte[128*128];previous.put(uuid,old);}
        int faceW=face.tileWidth(cube.width,cube.height,cube.depth);
        int faceH=face.tileHeight(cube.width,cube.height,cube.depth);
        int w=faceW*128;
        for(int y=0;y<128;y++)for(int x=0;x<128;x++) {
            int pos=y*128+x;
            // Map canvases are top-to-bottom; tileY is bottom-to-top.
            int at=((faceH-1-tileY)*128+y)*w+tileX*128+x;
            byte color=all[at];
            if(first || color!=old[pos]){canvas.setPixel(x,y,color);old[pos]=color;}
        }
    }
    public void clearPlayer(UUID id){previous.remove(id);}
}
