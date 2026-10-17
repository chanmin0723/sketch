import com.chanmin.sketch.SketchCore;
import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

public class DesktopTest {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        int w = src.getWidth(), h = src.getHeight();
        int max = 3000;
        if (Math.max(w, h) > max) {
            double s = (double) max / Math.max(w, h);
            int nw = (int) (w * s), nh = (int) (h * s);
            BufferedImage r = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = r.createGraphics();
            g.drawImage(src.getScaledInstance(nw, nh, Image.SCALE_AREA_AVERAGING), 0, 0, null);
            g.dispose(); src = r; w = nw; h = nh;
        }
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        long t0 = System.currentTimeMillis();
        int[] out = SketchCore.process(px, w, h, null);
        System.out.println("process ms: " + (System.currentTimeMillis() - t0) + " size " + w + "x" + h);
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        o.setRGB(0, 0, w, h, out, 0, w);
        ImageIO.write(o, "png", new File(a[1]));
    }
}
