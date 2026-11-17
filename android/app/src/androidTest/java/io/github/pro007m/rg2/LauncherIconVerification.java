package io.github.pro007m.rg2;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;

/** Actual installed Android resources rendered by Android, with two launcher masks. */
final class LauncherIconVerification {
    private static final int EDGE = 256;
    static JSONObject verify(Context context, File output, JSONArray screenshots) throws Exception {
        if (Build.VERSION.SDK_INT < 33) throw new AssertionError("Themed icon QA requires API33+");
        Drawable loaded = context.getPackageManager().getApplicationIcon(context.getPackageName());
        if (!(loaded instanceof AdaptiveIconDrawable)) throw new AssertionError("Installed app icon is not adaptive");
        AdaptiveIconDrawable icon = (AdaptiveIconDrawable) loaded;
        icon.setBounds(0, 0, EDGE, EDGE);
        Bitmap platform = image(); icon.draw(new Canvas(platform));
        save(platform, "14-launcher-native-default", output, screenshots);
        Drawable background = icon.getBackground();
        if (!(background instanceof ColorDrawable) || ((ColorDrawable) background).getColor() != Color.rgb(32,36,46)) throw new AssertionError("Expected one solid graphite launcher background");
        Bitmap foreground = layer(icon.getForeground());
        if (Color.alpha(foreground.getPixel(0,0)) != 0 || Color.alpha(foreground.getPixel(EDGE-1,0)) != 0 || Color.alpha(foreground.getPixel(0,EDGE-1)) != 0 || Color.alpha(foreground.getPixel(EDGE-1,EDGE-1)) != 0)
            throw new AssertionError("Adaptive foreground contains a baked background");
        Path circle = new Path(); circle.addCircle(EDGE/2f, EDGE/2f, EDGE/2f, Path.Direction.CW);
        Path squircle = new Path();
        for (int i=0;i<=720;i++) { double angle=2*Math.PI*i/720, c=Math.cos(angle), s=Math.sin(angle);float x=(float)(EDGE/2d+EDGE/2d*Math.copySign(Math.sqrt(Math.abs(c)),c)),y=(float)(EDGE/2d+EDGE/2d*Math.copySign(Math.sqrt(Math.abs(s)),s));if(i==0)squircle.moveTo(x,y);else squircle.lineTo(x,y); } squircle.close();
        int outsideCircle = outside(foreground,circle), outsideSquircle = outside(foreground,squircle);
        if(outsideCircle != 0 || outsideSquircle != 0) throw new AssertionError("A launcher mask clips the cube");
        save(masked(foreground,circle,Color.rgb(32,36,46)),"15-launcher-circle",output,screenshots);
        save(masked(foreground,squircle,Color.rgb(32,36,46)),"16-launcher-squircle",output,screenshots);
        Drawable monochrome = icon.getMonochrome();
        if(monochrome == null) throw new AssertionError("The installed API33+ icon has no monochrome resource");
        monochrome = monochrome.mutate();monochrome.setTint(Color.rgb(176,202,255));Bitmap dark=layer(monochrome);
        if(outside(dark,circle) != 0) throw new AssertionError("The themed glyph is clipped");
        save(masked(dark,circle,Color.rgb(38,50,70)),"17-launcher-themed-dark",output,screenshots);
        monochrome.setTint(Color.rgb(34,60,90));save(masked(layer(monochrome),squircle,Color.rgb(219,231,255)),"18-launcher-themed-light",output,screenshots);
        return new JSONObject().put("passed",true).put("api",Build.VERSION.SDK_INT).put("actualInstalledAdaptiveResource",true)
            .put("backgroundCount",1).put("foregroundCornersTransparent",true).put("circleClippedPixels",outsideCircle).put("squircleClippedPixels",outsideSquircle)
            .put("monochromeAvailable",true).put("rendering","Android PackageManager/AdaptiveIconDrawable/Canvas; circle and squircle masks; two themed tints");
    }
    private static Bitmap image(){return Bitmap.createBitmap(EDGE,EDGE,Bitmap.Config.ARGB_8888);}
    private static Bitmap layer(Drawable drawable){Bitmap result=image();int inset=Math.round(EDGE*AdaptiveIconDrawable.getExtraInsetFraction());drawable.setBounds(-inset,-inset,EDGE+inset,EDGE+inset);drawable.draw(new Canvas(result));return result;}
    private static int outside(Bitmap bitmap,Path mask){Region region=new Region();region.setPath(mask,new Region(new Rect(0,0,EDGE,EDGE)));int count=0;for(int y=0;y<EDGE;y++)for(int x=0;x<EDGE;x++)if(Color.alpha(bitmap.getPixel(x,y))>16&&!region.contains(x,y))count++;return count;}
    private static Bitmap masked(Bitmap foreground,Path mask,int color){Bitmap result=image();Canvas canvas=new Canvas(result);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(color);canvas.drawPath(mask,paint);canvas.save();canvas.clipPath(mask);canvas.drawBitmap(foreground,0,0,null);canvas.restore();return result;}
    private static void save(Bitmap image,String name,File output,JSONArray screenshots)throws Exception{try(FileOutputStream stream=new FileOutputStream(new File(output,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,stream);}screenshots.put(new JSONObject().put("name",name).put("kind","installed-native-icon-render"));}
}
