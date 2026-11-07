package dev.openai.holomap;

/** Bounded dead reckoning between the 20 Hz server position samples. */
public final class ViewMotion {
    private ViewMotion() {}
    public static double fraction(long previous,long latest,long now,double distanceSquared) {
        long duration=latest-previous;
        if(duration<10_000_000L||duration>200_000_000L||distanceSquared>4)return 0;
        return Math.max(0,Math.min(1,(now-latest)/(double)duration));
    }
    public static double predict(double previous,double latest,double fraction) {
        return latest+(latest-previous)*fraction;
    }
}
