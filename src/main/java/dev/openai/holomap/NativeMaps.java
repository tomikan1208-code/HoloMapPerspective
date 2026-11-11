package dev.openai.holomap;

import org.bukkit.entity.Player;
import org.bukkit.map.MapView;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Paper 26.3 packet adapter. Constructed/bound on the server thread; delivery only touches Netty. */
public final class NativeMaps {
    private final Constructor<?> mapId,patch,mapPacket,bundle;
    private final Class<?> packetClass;
    private final Field worldMap,dirtyData;
    private final Method getHandle,getHoldingPlayer;
    private record DeliveryTiming(long diffNanos,long handoffNanos,int patchCount,long pixelBytes) {}
    private volatile DeliveryTiming lastDelivery;
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
    public String status() {
        DeliveryTiming timing=lastDelivery;
        if(timing==null)return "Waiting for first native map update";
        return String.format(Locale.ROOT,"last update: diff %.1f ms, packet handoff %.1f ms, %d patches, %.1f KiB raw pixels",
                timing.diffNanos()/1e6,timing.handoffNanos()/1e6,timing.patchCount(),timing.pixelBytes()/1024.0);
    }
    public GpuEngine.Delivery capture(Player player,List<MapPatches.Tile> tiles) {
        try {
            Object handle=player.getClass().getMethod("getHandle").invoke(player);
            Object listener=handle.getClass().getField("connection").get(handle);
            if(listener==null)return null;
            Method send=listener.getClass().getMethod("send",packetClass);
            Object connection=listener.getClass().getField("connection").get(listener);
            Object channel=connection.getClass().getField("channel").get(connection);
            Class<?> channelType=Class.forName("io.netty.channel.Channel");
            Method active=channelType.getMethod("isActive");
            Method writable=channelType.getMethod("isWritable");
            return delivery(tiles,packet->{
                try{send.invoke(listener,packet);}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}
            },()->{
                try{return channel!=null&&(boolean)active.invoke(channel)&&(boolean)writable.invoke(channel);}
                catch(ReflectiveOperationException ex){return false;}
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
        return delivery(tiles,receiver,()->true);
    }
    private GpuEngine.Delivery delivery(List<MapPatches.Tile> tiles,Consumer<Object> receiver,BooleanSupplier writable) {
        MapPatches changes=new MapPatches(tiles);
        return new GpuEngine.Delivery() {
            private final AtomicBoolean reset=new AtomicBoolean();
            @Override public boolean ready(){return writable.getAsBoolean();}
            @Override public void reset(){reset.set(true);}
            @Override public boolean send(GpuEngine.Scene scene)throws Exception {
                long started=System.nanoTime();
                if(reset.getAndSet(false))changes.reset();
                List<MapPatches.Patch> patches=changes.update(scene.faces());
                long diffNanos=System.nanoTime()-started;
                long handoffNanos=0,pixelBytes=0;
                if(!patches.isEmpty()) {
                    for(MapPatches.Patch patch:patches)pixelBytes+=patch.pixels().length;
                    long handoffAt=System.nanoTime();
                    receiver.accept(packetBundle(patches));
                    handoffNanos=System.nanoTime()-handoffAt;
                }
                lastDelivery=new DeliveryTiming(diffNanos,handoffNanos,patches.size(),pixelBytes);
                return !patches.isEmpty();
            }
        };
    }
}
