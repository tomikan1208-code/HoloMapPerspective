package dev.openai.holomap;

/** Uniform world-space scaling that fits the actual model bounds in all three axes. */
public final class ModelFit {
    private ModelFit() {}
    public static double size(double width,double height,double depth,double x,double y,double z,double margin) {
        double fit=Double.POSITIVE_INFINITY;
        if(x>1e-8)fit=Math.min(fit,width/x);
        if(y>1e-8)fit=Math.min(fit,height/y);
        if(z>1e-8)fit=Math.min(fit,depth/z);
        if(!Double.isFinite(fit))fit=Math.min(width,Math.min(height,depth));
        return fit*Math.max(.01,Math.min(1,margin));
    }
}
