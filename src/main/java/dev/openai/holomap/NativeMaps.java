package dev.openai.holomap;

import org.bukkit.entity.Player;
import org.bukkit.map.MapView;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Paper 26.3 packet adapter. Constructed/bound on the server thread; delivery only touches Netty. */
public final class NativeMaps {
    private final Constructor<?> mapId,patch,mapPacket,bundle;
    private final Class<?> packetClass;
    private final Field worldMap,dirtyData;
    private final Method getHandle,getHoldingPlayer;
    public NativeMaps()throws ReflectiveOperationException {
        Class<?> id=Class.forName("net.minecraft.world.level.saveddata.maps.MapId");
        Class<?> colors=Class.forName("net.minecraft.world.level.saveddata.maps.MapItemSavedData$MapPatch");
        packetClass=Class.forName("net.minecraft.network.protocol.Packet");
        mapId=id.getConstructor(int.class);
        patch=colors.getConstructor(int.class,int.class,int.class,int.class,byte[].class);
        mapPacket=Class.forName("net.minecraft.network.protocol.game.ClientboundMapItemDataPacket")
                .getConstructor(id,byte.class,boolean.class,Collection.class,colors);
        bundle=Class.forName("net.minecraft.network.protocol.game.ClientboundBundlePacket").getConstructor(Iterable.class);
        worldMap=Class.forName("org.bukkit.craftbukkit.map.CraftMapView").getDeclaredField("worldMap");worldMap.setAccessible(true);
        getHandle=Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
        getHoldingPlayer=Class.forName("net.minecraft.world.level.saveddata.maps.MapItemSavedData")
                .getMethod("getHoldingPlayer",Class.forName("net.minecraft.world.entity.player.Player"));
        dirtyData=Class.forName("net.minecraft.world.level.saveddata.maps.MapItemSavedData$HoldingPlayer").getDeclaredField("dirtyData");
        dirtyData.setAccessible(true);
    }
    public Object packetBundle(List<MapPatches.Patch> patches)throws ReflectiveOperationException {
        List<Object> packets=new ArrayList<>(patches.size());
        for(MapPatches.Patch value:patches)packets.add(mapPacket.newInstance(mapId.newInstance(value.mapId()),(byte)0,true,
                null,patch.newInstance(value.x(),value.y(),value.width(),value.height(),value.pixels())));
        return bundle.newInstance(packets);
    }
    public GpuEngine.Delivery capture(Player player,List<MapPatches.Tile> tiles) {
        try {
            Object handle=player.getClass().getMethod("getHandle").invoke(player);
            Object listener=handle.getClass().getField("connection").get(handle);
            Method send=listener.getClass().getMethod("send",packetClass);
            return delivery(tiles,packet->{
                try{send.invoke(listener,packet);}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}
            });
        }catch(ReflectiveOperationException ex){return null;}
    }
    /** Called by MapRenderer on the server thread, before Paper constructs its automatic map patch.
     * A stale Bukkit canvas must never overwrite the private 60 Hz stream with blank pixels. */
    public void suppressVanillaUpdate(MapView view,Player player) {
        try{suppress(view,getHandle.invoke(player));}
        catch(ReflectiveOperationException ex){throw new IllegalStateException("Cannot suppress stale map canvas",ex);}
    }
    private void suppress(MapView view,Object handle)throws ReflectiveOperationException {
        Object holder=getHoldingPlayer.invoke(worldMap.get(view),handle);dirtyData.setBoolean(holder,false);
    }
    /** Also used by the opt-in real-Paper verifier with an in-memory packet receiver. */
    public GpuEngine.Delivery delivery(List<MapPatches.Tile> tiles,Consumer<Object> receiver) {
        MapPatches changes=new MapPatches(tiles);
        return new GpuEngine.Delivery() {
            private final AtomicBoolean reset=new AtomicBoolean();
            @Override public void reset(){reset.set(true);}
            @Override public boolean send(GpuEngine.Scene scene)throws Exception {
                if(reset.getAndSet(false))changes.reset();
                List<MapPatches.Patch> patches=changes.update(scene.faces());
                if(patches.isEmpty())return false;
                receiver.accept(packetBundle(patches));return true;
            }
        };
    }
}
