/*
 * Vita LiveArea Editor
 *
 * Builds a complete sce_sys folder (icon0.png, pic0.png, livearea/contents/bg.png,
 * startup.png, template.xml) from your own art in the Vita LiveArea "ad0" style
 * (full-screen background, logo on top, optional credits text), converting every image to
 * the exact format the Vita expects:
 *
 *   icon0.png    128x128   8-bit indexed (256 colours), no alpha
 *   pic0.png     960x544   8-bit indexed (256 colours)
 *   bg.png       840x500   8-bit indexed (256 colours)
 *   startup.png  280x158   32-bit RGBA, logo on a transparent canvas
 *   template.xml style "ad0", startup.png at y=80, 560x316, optional credits text
 *
 * Run the GUI:     java -jar LiveAreaEditor.jar
 * Command line:    java -jar LiveAreaEditor.jar --help
 *
 * Needs Java 11 or newer. No other libraries.
 */

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class LiveAreaEditor {

    // ---- Vita LiveArea "ad0" style geometry ----
    static final int BG_W = 840, BG_H = 500;          // livearea/contents/bg.png
    static final int PIC_W = 960, PIC_H = 544;        // pic0.png
    static final int ICON = 128;                      // icon0.png
    static final int ST_W = 280, ST_H = 158;          // startup.png canvas
    static final int SHOWN_Y = 80;                    // template: <image y="80" ...>
    static final int SHOWN_W = 560, SHOWN_H = 316;    // template: width/height (2x the canvas)

    // =====================================================================
    //  Project model
    // =====================================================================
    static class Project {
        File bgFile, logoFile, iconFile, pic0File;
        BufferedImage bg, logo, icon, pic0;
        int logoWidth = 276;      // logo width inside the 280px startup canvas
        int logoY = 0;            // logo top inside the startup canvas
        int logoXOff = 0;         // logo left/right offset from centre, inside the startup canvas
        int screenY = SHOWN_Y;    // template y: where startup.png sits from the top of the page
        int focusX = 50, focusY = 50;   // crop focus for bg / pic0 (0..100)
        boolean logoOnPic0 = true;
        String credits = "";
        String name = "";          // project / game name -> export subfolder
        int textSize = 18, marginLeft = 20, marginBottom = 20;
        Color textColor = Color.WHITE;
        boolean shadow = true;

        Project copy() {
            Project c = new Project();
            c.bgFile = bgFile; c.logoFile = logoFile; c.iconFile = iconFile; c.pic0File = pic0File;
            c.bg = bg; c.logo = logo; c.icon = icon; c.pic0 = pic0;
            c.logoWidth = logoWidth; c.logoY = logoY; c.logoXOff = logoXOff; c.screenY = screenY; c.focusX = focusX; c.focusY = focusY;
            c.logoOnPic0 = logoOnPic0; c.credits = credits; c.name = name;
            c.textSize = textSize; c.marginLeft = marginLeft; c.marginBottom = marginBottom;
            c.textColor = textColor; c.shadow = shadow;
            return c;
        }

        void setImage(String kind, File f) throws IOException {
            BufferedImage img = f == null ? null : readImage(f);
            switch (kind) {
                case "bg": bgFile = f; bg = img; break;
                case "logo": logoFile = f; logo = img; break;
                case "icon": iconFile = f; icon = img; break;
                case "pic0": pic0File = f; pic0 = img; break;
                default: throw new IllegalArgumentException(kind);
            }
        }

        String validate() {
            if (bg == null) return "Pick a background art image first.";
            if (logo == null) return "Pick a logo image first (a transparent PNG works best).";
            if (icon == null) return "Pick an icon art image first.";
            return null;
        }

        void save(File f) throws IOException {
            Properties pr = new Properties();
            putPath(pr, "bg", bgFile); putPath(pr, "logo", logoFile);
            putPath(pr, "icon", iconFile); putPath(pr, "pic0", pic0File);
            pr.setProperty("logoWidth", "" + logoWidth);
            pr.setProperty("logoY", "" + logoY);
            pr.setProperty("logoXOff", "" + logoXOff);
            pr.setProperty("screenY", "" + screenY);
            pr.setProperty("focusX", "" + focusX);
            pr.setProperty("focusY", "" + focusY);
            pr.setProperty("logoOnPic0", "" + logoOnPic0);
            pr.setProperty("credits", credits);
            pr.setProperty("name", name);
            pr.setProperty("textSize", "" + textSize);
            pr.setProperty("marginLeft", "" + marginLeft);
            pr.setProperty("marginBottom", "" + marginBottom);
            pr.setProperty("textColor", String.format("%06x", textColor.getRGB() & 0xFFFFFF));
            pr.setProperty("shadow", "" + shadow);
            try (OutputStream os = new FileOutputStream(f)) {
                pr.store(os, "Vita LiveArea Editor project");
            }
        }

        void load(File f) throws IOException {
            Properties pr = new Properties();
            try (InputStream is = new FileInputStream(f)) { pr.load(is); }
            for (String k : new String[]{"bg", "logo", "icon", "pic0"}) {
                String path = pr.getProperty(k, "");
                File file = path.isEmpty() ? null : new File(path);
                try { setImage(k, file != null && file.isFile() ? file : null); }
                catch (IOException ignored) { setImage(k, null); }
            }
            logoWidth = geti(pr, "logoWidth", 276);
            logoY = geti(pr, "logoY", 0);
            logoXOff = geti(pr, "logoXOff", 0);
            screenY = geti(pr, "screenY", SHOWN_Y);
            focusX = geti(pr, "focusX", 50);
            focusY = geti(pr, "focusY", 50);
            logoOnPic0 = Boolean.parseBoolean(pr.getProperty("logoOnPic0", "true"));
            credits = pr.getProperty("credits", "");
            name = pr.getProperty("name", "");
            textSize = geti(pr, "textSize", 18);
            marginLeft = geti(pr, "marginLeft", 20);
            marginBottom = geti(pr, "marginBottom", 20);
            try { textColor = new Color(Integer.parseInt(pr.getProperty("textColor", "ffffff"), 16)); }
            catch (NumberFormatException e) { textColor = Color.WHITE; }
            shadow = Boolean.parseBoolean(pr.getProperty("shadow", "true"));
        }

        private static void putPath(Properties pr, String k, File f) {
            pr.setProperty(k, f == null ? "" : f.getAbsolutePath());
        }
        private static int geti(Properties pr, String k, int def) {
            try { return Integer.parseInt(pr.getProperty(k, "" + def).trim()); }
            catch (NumberFormatException e) { return def; }
        }
    }

    // =====================================================================
    //  Image conversion
    // =====================================================================
    static BufferedImage readImage(File f) throws IOException {
        BufferedImage img = ImageIO.read(f);   // detects the real format by content, not by extension
        if (img == null) throw new IOException("Not a readable image: " + f.getName());
        return img;
    }

    static BufferedImage convert(BufferedImage src, int type) {
        if (src.getType() == type) return src;
        BufferedImage d = new BufferedImage(src.getWidth(), src.getHeight(), type);
        Graphics2D g = d.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return d;
    }

    private static BufferedImage resizeStep(BufferedImage s, int w, int h) {
        BufferedImage d = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = d.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(s, 0, 0, w, h, null);
        g.dispose();
        return d;
    }

    /** High quality resize. Works on premultiplied alpha so transparent logo edges get no halo. */
    static BufferedImage scaleArgb(BufferedImage src, int w, int h) {
        w = Math.max(1, w); h = Math.max(1, h);
        BufferedImage cur = convert(src, BufferedImage.TYPE_INT_ARGB_PRE);
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            cur = resizeStep(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        if (cur.getWidth() != w || cur.getHeight() != h) cur = resizeStep(cur, w, h);
        return cur;
    }

    /** Scale to fill w x h, then crop. fx/fy (0..1) pick which part of the overflow is kept. */
    static BufferedImage cover(BufferedImage src, int w, int h, double fx, double fy) {
        double s = Math.max((double) w / src.getWidth(), (double) h / src.getHeight());
        int sw = Math.max(w, (int) Math.ceil(src.getWidth() * s));
        int sh = Math.max(h, (int) Math.ceil(src.getHeight() * s));
        BufferedImage sc = scaleArgb(src, sw, sh);
        int x = (int) Math.round((sw - w) * fx), y = (int) Math.round((sh - h) * fy);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(sc, -x, -y, null);
        g.dispose();
        return out;
    }

    /** Median-cut palette (256 colours) + Floyd-Steinberg dithering -> 8-bit indexed image. */
    static BufferedImage quantize(BufferedImage src) {
        final int w = src.getWidth(), h = src.getHeight();
        final int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        final int B = 6, SH = 8 - B, N = 1 << (3 * B), MASK = (1 << B) - 1;
        long[] cnt = new long[N], sr = new long[N], sg = new long[N], sb = new long[N];
        for (int p : px) {
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int k = ((r >> SH) << (2 * B)) | ((g >> SH) << B) | (b >> SH);
            cnt[k]++; sr[k] += r; sg[k] += g; sb[k] += b;
        }
        List<Integer> used = new ArrayList<>();
        for (int k = 0; k < N; k++) if (cnt[k] > 0) used.add(k);

        List<List<Integer>> boxes = new ArrayList<>();
        List<Long> pops = new ArrayList<>();
        boxes.add(used);
        long total = 0;
        for (int k : used) total += cnt[k];
        pops.add(total);
        while (boxes.size() < 256) {
            int best = -1; long bestPop = -1;
            for (int i = 0; i < boxes.size(); i++) {
                if (boxes.get(i).size() < 2) continue;
                if (pops.get(i) > bestPop) { bestPop = pops.get(i); best = i; }
            }
            if (best < 0) break;
            List<Integer> bx = boxes.remove(best);
            pops.remove(best);
            int[] lo = {63, 63, 63}, hi = {0, 0, 0};
            for (int k : bx) {
                int[] c = {(k >> (2 * B)) & MASK, (k >> B) & MASK, k & MASK};
                for (int j = 0; j < 3; j++) { lo[j] = Math.min(lo[j], c[j]); hi[j] = Math.max(hi[j], c[j]); }
            }
            int ch = 0;
            for (int j = 1; j < 3; j++) if (hi[j] - lo[j] > hi[ch] - lo[ch]) ch = j;
            final int shift = ch == 0 ? 2 * B : ch == 1 ? B : 0;
            bx.sort(Comparator.comparingInt((Integer k) -> (k >> shift) & MASK));
            long half = bestPop / 2, acc = 0; int cut = 1;
            for (int i = 0; i < bx.size() - 1; i++) {
                acc += cnt[bx.get(i)]; cut = i + 1;
                if (acc >= half) break;
            }
            List<Integer> a = new ArrayList<>(bx.subList(0, cut)), b = new ArrayList<>(bx.subList(cut, bx.size()));
            long pa = 0; for (int k : a) pa += cnt[k];
            boxes.add(a); pops.add(pa);
            boxes.add(b); pops.add(bestPop - pa);
        }
        final int n = boxes.size();
        int[] pr = new int[n], pg = new int[n], pb = new int[n];
        for (int i = 0; i < n; i++) {
            long c = 0, r = 0, g = 0, b = 0;
            for (int k : boxes.get(i)) { c += cnt[k]; r += sr[k]; g += sg[k]; b += sb[k]; }
            pr[i] = (int) (r / c); pg[i] = (int) (g / c); pb[i] = (int) (b / c);
        }
        final int[] cache = new int[N];
        java.util.Arrays.fill(cache, -1);

        int[] R = new int[w * h], G = new int[w * h], Bl = new int[w * h];
        for (int i = 0; i < px.length; i++) { R[i] = (px[i] >> 16) & 255; G[i] = (px[i] >> 8) & 255; Bl[i] = px[i] & 255; }

        byte[] rr = new byte[256], gg = new byte[256], bb = new byte[256];
        for (int i = 0; i < n; i++) { rr[i] = (byte) pr[i]; gg[i] = (byte) pg[i]; bb[i] = (byte) pb[i]; }
        IndexColorModel icm = new IndexColorModel(8, 256, rr, gg, bb);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, icm);
        byte[] data = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int r = Math.max(0, Math.min(255, R[i])), g = Math.max(0, Math.min(255, G[i])), b = Math.max(0, Math.min(255, Bl[i]));
                int k = ((r >> SH) << (2 * B)) | ((g >> SH) << B) | (b >> SH);
                int idx = cache[k];
                if (idx < 0) {
                    int cr = ((k >> (2 * B)) & MASK) << SH | (1 << (SH - 1));
                    int cg = ((k >> B) & MASK) << SH | (1 << (SH - 1));
                    int cb = (k & MASK) << SH | (1 << (SH - 1));
                    int bd = Integer.MAX_VALUE; idx = 0;
                    for (int j = 0; j < n; j++) {
                        int dr = cr - pr[j], dg = cg - pg[j], db = cb - pb[j];
                        int d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
                        if (d < bd) { bd = d; idx = j; }
                    }
                    cache[k] = idx;
                }
                data[i] = (byte) idx;
                int er = r - pr[idx], eg = g - pg[idx], eb = b - pb[idx];
                if (x + 1 < w) { R[i + 1] += er * 7 / 16; G[i + 1] += eg * 7 / 16; Bl[i + 1] += eb * 7 / 16; }
                if (y + 1 < h) {
                    int j = i + w;
                    if (x > 0) { R[j - 1] += er * 3 / 16; G[j - 1] += eg * 3 / 16; Bl[j - 1] += eb * 3 / 16; }
                    R[j] += er * 5 / 16; G[j] += eg * 5 / 16; Bl[j] += eb * 5 / 16;
                    if (x + 1 < w) { R[j + 1] += er / 16; G[j + 1] += eg / 16; Bl[j + 1] += eb / 16; }
                }
            }
        }
        return out;
    }

    // =====================================================================
    //  Build the four images + template.xml
    // =====================================================================
    static class Assets { BufferedImage bg, startup, pic0, icon; }

    static BufferedImage placeholder(int w, int h) {
        BufferedImage i = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = i.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(70, 74, 84), 0, h, new Color(40, 42, 48)));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return i;
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** {logo width, logo height, x, y} of the logo inside the 280x158 startup canvas; null without a logo. */
    static int[] logoGeom(Project p) {
        if (p.logo == null) return null;
        double ratio = (double) p.logo.getHeight() / p.logo.getWidth();
        int lw = clamp(p.logoWidth, 20, ST_W);
        int lh = Math.max(1, (int) Math.round(lw * ratio));
        if (lh > ST_H) { lh = ST_H; lw = Math.max(1, (int) Math.round(lh / ratio)); }
        int lx = clamp((ST_W - lw) / 2 + p.logoXOff, 0, ST_W - lw);
        int ly = clamp(p.logoY, 0, ST_H - lh);
        return new int[]{lw, lh, lx, ly};
    }

    /** Logo rectangle on the 840x500 LiveArea page, in page pixels. */
    static Rectangle logoOnPage(Project p) {
        int[] g = logoGeom(p);
        if (g == null) return null;
        return new Rectangle((BG_W - SHOWN_W) / 2 + g[2] * 2, p.screenY + g[3] * 2, g[0] * 2, g[1] * 2);
    }

    static Assets build(Project p, boolean quant) {
        Assets a = new Assets();
        double fx = p.focusX / 100.0, fy = p.focusY / 100.0;

        a.bg = p.bg != null ? cover(p.bg, BG_W, BG_H, fx, fy) : placeholder(BG_W, BG_H);

        // startup.png: 280x158 transparent canvas (the Vita shows it at 2x = 560x316).
        a.startup = new BufferedImage(ST_W, ST_H, BufferedImage.TYPE_INT_ARGB);
        BufferedImage logoSmall = null;
        int[] gm = logoGeom(p);
        int lw = 0, lh = 0, lx = 0, ly = 0;
        if (gm != null) {
            lw = gm[0]; lh = gm[1]; lx = gm[2]; ly = gm[3];
            logoSmall = scaleArgb(p.logo, lw, lh);
            Graphics2D g = a.startup.createGraphics();
            g.drawImage(logoSmall, lx, ly, null);
            g.dispose();
        }

        // pic0.png: the logo is placed at the same spot it appears on the LiveArea bg, scaled 960/840.
        BufferedImage picSrc = p.pic0 != null ? p.pic0 : p.bg;
        a.pic0 = picSrc != null ? cover(picSrc, PIC_W, PIC_H, fx, fy) : placeholder(PIC_W, PIC_H);
        if (logoSmall != null && p.logoOnPic0) {
            double sx = PIC_W / (double) BG_W, sy = PIC_H / (double) BG_H;
            double bx0 = (BG_W - SHOWN_W) / 2.0 + lx * 2.0;   // startup is shown at 2x, centred
            double by0 = p.screenY + ly * 2.0;
            double bw = lw * 2.0, bh = lh * 2.0;
            double pw = bw * sx;
            double ph = pw * p.logo.getHeight() / p.logo.getWidth();
            double cx = (bx0 + bw / 2) * sx, cy = (by0 + bh / 2) * sy;
            BufferedImage big = scaleArgb(p.logo, (int) Math.round(pw), (int) Math.round(ph));
            Graphics2D g = a.pic0.createGraphics();
            g.drawImage(big, (int) Math.round(cx - pw / 2), (int) Math.round(cy - ph / 2), null);
            g.dispose();
        }

        a.icon = p.icon != null ? cover(p.icon, ICON, ICON, 0.5, 0.5) : placeholder(ICON, ICON);

        if (quant) {
            a.bg = quantize(a.bg);
            a.pic0 = quantize(a.pic0);
            a.icon = quantize(a.icon);
        }
        return a;
    }

    static String creditsClean(String s) {
        String t = s.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = t.split("\n", -1);
        int a = 0, b = lines.length;
        while (a < b && lines[a].trim().isEmpty()) a++;
        while (b > a && lines[b - 1].trim().isEmpty()) b--;
        StringBuilder sb = new StringBuilder();
        for (int i = a; i < b; i++) { if (i > a) sb.append('\n'); sb.append(lines[i].replaceAll("\\s+$", "")); }
        return sb.toString();
    }

    static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** template.xml for the "ad0" LiveArea style (one liveitem), plus optional credits text. */
    static String template(Project p) {
        String credits = creditsClean(p.credits);
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n\r\n");
        x.append("<livearea style=\"ad0\" format-ver=\"01.00\" content-rev=\"1\">\r\n");
        x.append("\t<livearea-background>\r\n\t\t<image>bg.png</image> \r\n\t</livearea-background>\r\n\r\n");
        x.append("\t<frame id='frame1' multi='o' autoflip='1' >\r\n");
        x.append("\t\t<liveitem>\r\n");
        x.append("\t\t\t<target>psla:eboot</target>\r\n");
        x.append("\t\t\t<image y=\"").append(p.screenY).append("\" width=\"").append(SHOWN_W)
         .append("\" height=\"").append(SHOWN_H).append("\">startup.png</image> \r\n");
        if (!credits.isEmpty()) {
            // One <text> block; lines are separated by a plain newline inside <str>. (Several <text>
            // blocks in one liveitem only show the first one on a real Vita.)
            x.append("\t\t\t<text origin=\"background\" align=\"left\" valign=\"bottom\" line-align=\"left\" ")
             .append("text-valign=\"bottom\" margin-left=\"").append(p.marginLeft)
             .append("\" margin-bottom=\"").append(p.marginBottom).append("\">\r\n");
            x.append("\t\t\t\t<str size=\"").append(p.textSize).append("\" bold=\"off\" shadow=\"")
             .append(p.shadow ? "on" : "off").append("\" color=\"")
             .append(String.format("#%06x", p.textColor.getRGB() & 0xFFFFFF)).append("\">")
             .append(xmlEscape(credits)).append("</str>\r\n");
            x.append("\t\t\t</text>\r\n");
        }
        x.append("\t\t</liveitem>\r\n\t</frame>\r\n\r\n</livearea>\r\n");
        return x.toString();
    }

    /** Approximate on-screen look of the LiveArea page (bg + startup + credits). */
    static BufferedImage compose(Project p, Assets a) {
        BufferedImage out = new BufferedImage(BG_W, BG_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(a.bg, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(a.startup, (BG_W - SHOWN_W) / 2, p.screenY, SHOWN_W, SHOWN_H, null);
        String credits = creditsClean(p.credits);
        if (!credits.isEmpty()) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, p.textSize));
            FontMetrics fm = g.getFontMetrics();
            String[] lines = credits.split("\n");
            int y = BG_H - p.marginBottom - fm.getDescent();
            for (int i = lines.length - 1; i >= 0; i--) {
                if (p.shadow) { g.setColor(new Color(0, 0, 0, 170)); g.drawString(lines[i], p.marginLeft + 1, y + 1); }
                g.setColor(p.textColor);
                g.drawString(lines[i], p.marginLeft, y);
                y -= fm.getHeight();
            }
        }
        g.dispose();
        return out;
    }

    // =====================================================================
    //  Export
    // =====================================================================
    static void writePng(BufferedImage img, File f) throws IOException {
        f.getParentFile().mkdirs();
        if (!ImageIO.write(img, "png", f)) throw new IOException("No PNG writer available");
    }

    /** Turns a game name into something safe to use as a folder name. */
    static String safeName(String s) {
        String n = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1).trim();
        while (n.endsWith(".")) n = n.substring(0, n.length() - 1).trim();
        return n;
    }

    /** <chosen folder>/<project name>/ , or just the chosen folder when no name is set. */
    static File projectDir(Project p, File outDir) {
        String n = safeName(p.name);
        return n.isEmpty() ? outDir : new File(outDir, n);
    }

    static String export(Project p, File outDir, boolean zip) throws IOException {
        String err = p.validate();
        if (err != null) throw new IOException(err);
        Assets a = build(p, true);
        File base = projectDir(p, outDir);
        File sce = new File(base, "sce_sys");
        File contents = new File(sce, "livearea/contents");
        contents.mkdirs();
        writePng(a.icon, new File(sce, "icon0.png"));
        writePng(a.pic0, new File(sce, "pic0.png"));
        writePng(a.bg, new File(contents, "bg.png"));
        writePng(a.startup, new File(contents, "startup.png"));
        Files.write(new File(contents, "template.xml").toPath(), template(p).getBytes(StandardCharsets.UTF_8));
        StringBuilder msg = new StringBuilder("Created " + sce.getAbsolutePath());
        if (zip) {
            File z = new File(base, "sce_sys.zip");
            try (ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(z))) {
                addZip(zo, sce, "sce_sys");
            }
            msg.append("\nand ").append(z.getAbsolutePath());
        }
        return msg.toString();
    }

    private static void addZip(ZipOutputStream zo, File f, String name) throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) return;
            java.util.Arrays.sort(kids);
            for (File k : kids) addZip(zo, k, name + "/" + k.getName());
        } else {
            zo.putNextEntry(new ZipEntry(name));
            Files.copy(f.toPath(), zo);
            zo.closeEntry();
        }
    }

    // =====================================================================
    //  GUI
    // =====================================================================
    static class PreviewPanel extends JPanel {
        BufferedImage img;
        String hint;
        Rectangle overlay;   // optional dashed box (image pixels), e.g. around the draggable logo
        /** Optional drag support. hit() says whether a press at image pixel (x,y) starts a drag. */
        java.util.function.BiPredicate<Double, Double> hit;
        Runnable onPress;
        java.util.function.BiConsumer<Double, Double> onDrag;   // total movement so far, in image pixels
        private double scale = 1;
        private int ox, oy, startX, startY;
        private boolean dragging;

        PreviewPanel(String hint) {
            this.hint = hint;
            setBackground(new Color(38, 38, 42));
            MouseAdapter m = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (hit == null || img == null) return;
                    if (hit.test((e.getX() - ox) / scale, (e.getY() - oy) / scale)) {
                        dragging = true; startX = e.getX(); startY = e.getY();
                        if (onPress != null) onPress.run();
                    }
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragging && onDrag != null) onDrag.accept((e.getX() - startX) / scale, (e.getY() - startY) / scale);
                }
                @Override public void mouseReleased(MouseEvent e) { dragging = false; }
                @Override public void mouseMoved(MouseEvent e) {
                    if (hit == null || img == null) return;
                    boolean over = hit.test((e.getX() - ox) / scale, (e.getY() - oy) / scale);
                    setCursor(over ? Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR) : Cursor.getDefaultCursor());
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        void setImage(BufferedImage i) { img = i; repaint(); }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            if (img == null) {
                g.setColor(Color.GRAY);
                FontMetrics fm = g.getFontMetrics();
                g.drawString(hint, (getWidth() - fm.stringWidth(hint)) / 2, getHeight() / 2);
                return;
            }
            double s = Math.min((getWidth() - 24) / (double) img.getWidth(), (getHeight() - 24) / (double) img.getHeight());
            if (s <= 0) return;
            int w = (int) Math.round(img.getWidth() * s), h = (int) Math.round(img.getHeight() * s);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    s > 1 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
            scale = s; ox = x; oy = y;
            g.drawImage(img, x, y, w, h, null);
            g.setColor(new Color(255, 255, 255, 60));
            g.drawRect(x - 1, y - 1, w + 1, h + 1);
            if (overlay != null) {
                g.setColor(new Color(255, 255, 255, 190));
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{5f, 4f}, 0f));
                g.drawRect(x + (int) Math.round(overlay.x * s), y + (int) Math.round(overlay.y * s),
                        (int) Math.round(overlay.width * s), (int) Math.round(overlay.height * s));
            }
        }
    }

    static class Gui {
        final Project p = new Project();
        final File settingsFile = new File(System.getProperty("user.home"), ".vita-livearea-editor.properties");
        File lastDir;
        JFrame frame;
        final PreviewPanel live = new PreviewPanel("Pick a background, logo and icon to see a preview");
        final PreviewPanel pic = new PreviewPanel("pic0.png preview");
        final PreviewPanel ico = new PreviewPanel("icon0.png preview");
        final Map<String, JTextField> fields = new HashMap<>();
        JSlider wSl, xSl, fxSl, fySl;
        JSpinner screenYSp;
        JCheckBox quantBox, pic0LogoBox, shadowBox, zipBox;
        JTextArea creditsArea;
        JTextField nameField;
        JSpinner sizeSp, mlSp, mbSp;
        JButton colorBtn;
        final JLabel status = new JLabel(" ");
        boolean syncing;
        int gen;
        final javax.swing.Timer debounce = new javax.swing.Timer(150, e -> render());

        Gui() { debounce.setRepeats(false); }

        void refresh() { if (!syncing) debounce.restart(); }

        // ---- layout ----
        Container buildContent() {
            JPanel left = new JPanel();
            left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
            left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            JPanel imgs = section("Images  (click Browse or drag a file onto a box)");
            imgs.add(fileRow("bg", "Background art", false));
            imgs.add(fileRow("logo", "Logo (transparent PNG)", false));
            imgs.add(fileRow("icon", "Icon art", false));
            imgs.add(fileRow("pic0", "pic0 art (optional, default = background)", true));
            left.add(imgs);

            JPanel logo = section("Logo size and location");
            wSl = slider("Logo width", 60, ST_W, p.logoWidth, v -> p.logoWidth = v, logo);
            xSl = slider("Left / right offset from centre", -140, 140, p.logoXOff, v -> p.logoXOff = v, logo);
            JPanel posRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            screenYSp = spinner(posRow, "Distance from top (startup.png y)", p.screenY, 0, 450, v -> p.screenY = v);
            logo.add(posRow);
            JButton resetPos = new JButton("Reset location");
            resetPos.addActionListener(e -> { p.logoXOff = 0; p.logoY = 0; p.screenY = SHOWN_Y; syncUi(); });
            logo.add(resetPos);
            logo.add(new JLabel("<html><small>Tip: drag the logo in the LiveArea preview. Moving it sideways needs room,"
                    + " so make the logo narrower than 276 first.</small></html>"));
            pic0LogoBox = new JCheckBox("Also draw the logo on pic0 (same position)", p.logoOnPic0);
            pic0LogoBox.addActionListener(e -> { p.logoOnPic0 = pic0LogoBox.isSelected(); refresh(); });
            logo.add(pic0LogoBox);
            left.add(logo);

            JPanel crop = section("Background crop");
            fxSl = slider("Focus left / right", 0, 100, p.focusX, v -> p.focusX = v, crop);
            fySl = slider("Focus top / bottom", 0, 100, p.focusY, v -> p.focusY = v, crop);
            left.add(crop);

            JPanel txt = section("Credits text (bottom-left, optional)");
            creditsArea = new JTextArea(3, 20);
            creditsArea.setLineWrap(false);
            creditsArea.getDocument().addDocumentListener(new DocumentListener() {
                void ch() { if (!syncing) { p.credits = creditsArea.getText(); refresh(); } }
                public void insertUpdate(DocumentEvent e) { ch(); }
                public void removeUpdate(DocumentEvent e) { ch(); }
                public void changedUpdate(DocumentEvent e) { ch(); }
            });
            txt.add(new JScrollPane(creditsArea));
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            sizeSp = spinner(row, "Size", p.textSize, 8, 48, v -> p.textSize = v);
            mlSp = spinner(row, "Left", p.marginLeft, 0, 400, v -> p.marginLeft = v);
            mbSp = spinner(row, "Bottom", p.marginBottom, 0, 400, v -> p.marginBottom = v);
            txt.add(row);
            JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            colorBtn = new JButton("Colour");
            colorBtn.setBackground(p.textColor);
            colorBtn.addActionListener(e -> {
                Color c = JColorChooser.showDialog(frame, "Text colour", p.textColor);
                if (c != null) { p.textColor = c; colorBtn.setBackground(c); refresh(); }
            });
            shadowBox = new JCheckBox("Shadow", p.shadow);
            shadowBox.addActionListener(e -> { p.shadow = shadowBox.isSelected(); refresh(); });
            row2.add(colorBtn); row2.add(shadowBox);
            txt.add(row2);
            JLabel note = new JLabel("<html><small>Put each line on its own line. Keep the bottom margin at 20 or more:"
                    + " a margin of 0 can be cut off on a real Vita.</small></html>");
            txt.add(note);
            left.add(txt);

            JPanel out = section("Output");
            nameField = new JTextField(p.name);
            nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, nameField.getPreferredSize().height));
            nameField.setToolTipText("Each project is exported into its own subfolder with this name");
            nameField.getDocument().addDocumentListener(new DocumentListener() {
                void ch() { if (!syncing) p.name = nameField.getText(); }
                public void insertUpdate(DocumentEvent e) { ch(); }
                public void removeUpdate(DocumentEvent e) { ch(); }
                public void changedUpdate(DocumentEvent e) { ch(); }
            });
            out.add(new JLabel("Project / game name (exports into its own subfolder)"));
            out.add(nameField);
            quantBox = new JCheckBox("Preview with the final 256-colour conversion", false);
            quantBox.addActionListener(e -> refresh());
            zipBox = new JCheckBox("Also create sce_sys.zip", true);
            JButton export = new JButton("Export sce_sys folder...");
            export.addActionListener(e -> doExport());
            out.add(quantBox); out.add(zipBox); out.add(export);
            left.add(out);

            JScrollPane leftScroll = new JScrollPane(left);
            leftScroll.setBorder(null);
            leftScroll.getVerticalScrollBar().setUnitIncrement(16);
            leftScroll.setPreferredSize(new Dimension(400, 700));

            final int[] dragStart = new int[2];
            live.hit = (ix, iy) -> {
                Rectangle r = logoOnPage(p);
                return r != null && ix >= r.x - 8 && ix <= r.x + r.width + 8 && iy >= r.y - 8 && iy <= r.y + r.height + 8;
            };
            live.onPress = () -> { dragStart[0] = p.logoXOff; dragStart[1] = p.screenY; };
            live.onDrag = (dx, dy) -> {
                int[] gm = logoGeom(p);
                if (gm == null) return;
                int base = (ST_W - gm[0]) / 2;                       // startup is shown at 2x: 1 canvas px = 2 page px
                int off = clamp(dragStart[0] + (int) Math.round(dx / 2.0), -base, ST_W - gm[0] - base);
                int sy = clamp(dragStart[1] + (int) Math.round(dy), 0, 450);
                p.logoXOff = off; p.screenY = sy;
                syncing = true; xSl.setValue(off); screenYSp.setValue(sy); syncing = false;
                render();
            };

            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("LiveArea (bg + logo + text)", live);
            tabs.addTab("pic0.png", pic);
            tabs.addTab("icon0.png", ico);

            JPanel root = new JPanel(new BorderLayout());
            root.add(leftScroll, BorderLayout.WEST);
            root.add(tabs, BorderLayout.CENTER);
            status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            root.add(status, BorderLayout.SOUTH);
            return root;
        }

        JPanel section(String title) {
            JPanel s = new JPanel();
            s.setLayout(new BoxLayout(s, BoxLayout.Y_AXIS));
            s.setBorder(BorderFactory.createTitledBorder(title));
            s.setAlignmentX(Component.LEFT_ALIGNMENT);
            return s;
        }

        JSlider slider(String name, int min, int max, int val, java.util.function.IntConsumer set, JPanel into) {
            JLabel lab = new JLabel(name + ": " + val);
            JSlider s = new JSlider(min, max, val);
            s.addChangeListener(e -> {
                lab.setText(name + ": " + s.getValue());
                if (!syncing) { set.accept(s.getValue()); refresh(); }
            });
            s.putClientProperty("label", lab);
            s.putClientProperty("name", name);
            into.add(lab);
            into.add(s);
            return s;
        }

        JSpinner spinner(JPanel into, String name, int val, int min, int max, java.util.function.IntConsumer set) {
            JSpinner sp = new JSpinner(new SpinnerNumberModel(val, min, max, 1));
            sp.addChangeListener(e -> { if (!syncing) { set.accept((Integer) sp.getValue()); refresh(); } });
            into.add(new JLabel(name));
            into.add(sp);
            return sp;
        }

        JPanel fileRow(String kind, String title, boolean optional) {
            JPanel row = new JPanel(new BorderLayout(4, 0));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            JTextField tf = new JTextField();
            tf.setEditable(false);
            tf.setToolTipText(title);
            fields.put(kind, tf);
            tf.setTransferHandler(new TransferHandler() {
                @Override public boolean canImport(TransferSupport s) { return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }
                @Override @SuppressWarnings("unchecked") public boolean importData(TransferSupport s) {
                    try {
                        List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                        if (!files.isEmpty()) loadFile(kind, files.get(0));
                        return true;
                    } catch (Exception ex) { return false; }
                }
            });
            JButton browse = new JButton("Browse...");
            browse.addActionListener(e -> {
                JFileChooser fc = new JFileChooser(lastDir);
                fc.setDialogTitle(title);
                fc.setFileFilter(new FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "bmp", "gif"));
                if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) loadFile(kind, fc.getSelectedFile());
            });
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            btns.add(browse);
            if (optional) {
                JButton clear = new JButton("Clear");
                clear.addActionListener(e -> loadFile(kind, null));
                btns.add(clear);
            }
            row.add(new JLabel(title), BorderLayout.NORTH);
            row.add(tf, BorderLayout.CENTER);
            row.add(btns, BorderLayout.EAST);
            return row;
        }

        void loadFile(String kind, File f) {
            try {
                p.setImage(kind, f);
                fields.get(kind).setText(f == null ? "" : f.getAbsolutePath());
                if (f != null) lastDir = f.getParentFile();
                refresh();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(frame, ex.getMessage(), "Could not open image", JOptionPane.ERROR_MESSAGE);
            }
        }

        /** Push the project values into the controls. */
        void syncUi() {
            syncing = true;
            for (String k : new String[]{"bg", "logo", "icon", "pic0"}) {
                File f = k.equals("bg") ? p.bgFile : k.equals("logo") ? p.logoFile : k.equals("icon") ? p.iconFile : p.pic0File;
                fields.get(k).setText(f == null ? "" : f.getAbsolutePath());
            }
            wSl.setValue(p.logoWidth); xSl.setValue(p.logoXOff); screenYSp.setValue(p.screenY);
            fxSl.setValue(p.focusX); fySl.setValue(p.focusY);
            pic0LogoBox.setSelected(p.logoOnPic0);
            creditsArea.setText(p.credits);
            nameField.setText(p.name);
            sizeSp.setValue(p.textSize); mlSp.setValue(p.marginLeft); mbSp.setValue(p.marginBottom);
            colorBtn.setBackground(p.textColor);
            shadowBox.setSelected(p.shadow);
            syncing = false;
            refresh();
        }

        void render() {
            final Project snap = p.copy();
            final boolean q = quantBox != null && quantBox.isSelected();
            final int my = ++gen;
            new SwingWorker<Object[], Void>() {
                @Override protected Object[] doInBackground() {
                    Assets a = build(snap, q);
                    return new Object[]{compose(snap, a), a};
                }
                @Override protected void done() {
                    if (my != gen) return;
                    try {
                        Object[] r = get();
                        Assets a = (Assets) r[1];
                        live.overlay = logoOnPage(snap);
                        live.setImage((BufferedImage) r[0]);
                        pic.setImage(a.pic0);
                        ico.setImage(a.icon);
                        String missing = snap.validate();
                        status.setText(missing != null ? missing
                                : "Ready. Export makes: icon0 128x128, pic0 960x544, bg 840x500 (all 256-colour), startup 280x158 RGBA, template.xml");
                    } catch (Exception ex) {
                        status.setText("Preview error: " + ex);
                    }
                }
            }.execute();
        }

        void doExport() {
            String err = p.validate();
            if (err != null) { JOptionPane.showMessageDialog(frame, err, "Missing image", JOptionPane.WARNING_MESSAGE); return; }
            if (safeName(p.name).isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Type a project / game name first. It becomes the subfolder name.",
                        "Missing project name", JOptionPane.WARNING_MESSAGE);
                nameField.requestFocusInWindow();
                return;
            }
            JFileChooser fc = new JFileChooser(lastDir);
            fc.setDialogTitle("Choose the folder that holds your projects (a subfolder named after the game is created)");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
            final File dir = fc.getSelectedFile();
            lastDir = dir;
            final Project snap = p.copy();
            final boolean zip = zipBox.isSelected();
            File target = projectDir(snap, dir);
            if (new File(target, "sce_sys").exists()
                    && JOptionPane.showConfirmDialog(frame, "\"" + target.getName() + "\" already has an sce_sys folder.\nOverwrite it?",
                            "Overwrite?", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) {
                return;
            }
            status.setText("Converting and exporting...");
            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() throws Exception { return export(snap, dir, zip); }
                @Override protected void done() {
                    try {
                        String msg = get();
                        status.setText("Export finished.");
                        JOptionPane.showMessageDialog(frame, msg, "Export finished", JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        status.setText("Export failed.");
                        JOptionPane.showMessageDialog(frame, c.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        }

        void openProject() {
            JFileChooser fc = new JFileChooser(lastDir);
            fc.setFileFilter(new FileNameExtensionFilter("LiveArea project (*.lap)", "lap"));
            if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
            try { p.load(fc.getSelectedFile()); syncUi(); }
            catch (IOException ex) { JOptionPane.showMessageDialog(frame, ex.getMessage(), "Could not open project", JOptionPane.ERROR_MESSAGE); }
        }

        void saveProject() {
            JFileChooser fc = new JFileChooser(lastDir);
            fc.setFileFilter(new FileNameExtensionFilter("LiveArea project (*.lap)", "lap"));
            if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
            File f = fc.getSelectedFile();
            if (!f.getName().toLowerCase().endsWith(".lap")) f = new File(f.getParentFile(), f.getName() + ".lap");
            try { p.save(f); status.setText("Saved " + f.getName()); }
            catch (IOException ex) { JOptionPane.showMessageDialog(frame, ex.getMessage(), "Could not save project", JOptionPane.ERROR_MESSAGE); }
        }

        void show() {
            frame = new JFrame("Vita LiveArea Editor");
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setContentPane((Container) buildContent());
            JMenuBar mb = new JMenuBar();
            JMenu file = new JMenu("File");
            JMenuItem open = new JMenuItem("Open project...");
            open.addActionListener(e -> openProject());
            JMenuItem save = new JMenuItem("Save project...");
            save.addActionListener(e -> saveProject());
            JMenuItem exp = new JMenuItem("Export sce_sys...");
            exp.addActionListener(e -> doExport());
            JMenuItem quit = new JMenuItem("Quit");
            quit.addActionListener(e -> quit());
            file.add(open); file.add(save); file.addSeparator(); file.add(exp); file.addSeparator(); file.add(quit);
            mb.add(file);
            frame.setJMenuBar(mb);
            frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { quit(); } });
            if (settingsFile.isFile()) { try { p.load(settingsFile); } catch (IOException ignored) { } }
            syncUi();
            frame.setSize(1220, 780);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        }

        void quit() {
            try { p.save(settingsFile); } catch (IOException ignored) { }
            frame.dispose();
            System.exit(0);
        }
    }

    // =====================================================================
    //  Command line (also handy for batch jobs)
    // =====================================================================
    static void help() {
        System.out.println(
            "Vita LiveArea Editor\n\n"
          + "  java -jar LiveAreaEditor.jar                 open the editor\n"
          + "  java -jar LiveAreaEditor.jar --export [options]\n\n"
          + "Options:\n"
          + "  --bg FILE  --logo FILE  --icon FILE  [--pic0 FILE]   source images (any format Java can read)\n"
          + "  --out DIR                 folder to export into (default: current folder)\n"
          + "  --name \"Game name\"       export into DIR/<Game name>/ instead of straight into DIR\n"
          + "  --credits \"Line 1\\nLine 2\"  text for the bottom-left corner\n"
          + "  --logo-width N (276)  --logo-x N (0, left/right offset)  --screen-y N (80, distance from top)\n"
          + "  --focus-x N (50)  --focus-y N (50)\n"
          + "  --text-size N (18)  --margin-left N (20)  --margin-bottom N (20)\n"
          + "  --color RRGGBB (ffffff)  --no-shadow  --no-pic0-logo  --zip\n"
          + "  --project FILE            load settings from a .lap file first\n");
    }

    static int cli(String[] args) throws Exception {
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) throw new IllegalArgumentException("Unexpected argument: " + a);
            String k = a.substring(2);
            boolean flag = k.equals("export") || k.equals("zip") || k.equals("no-shadow") || k.equals("no-pic0-logo") || k.equals("help") || k.equals("selftest-ui");
            o.put(k, flag ? "1" : (i + 1 < args.length ? args[++i] : ""));
        }
        if (o.containsKey("help")) { help(); return 0; }
        if (o.containsKey("selftest-ui")) return selftestUi(o);
        Project p = new Project();
        if (o.containsKey("project")) p.load(new File(o.get("project")));
        for (String k : new String[]{"bg", "logo", "icon", "pic0"}) if (o.containsKey(k)) p.setImage(k, new File(o.get(k)));
        if (o.containsKey("name")) p.name = o.get("name");
        if (o.containsKey("credits")) p.credits = o.get("credits").replace("\\n", "\n");
        if (o.containsKey("logo-width")) p.logoWidth = Integer.parseInt(o.get("logo-width"));
        if (o.containsKey("logo-y")) p.logoY = Integer.parseInt(o.get("logo-y"));
        if (o.containsKey("logo-x")) p.logoXOff = Integer.parseInt(o.get("logo-x"));
        if (o.containsKey("screen-y")) p.screenY = Integer.parseInt(o.get("screen-y"));
        if (o.containsKey("focus-x")) p.focusX = Integer.parseInt(o.get("focus-x"));
        if (o.containsKey("focus-y")) p.focusY = Integer.parseInt(o.get("focus-y"));
        if (o.containsKey("text-size")) p.textSize = Integer.parseInt(o.get("text-size"));
        if (o.containsKey("margin-left")) p.marginLeft = Integer.parseInt(o.get("margin-left"));
        if (o.containsKey("margin-bottom")) p.marginBottom = Integer.parseInt(o.get("margin-bottom"));
        if (o.containsKey("color")) p.textColor = new Color(Integer.parseInt(o.get("color"), 16));
        if (o.containsKey("no-shadow")) p.shadow = false;
        if (o.containsKey("no-pic0-logo")) p.logoOnPic0 = false;
        System.out.println(export(p, new File(o.getOrDefault("out", ".")), o.containsKey("zip")));
        return 0;
    }

    /** Builds the whole GUI without opening a window (works with -Djava.awt.headless=true). */
    static int selftestUi(Map<String, String> o) throws Exception {
        Gui g = new Gui();
        Container c = g.buildContent();
        for (String k : new String[]{"bg", "logo", "icon", "pic0"}) if (o.containsKey(k)) g.p.setImage(k, new File(o.get(k)));
        g.p.credits = "Line one\nLine two";
        g.syncing = true; g.creditsArea.setText(g.p.credits); g.syncing = false;
        Assets a = build(g.p.copy(), true);
        BufferedImage live = compose(g.p, a);
        System.out.println("GUI built, components: " + c.getComponentCount() + ", live preview " + live.getWidth() + "x" + live.getHeight());
        return 0;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            try { System.exit(cli(args)); }
            catch (Exception e) { System.err.println("Error: " + e.getMessage()); System.exit(1); }
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
            new Gui().show();
        });
    }
}
