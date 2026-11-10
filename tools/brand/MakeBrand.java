import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Draws the icons and banners of both apps, so the artwork is reproducible and has no photos in it.
 * Run from the repository root (tools/docker-build.sh's image has a JDK):
 *
 *   java -Djava.awt.headless=true -Duser.home=/tmp tools/brand/MakeBrand.java
 *
 * Writes home/app/src/main/res/{mipmap-xhdpi/ic_launcher.png, drawable-nodpi/banner.png} (Origin TV) and
 * live/app/src/main/res/drawable-nodpi/{icon.png, banner.png} (Live Indian TV). The font is the launcher's Outfit (OFL).
 */
public class MakeBrand {
    private static Font outfit;

    public static void main(String[] args) throws Exception {
        outfit = Font.createFont(Font.TRUETYPE_FONT, new File("home/app/src/main/res/font/outfit.ttf"));
        write(liveBanner(640, 360), "live/app/src/main/res/drawable-nodpi/banner.png");
        write(liveIcon(256), "live/app/src/main/res/drawable-nodpi/icon.png");
        write(originBanner(640, 360), "home/app/src/main/res/drawable-nodpi/banner.png");
        write(originIcon(192), "home/app/src/main/res/mipmap-xhdpi/ic_launcher.png");
    }

    // ---- Live Indian TV: a TV with an antenna, "Live Indian" and a red "TV" pill with a play mark, on deep violet ---

    static BufferedImage liveBanner(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = start(img);
        g.setPaint(new GradientPaint(0, 0, new Color(0x1A0B3D), w, h, new Color(0x3B0F5C)));
        g.fillRect(0, 0, w, h);
        glow(g, w * 0.5f, h * 0.55f, h * 0.9f, new Color(0x7B2CBF), 0.35f);
        liveMark(g, w / 2f, h * 0.56f, h * 0.68f);
        g.dispose();
        return img;
    }

    static BufferedImage liveIcon(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = start(img);
        g.setPaint(new GradientPaint(0, 0, new Color(0x1A0B3D), s, s, new Color(0x3B0F5C)));
        g.fill(new RoundRectangle2D.Float(0, 0, s, s, s * 0.22f, s * 0.22f));
        liveMark(g, s / 2f, s * 0.58f, s * 0.6f);
        g.dispose();
        return img;
    }

    /** The mark centred at (cx, cy), about `size` tall. */
    static void liveMark(Graphics2D g, float cx, float cy, float size) {
        float tvW = size * 1.45f, tvH = size * 0.95f;
        float x = cx - tvW / 2, y = cy - tvH / 2;
        Color orange = new Color(0xFF8A00), magenta = new Color(0xE0218A);
        // Antenna.
        g.setStroke(new BasicStroke(size * 0.045f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setPaint(orange);
        g.draw(new Line2D.Float(cx, y + size * 0.02f, cx - size * 0.2f, y - size * 0.2f));
        g.draw(new Line2D.Float(cx, y + size * 0.02f, cx + size * 0.2f, y - size * 0.2f));
        float dot = size * 0.07f;
        g.fill(new Ellipse2D.Float(cx - size * 0.2f - dot / 2, y - size * 0.2f - dot / 2, dot, dot));
        g.fill(new Ellipse2D.Float(cx + size * 0.2f - dot / 2, y - size * 0.2f - dot / 2, dot, dot));
        // Screen: dark glass with a warm outline.
        RoundRectangle2D screen = new RoundRectangle2D.Float(x, y, tvW, tvH, tvH * 0.38f, tvH * 0.38f);
        g.setPaint(new Color(0x12082A));
        g.fill(screen);
        g.setStroke(new BasicStroke(size * 0.06f));
        g.setPaint(new GradientPaint(x, y, orange, x + tvW, y + tvH, magenta));
        g.draw(screen);
        // "Live Indian", made smaller when needed to fit inside the screen.
        float wordPx = size * 0.30f;
        Shape word = text("Live Indian", wordPx, size * 0.012f);
        Rectangle2D wb = word.getBounds2D();
        float fit = (float) (tvW * 0.80 / wb.getWidth());
        if (fit < 1f) {
            word = text("Live Indian", wordPx * fit, size * 0.012f);
            wb = word.getBounds2D();
        }
        float wordY = y + tvH * 0.50f;
        fillAt(g, word, cx - (float) wb.getCenterX(), wordY - (float) wb.getMaxY(), Color.WHITE, size * 0.012f);
        // The red "TV" pill with a play triangle.
        Shape tv = text("TV", size * 0.27f, size * 0.01f);
        Rectangle2D tb = tv.getBounds2D();
        float play = size * 0.16f, gap = size * 0.06f;
        float pillW = (float) tb.getWidth() + gap + play + size * 0.2f, pillH = size * 0.32f;
        float px = cx - pillW / 2, py = wordY + size * 0.05f;
        g.setPaint(new GradientPaint(px, py, new Color(0xFF3D3D), px + pillW, py, new Color(0xE0218A)));
        g.fill(new RoundRectangle2D.Float(px, py, pillW, pillH, pillH, pillH));
        float tx = px + size * 0.1f - (float) tb.getX();
        float ty = py + pillH / 2 - (float) tb.getCenterY();
        fillAt(g, tv, tx, ty, Color.WHITE, size * 0.01f);
        float ax = px + size * 0.1f + (float) tb.getWidth() + gap, ay = py + pillH / 2;
        Path2D tri = new Path2D.Float();
        tri.moveTo(ax, ay - play / 2);
        tri.lineTo(ax + play * 0.87f, ay);
        tri.lineTo(ax, ay + play / 2);
        tri.closePath();
        g.setPaint(Color.WHITE);
        g.fill(tri);
    }

    // ---- Origin TV: a ring with a play mark, and the name, on midnight blue ---------------------------------------

    static BufferedImage originBanner(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = start(img);
        g.setPaint(new GradientPaint(0, 0, new Color(0x06121F), w, h, new Color(0x0B2A3F)));
        g.fillRect(0, 0, w, h);
        // Ring, the name and "TV", measured and centred as one group.
        float ring = h * 0.36f, gap = h * 0.1f, space = h * 0.06f;
        Shape name = text("Origin", h * 0.21f, h * 0.006f);
        Shape tv = text("TV", h * 0.21f, h * 0.006f);
        Rectangle2D nb = name.getBounds2D(), tb = tv.getBounds2D();
        float group = ring + gap + (float) nb.getWidth() + space + (float) tb.getWidth();
        float left = (w - group) / 2;
        glow(g, left + ring / 2, h * 0.5f, h * 0.7f, new Color(0x00B4D8), 0.28f);
        originRing(g, left + ring / 2, h * 0.5f, ring);
        float nx = left + ring + gap;
        float ny = h * 0.5f - (float) nb.getCenterY();
        fillAt(g, name, nx - (float) nb.getX(), ny, Color.WHITE, h * 0.006f);
        fillAt(g, tv, nx + (float) nb.getWidth() + space - (float) tb.getX(), ny, new Color(0x48CAE4), h * 0.006f);
        g.dispose();
        return img;
    }

    static BufferedImage originIcon(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = start(img);
        g.setPaint(new GradientPaint(0, 0, new Color(0x06121F), s, s, new Color(0x0B2A3F)));
        g.fill(new RoundRectangle2D.Float(0, 0, s, s, s * 0.22f, s * 0.22f));
        originRing(g, s / 2f, s / 2f, s * 0.62f);
        g.dispose();
        return img;
    }

    static void originRing(Graphics2D g, float cx, float cy, float d) {
        g.setStroke(new BasicStroke(d * 0.11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setPaint(new GradientPaint(cx - d / 2, cy - d / 2, new Color(0x48CAE4), cx + d / 2, cy + d / 2, new Color(0x7B5CFF)));
        g.draw(new Ellipse2D.Float(cx - d / 2, cy - d / 2, d, d));
        float p = d * 0.36f;
        Path2D tri = new Path2D.Float();
        tri.moveTo(cx - p * 0.38f, cy - p / 2);
        tri.lineTo(cx + p * 0.55f, cy);
        tri.lineTo(cx - p * 0.38f, cy + p / 2);
        tri.closePath();
        g.setPaint(Color.WHITE);
        g.fill(tri);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    static Graphics2D start(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /** A soft radial glow made of fading circles: no blur filter needed. */
    static void glow(Graphics2D g, float cx, float cy, float r, Color c, float strength) {
        for (int i = 24; i > 0; i--) {
            float rr = r * i / 24f;
            float a = strength * (1 - i / 24f) * 0.18f;
            g.setPaint(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(255 * Math.min(1f, a))));
            g.fill(new Ellipse2D.Float(cx - rr, cy - rr, rr * 2, rr * 2));
        }
    }

    /** The word's outline in Outfit; the variable font loads at its default weight, so fillAt thickens it. */
    static Shape text(String s, float px, float bold) {
        Font f = outfit.deriveFont(Font.PLAIN, px);
        GlyphVector gv = f.createGlyphVector(new FontRenderContext(null, true, true), s);
        return gv.getOutline();
    }

    static void fillAt(Graphics2D g, Shape shape, float x, float y, Color color, float bold) {
        Shape moved = AffineTransform.getTranslateInstance(x, y).createTransformedShape(shape);
        g.setPaint(color);
        g.fill(moved);
        g.setStroke(new BasicStroke(bold * 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(moved);
    }

    static void write(BufferedImage img, String path) throws Exception {
        File out = new File(path);
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + path + " " + img.getWidth() + "x" + img.getHeight());
    }
}
