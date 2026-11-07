package dev.openai.holomap;

/** Short prediction from accepted positions, with a smaller lead after a sharp turn. */
public final class ViewMotion {
    private ViewMotion() {}
    public static double fraction(long previous,long latest,long now,double distanceSquared) {
        return fraction(previous,latest,now,distanceSquared,false);
    }
    public static double fraction(long previous,long latest,long now,double distanceSquared,boolean abrupt) {
        long duration=latest-previous;
        if(duration<8_000_000L||duration>200_000_000L||distanceSquared>4)return 0;
        long limit=Math.min(duration,abrupt?12_000_000L:50_000_000L);
        return Math.max(0,Math.min(limit,now-latest)/(double)duration);
    }
    public static boolean sharpTurn(double x,double y,double z,double previousX,double previousY,double previousZ) {
        double currentLength=x*x+y*y+z*z;
        double previousLength=previousX*previousX+previousY*previousY+previousZ*previousZ;
        if(currentLength<.0064||previousLength<.0064)return false;
        return x*previousX+y*previousY+z*previousZ<.45*Math.sqrt(currentLength*previousLength);
    }
    public static double predict(double previous,double latest,double fraction) {
        return latest+(latest-previous)*fraction;
    }
}
