package dev.openai.holomap;

import org.bukkit.entity.Player;
import org.bukkit.map.*;
import java.util.*;

/** Player-specific canvas, updated with only changed pixels. */
public final class TileRenderer extends MapRenderer {
    private final Display display;
    private final int tileX,tileY;
    private final Map<UUID,byte[]> previous=new HashMap<>();
    public TileRenderer(Display display,int tileX,int tileY) {
        super(true);this.display=display;this.tileX=tileX;this.tileY=tileY;
    }
    @Override @SuppressWarnings("deprecation")
    public void render(MapView view,MapCanvas canvas,Player player) {
        if(display.isStreaming(player.getUniqueId())){display.suppressVanillaUpdate(view,player);return;}
        byte[] all=display.frameFor(player);
        byte[] old=previous.get(player.getUniqueId());
        boolean first=old==null;
        if(first){old=new byte[128*128];previous.put(player.getUniqueId(),old);}
        int totalWidth=128*display.width;
        for(int y=0;y<128;y++)for(int x=0;x<128;x++) {
            int index=y*128+x,at=((display.height-1-tileY)*128+y)*totalWidth+tileX*128+x;
            byte color=all[at];
            if(first||old[index]!=color){canvas.setPixel(x,y,color);old[index]=color;}
        }
    }
    public void clearPlayer(UUID uuid){previous.remove(uuid);}
}
