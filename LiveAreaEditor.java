/*
 * Vita LiveArea Editor
 *
 * Builds an sce_sys folder (icon0.png, pic0.png, livearea/contents/<bg>.png, startup.png,
 * template.xml) from your own art and converts every image to the format the Vita expects.
 *
 * What the layout code is based on (measurements and conventions, not code):
 *   - The LiveArea page is an 840x500 card drawn at (60,44) on the 960x544 screen; its top
 *     44 px belong to the system buttons.
 *   - Positions are measured from the card centre, X to the right and Y UP.
 *   - In template.xml an image's x/y are offsets from the centre of the frame it lives in.
 *     In style ad0 the only frame (frame1) is 840x414 and its centre sits 43 px below the
 *     card centre.
 *   - Gate styles (a1..a5, psmobile) draw the 280x158 startup image at a fixed gate position.
 *   - text elements can be anchored to any edge with margin-left/right/top/bottom.
 * Sources: the LiveAreaDesigner project's documentation and layout table, the PS Vita
 * developer wiki (template.xml), and the vitasdk livearea sample. All code in this file is
 * original.
 *
 * Run the editor:   java -jar LiveAreaEditor.jar
 * Command line:     java -jar LiveAreaEditor.jar --help
 * Needs Java 11 or newer, no other libraries.
 */

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
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
import java.awt.image.IndexColorModel;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class LiveAreaEditor {

    // =====================================================================
    //  Vita screen facts
    // =====================================================================
    static final int SCREEN_W = 960, SCREEN_H = 544;
    static final int CARD_W = 840, CARD_H = 500;          // the LiveArea page
    static final int CARD_ON_SCREEN_X = 60, CARD_ON_SCREEN_Y = 44;
    static final int TOP_RESERVED = 44;                   // system buttons live here
    static final int ICON_SIZE = 128;
    static final int GATE_W = 280, GATE_H = 158;          // startup image of gate styles
    static final int CANVAS_W = 280, CANVAS_H = 158;      // ad0 logo image (shown at 2x)
    static final int ZOOM = 2;
    static final int AD0_FRAME_CX = 0, AD0_FRAME_CY = -43;   // centre of ad0's frame1, card-centred, Y up
    static final int AD0_DEFAULT_Y = 80;                  // the y value that was tested on a real Vita

    // =====================================================================
    //  Styles
    // =====================================================================
    static final class Style {
        final String id, label; final boolean tested, gate; final int gateX, gateY;
        Style(String id, String label, boolean tested, boolean gate, int gx, int gy) {
            this.id = id; this.label = label; this.tested = tested; this.gate = gate; this.gateX = gx; this.gateY = gy;
        }
    }
    static final Style[] STYLES = {
        new Style("ad0", "ad0 - full-screen art, logo anywhere on it", true, false, 0, 0),
        new Style("a1", "a1 - start gate in the centre", false, true, 0, 32),
        new Style("a2", "a2 - start gate in the centre, higher", false, true, 0, 66),
        new Style("a3", "a3 - start gate in the centre, higher", false, true, 0, 66),
        new Style("a4", "a4 - start gate in the centre, higher", false, true, 0, 66),
        new Style("a5", "a5 - start gate on the right", false, true, 240, 66),
        new Style("psmobile", "psmobile - start gate on the right, low", false, true, 240, 16),
    };
    static Style styleOf(String id) {
        for (Style s : STYLES) if (s.id.equals(id)) return s;
        return STYLES[0];
    }
    static boolean knownStyle(String id) {
        for (Style s : STYLES) if (s.id.equals(id)) return true;
        return false;
    }

    static final String[] ANCHORS = {"bl", "bc", "br", "tl", "tc", "tr"};
    static final String[] ANCHOR_LABELS = {"Bottom left (tested)", "Bottom centre", "Bottom right",
                                           "Top left", "Top centre", "Top right"};

    // =====================================================================
    //  Project model
    // =====================================================================
    static class Project {
        File bgFile, logoFile, iconFile, pic0File;
        BufferedImage bg, logo, icon, pic0;
        String name = "";                 // project / game name -> export subfolder
        String style = "ad0";
        String bgName = "bg.png";         // bg.png (tested) or bg0.png (name used in the docs)
        boolean makePic0 = true, makeIcon = true;
        int logoWidth = 276;              // logo width in image pixels (ad0 shows them at 2x)
        boolean posCustom = false;        // false: default spot (y = 80 in template.xml)
        int logoCx = 0, logoCy = 142;     // logo centre on the card, card-centred, Y up (used when posCustom)
        boolean explicitAlign = false;    // write align/valign/origin on the ad0 logo image
        int focusX = 50, focusY = 50;     // crop focus for bg / pic0 art
        boolean gateTransparent = false;  // gate styles: logo only (transparent) instead of an art card
        boolean pic0Screen = false;       // false: scaled with the picture (as tested), true: same screen spot
        boolean logoOnPic0 = true;
        String credits = "";
        String anchor = "bl";
        int marginX = 20, marginY = 20;
        int textSize = 18;
        Color textColor = Color.WHITE;
        boolean shadow = true;
        boolean strictCaps = false;       // keep files under the reference's conservative size caps
        boolean guides = true;            // preview only

        Style st() { return styleOf(style); }
        boolean gate() { return st().gate; }

        Project copy() {
            Project c = new Project();
            c.bgFile = bgFile; c.logoFile = logoFile; c.iconFile = iconFile; c.pic0File = pic0File;
            c.bg = bg; c.logo = logo; c.icon = icon; c.pic0 = pic0;
            c.name = name; c.style = style; c.bgName = bgName; c.makePic0 = makePic0; c.makeIcon = makeIcon;
            c.logoWidth = logoWidth; c.posCustom = posCustom; c.logoCx = logoCx; c.logoCy = logoCy;
            c.explicitAlign = explicitAlign; c.focusX = focusX; c.focusY = focusY;
            c.gateTransparent = gateTransparent; c.pic0Screen = pic0Screen; c.logoOnPic0 = logoOnPic0;
            c.credits = credits; c.anchor = anchor; c.marginX = marginX; c.marginY = marginY;
            c.textSize = textSize; c.textColor = textColor; c.shadow = shadow;
            c.strictCaps = strictCaps; c.guides = guides;
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
            if (icon == null && makeIcon) return "Pick an icon art image, or untick \"Create icon0.png\".";
            return null;
        }

        void save(File f) throws IOException {
            Properties pr = new Properties();
            putPath(pr, "bg", bgFile); putPath(pr, "logo", logoFile); putPath(pr, "icon", iconFile); putPath(pr, "pic0", pic0File);
            pr.setProperty("name", name); pr.setProperty("style", style); pr.setProperty("bgName", bgName);
            pr.setProperty("makePic0", "" + makePic0); pr.setProperty("makeIcon", "" + makeIcon);
            pr.setProperty("logoWidth", "" + logoWidth); pr.setProperty("posCustom", "" + posCustom);
            pr.setProperty("logoCx", "" + logoCx); pr.setProperty("logoCy", "" + logoCy);
            pr.setProperty("explicitAlign", "" + explicitAlign);
            pr.setProperty("focusX", "" + focusX); pr.setProperty("focusY", "" + focusY);
            pr.setProperty("gateTransparent", "" + gateTransparent); pr.setProperty("pic0Screen", "" + pic0Screen);
            pr.setProperty("logoOnPic0", "" + logoOnPic0);
            pr.setProperty("credits", credits); pr.setProperty("anchor", anchor);
            pr.setProperty("marginX", "" + marginX); pr.setProperty("marginY", "" + marginY);
            pr.setProperty("textSize", "" + textSize);
            pr.setProperty("textColor", String.format("%06x", textColor.getRGB() & 0xFFFFFF));
            pr.setProperty("shadow", "" + shadow); pr.setProperty("strictCaps", "" + strictCaps);
            pr.setProperty("guides", "" + guides);
            try (OutputStream os = new FileOutputStream(f)) { pr.store(os, "Vita LiveArea Editor project"); }
        }

        void load(File f) throws IOException {
            Properties pr = new Properties();
            try (InputStream is = new FileInputStream(f)) { pr.load(is); }
            for (String k : new String[]{"bg", "logo", "icon", "pic0"}) {
                String path = pr.getProperty(k, "");
                File file = path.isEmpty() ? null : new File(path);
                try { setImage(k, file != null && file.isFile() ? file : null); } catch (IOException e) { setImage(k, null); }
            }
            name = pr.getProperty("name", "");
            style = knownStyle(pr.getProperty("style", "ad0")) ? pr.getProperty("style", "ad0") : "ad0";
            bgName = pr.getProperty("bgName", "bg.png").equals("bg0.png") ? "bg0.png" : "bg.png";
            makePic0 = bool(pr, "makePic0", true); makeIcon = bool(pr, "makeIcon", true);
            logoWidth = num(pr, "logoWidth", 276); posCustom = bool(pr, "posCustom", false);
            logoCx = num(pr, "logoCx", 0); logoCy = num(pr, "logoCy", 142);
            explicitAlign = bool(pr, "explicitAlign", false);
            focusX = num(pr, "focusX", 50); focusY = num(pr, "focusY", 50);
            gateTransparent = bool(pr, "gateTransparent", false); pic0Screen = bool(pr, "pic0Screen", false);
            logoOnPic0 = bool(pr, "logoOnPic0", true);
            credits = pr.getProperty("credits", "");
            anchor = Arrays.asList(ANCHORS).contains(pr.getProperty("anchor", "bl")) ? pr.getProperty("anchor", "bl") : "bl";
            marginX = num(pr, "marginX", 20); marginY = num(pr, "marginY", 20);
            textSize = num(pr, "textSize", 18);
            try { textColor = new Color(Integer.parseInt(pr.getProperty("textColor", "ffffff"), 16)); } catch (NumberFormatException e) { textColor = Color.WHITE; }
            shadow = bool(pr, "shadow", true); strictCaps = bool(pr, "strictCaps", false); guides = bool(pr, "guides", true);
        }

        private static void putPath(Properties pr, String k, File f) { pr.setProperty(k, f == null ? "" : f.getAbsolutePath()); }
        private static int num(Properties pr, String k, int def) {
            try { return Integer.parseInt(pr.getProperty(k, "" + def).trim()); } catch (NumberFormatException e) { return def; }
        }
        private static boolean bool(Properties pr, String k, boolean def) { return Boolean.parseBoolean(pr.getProperty(k, "" + def)); }
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // =====================================================================
    //  Image helpers
    // =====================================================================
    static BufferedImage readImage(File f) throws IOException {
        BufferedImage img = ImageIO.read(f);          // format is detected from the content, not the extension
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

    private static BufferedImage resizeOnce(BufferedImage s, int w, int h) {
        BufferedImage d = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = d.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(s, 0, 0, w, h, null);
        g.dispose();
        return d;
    }

    /** Resize in premultiplied alpha (no dark/light halo on transparent edges), halving first for big reductions. */
    static BufferedImage scaleArgb(BufferedImage src, int w, int h) {
        w = Math.max(1, w); h = Math.max(1, h);
        BufferedImage cur = convert(src, BufferedImage.TYPE_INT_ARGB_PRE);
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) cur = resizeOnce(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        if (cur.getWidth() != w || cur.getHeight() != h) cur = resizeOnce(cur, w, h);
        return cur;
    }

    /** Fill w x h with the picture (cropping the overflow around the focus point). */
    static BufferedImage cover(BufferedImage src, int w, int h, double fx, double fy) {
        double s = Math.max((double) w / src.getWidth(), (double) h / src.getHeight());
        int sw = Math.max(w, (int) Math.ceil(src.getWidth() * s)), sh = Math.max(h, (int) Math.ceil(src.getHeight() * s));
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

    static BufferedImage placeholder(int w, int h) {
        BufferedImage i = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = i.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(70, 74, 84), 0, h, new Color(40, 42, 48)));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return i;
    }

    // =====================================================================
    //  Palette conversion (8-bit indexed PNG)
    // =====================================================================
    /**
     * Converts to an indexed image with at most maxColors entries (the palette is always padded to
     * 256 so the PNG is 8-bit). Pictures with few enough distinct colours keep their exact colours;
     * others get a median-cut palette, optionally with error-diffusion dithering. When mask is
     * given, masked pixels become the transparent palette entry (binary transparency).
     */
    static BufferedImage quantize(BufferedImage src, int maxColors, boolean dither, boolean[] mask) {
        final int w = src.getWidth(), h = src.getHeight();
        final int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        final boolean hasMask = mask != null;
        final int limit = Math.max(2, maxColors - (hasMask ? 1 : 0));
        final byte[] data = new byte[w * h];
        int[] pr, pg, pb;
        int n;

        HashMap<Integer, Integer> exact = new HashMap<>();
        boolean few = true;
        for (int i = 0; i < px.length && few; i++) {
            if (hasMask && mask[i]) continue;
            int rgb = px[i] & 0xFFFFFF;
            if (!exact.containsKey(rgb)) { if (exact.size() >= limit) few = false; else exact.put(rgb, exact.size()); }
        }
        if (few) {
            n = Math.max(1, exact.size());
            pr = new int[n]; pg = new int[n]; pb = new int[n];
            for (Map.Entry<Integer, Integer> e : exact.entrySet()) {
                int c = e.getKey(), i = e.getValue();
                pr[i] = (c >> 16) & 255; pg[i] = (c >> 8) & 255; pb[i] = c & 255;
            }
            for (int i = 0; i < px.length; i++) data[i] = (byte) (hasMask && mask[i] ? n : exact.get(px[i] & 0xFFFFFF));
        } else {
            final int B = 6, SH = 8 - B, N = 1 << (3 * B), MASK = (1 << B) - 1;
            long[] cnt = new long[N], sr = new long[N], sg = new long[N], sb = new long[N];
            for (int i = 0; i < px.length; i++) {
                if (hasMask && mask[i]) continue;
                int r = (px[i] >> 16) & 255, g = (px[i] >> 8) & 255, b = px[i] & 255;
                int k = ((r >> SH) << (2 * B)) | ((g >> SH) << B) | (b >> SH);
                cnt[k]++; sr[k] += r; sg[k] += g; sb[k] += b;
            }
            List<Integer> used = new ArrayList<>();
            long total = 0;
            for (int k = 0; k < N; k++) if (cnt[k] > 0) { used.add(k); total += cnt[k]; }
            List<List<Integer>> boxes = new ArrayList<>();
            List<Long> pops = new ArrayList<>();
            boxes.add(used); pops.add(total);
            while (boxes.size() < limit) {
                int best = -1; long bestPop = -1;
                for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).size() > 1 && pops.get(i) > bestPop) { bestPop = pops.get(i); best = i; }
                if (best < 0) break;
                List<Integer> bx = boxes.remove(best); pops.remove(best);
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
                for (int i = 0; i < bx.size() - 1; i++) { acc += cnt[bx.get(i)]; cut = i + 1; if (acc >= half) break; }
                List<Integer> a = new ArrayList<>(bx.subList(0, cut)), b = new ArrayList<>(bx.subList(cut, bx.size()));
                long pa = 0; for (int k : a) pa += cnt[k];
                boxes.add(a); pops.add(pa); boxes.add(b); pops.add(bestPop - pa);
            }
            n = boxes.size();
            pr = new int[n]; pg = new int[n]; pb = new int[n];
            for (int i = 0; i < n; i++) {
                long c = 0, r = 0, g = 0, b = 0;
                for (int k : boxes.get(i)) { c += cnt[k]; r += sr[k]; g += sg[k]; b += sb[k]; }
                pr[i] = (int) (r / c); pg[i] = (int) (g / c); pb[i] = (int) (b / c);
            }
            final int[] cache = new int[N];
            Arrays.fill(cache, -1);
            int[] R = new int[w * h], G = new int[w * h], Bl = new int[w * h];
            for (int i = 0; i < px.length; i++) { R[i] = (px[i] >> 16) & 255; G[i] = (px[i] >> 8) & 255; Bl[i] = px[i] & 255; }
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    if (hasMask && mask[i]) { data[i] = (byte) n; continue; }
                    int r = clamp(R[i], 0, 255), g = clamp(G[i], 0, 255), b = clamp(Bl[i], 0, 255);
                    int k = ((r >> SH) << (2 * B)) | ((g >> SH) << B) | (b >> SH);
                    int idx = cache[k];
                    if (idx < 0) {
                        int cr = (((k >> (2 * B)) & MASK) << SH) | (1 << (SH - 1));
                        int cg = (((k >> B) & MASK) << SH) | (1 << (SH - 1));
                        int cb = ((k & MASK) << SH) | (1 << (SH - 1));
                        int bd = Integer.MAX_VALUE; idx = 0;
                        for (int j = 0; j < n; j++) {
                            int dr = cr - pr[j], dg = cg - pg[j], db = cb - pb[j];
                            int d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
                            if (d < bd) { bd = d; idx = j; }
                        }
                        cache[k] = idx;
                    }
                    data[i] = (byte) idx;
                    if (!dither) continue;
                    int er = r - pr[idx], eg = g - pg[idx], eb = b - pb[idx];
                    spread(R, G, Bl, mask, w, h, x + 1, y, er, eg, eb, 7);
                    spread(R, G, Bl, mask, w, h, x - 1, y + 1, er, eg, eb, 3);
                    spread(R, G, Bl, mask, w, h, x, y + 1, er, eg, eb, 5);
                    spread(R, G, Bl, mask, w, h, x + 1, y + 1, er, eg, eb, 1);
                }
            }
        }

        byte[] rr = new byte[256], gg = new byte[256], bb = new byte[256];
        for (int i = 0; i < n; i++) { rr[i] = (byte) pr[i]; gg[i] = (byte) pg[i]; bb[i] = (byte) pb[i]; }
        IndexColorModel icm = hasMask ? new IndexColorModel(8, 256, rr, gg, bb, n) : new IndexColorModel(8, 256, rr, gg, bb);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, icm);
        out.getRaster().setDataElements(0, 0, w, h, data);
        return out;
    }

    private static void spread(int[] R, int[] G, int[] B, boolean[] mask, int w, int h, int x, int y, int er, int eg, int eb, int part) {
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        int j = y * w + x;
        if (mask != null && mask[j]) return;
        R[j] += er * part / 16; G[j] += eg * part / 16; B[j] += eb * part / 16;
    }

    /** PNG bytes with the strongest deflate setting Java's writer offers. */
    static byte[] pngBytes(BufferedImage img) throws IOException {
        ImageWriter wr = ImageIO.getImageWritersByFormatName("png").next();
        try {
            ImageWriteParam prm = wr.getDefaultWriteParam();
            if (prm.canWriteCompressed()) {
                prm.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                prm.setCompressionQuality(0f);          // 0 = smallest file
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (ImageOutputStream io = ImageIO.createImageOutputStream(bo)) {
                wr.setOutput(io);
                wr.write(null, new IIOImage(img, null, null), prm);
            }
            return bo.toByteArray();
        } finally { wr.dispose(); }
    }

    /** Size limit in KB for one file. Normal: the 420 KB the Vita samples recommend. Strict: tighter caps. */
    static int capKb(String kind, boolean strict) {
        if (!strict) return 420;
        switch (kind) { case "bg": return 128; case "startup": return 64; case "icon": return 128; default: return 256; }
    }

    /** Indexed PNG that fits the cap: 256 colours (dithered), then undithered, then fewer colours. */
    static byte[] encodeIndexed(String fileName, String kind, BufferedImage src, boolean[] mask, int capKb, List<String> notes) throws IOException {
        int[][] tries = {{256, 1}, {256, 0}, {192, 0}, {128, 0}, {96, 0}, {64, 0}};
        byte[] best = null;
        for (int t = 0; t < tries.length; t++) {
            byte[] b = pngBytes(quantize(src, tries[t][0], tries[t][1] == 1, mask));
            if (best == null || b.length < best.length) best = b;
            if (b.length <= capKb * 1024L) {
                if (t > 0) notes.add(fileName + " was reduced to " + tries[t][0] + " colours"
                        + (tries[t][1] == 0 ? " without dithering" : "") + " to stay under " + capKb + " KB.");
                return b;
            }
        }
        notes.add("WARNING: " + fileName + " is still " + (best.length / 1024) + " KB (cap " + capKb
                + " KB) even with 64 colours. Use simpler or smaller art.");
        return best;
    }

    // =====================================================================
    //  Geometry (all page positions: card pixels, origin top-left, unless stated)
    // =====================================================================
    /** Logo size/placement inside its own image: ad0 canvas (280x158, logo at top centre) or gate image (centred). */
    static final class LogoBox { int w, h, x, y; }

    static LogoBox logoBox(Project p) {
        if (p.logo == null) return null;
        double ratio = (double) p.logo.getHeight() / p.logo.getWidth();
        boolean gate = p.gate();
        int maxW = gate ? GATE_W - 16 : CANVAS_W, maxH = gate ? GATE_H - 16 : CANVAS_H;
        LogoBox b = new LogoBox();
        b.w = clamp(p.logoWidth, 20, maxW);
        b.h = Math.max(1, (int) Math.round(b.w * ratio));
        if (b.h > maxH) { b.h = maxH; b.w = Math.max(1, (int) Math.round(b.h / ratio)); }
        b.x = ((gate ? GATE_W : CANVAS_W) - b.w) / 2;
        b.y = gate ? (GATE_H - b.h) / 2 : 0;
        return b;
    }

    /** Where the logo centre is, card-centred (X right, Y up). Gate styles: the gate centre. */
    static int[] logoCentre(Project p) {
        LogoBox b = logoBox(p);
        if (b == null) return null;
        if (p.gate()) return new int[]{p.st().gateX, p.st().gateY};
        if (p.posCustom) return new int[]{p.logoCx, p.logoCy};
        // default: the logo image's centre is at y = 80 in template.xml, i.e. 80 above the frame centre
        double ccy = AD0_FRAME_CY + AD0_DEFAULT_Y;
        return new int[]{0, (int) Math.round(ccy - ZOOM * (b.y + b.h / 2.0 - CANVAS_H / 2.0))};
    }

    /** ad0: centre of the 280x158 logo image on the card, card-centred, Y up. */
    static int[] canvasCentre(Project p) {
        if (!p.posCustom) return new int[]{0, AD0_FRAME_CY + AD0_DEFAULT_Y};
        LogoBox b = logoBox(p);
        if (b == null) return new int[]{0, AD0_FRAME_CY + AD0_DEFAULT_Y};
        return new int[]{(int) Math.round(p.logoCx - ZOOM * (b.x + b.w / 2.0 - CANVAS_W / 2.0)),
                         (int) Math.round(p.logoCy + ZOOM * (b.y + b.h / 2.0 - CANVAS_H / 2.0))};
    }

    /** Logo rectangle on the page (card pixels). */
    static Rectangle logoOnPage(Project p) {
        LogoBox b = logoBox(p);
        int[] c = logoCentre(p);
        if (b == null || c == null) return null;
        int z = p.gate() ? 1 : ZOOM;
        int w = b.w * z, h = b.h * z;
        return new Rectangle(CARD_W / 2 + c[0] - w / 2, CARD_H / 2 - c[1] - h / 2, w, h);
    }

    /** Valid range for the logo centre so the logo stays on the card and below the reserved band. */
    static int[] logoCentreLimits(Project p) {
        LogoBox b = logoBox(p);
        int w = b == null ? 0 : b.w * ZOOM, h = b == null ? 0 : b.h * ZOOM;
        return new int[]{-(CARD_W / 2 - w / 2), CARD_W / 2 - w / 2,                 // x min, max
                         -(CARD_H / 2 - h / 2), CARD_H / 2 - TOP_RESERVED - (h + 1) / 2};   // y min, max (Y up)
    }

    // ---- text ----
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

    private static final Graphics2D METRICS_G = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
    static {
        METRICS_G.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    static FontMetrics fontMetrics(int size) { return METRICS_G.getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, size)); }

    /** Text block rectangle on the page (estimate: the Vita draws its own font). Null without text. */
    static Rectangle textRect(Project p) {
        String credits = creditsClean(p.credits);
        if (credits.isEmpty()) return null;
        FontMetrics fm = fontMetrics(p.textSize);
        String[] lines = credits.split("\n");
        int w = 0;
        for (String l : lines) w = Math.max(w, fm.stringWidth(l));
        int h = lines.length * fm.getHeight();
        char vt = p.anchor.charAt(0), hz = p.anchor.charAt(1);   // anchor = vertical letter + horizontal letter, e.g. "bl"
        int x = hz == 'l' ? p.marginX : hz == 'r' ? CARD_W - p.marginX - w : (CARD_W - w) / 2;
        int y = vt == 't' ? p.marginY : CARD_H - p.marginY - h;
        return new Rectangle(x, y, w, h);
    }

    /** Turn a dragged text rectangle into the nearest anchor + margins (so any spot is reachable). */
    static void setTextFromRect(Project p, Rectangle r) {
        int x = clamp(r.x, 0, Math.max(0, CARD_W - r.width)), y = clamp(r.y, 0, Math.max(0, CARD_H - r.height));
        double cx = x + r.width / 2.0, cy = y + r.height / 2.0;
        char hz = cx < CARD_W / 3.0 ? 'l' : cx > CARD_W * 2 / 3.0 ? 'r' : 'c';
        char vt = cy < CARD_H / 2.0 ? 't' : 'b';
        p.anchor = "" + vt + hz;
        p.marginX = hz == 'l' ? x : hz == 'r' ? CARD_W - (x + r.width) : 0;
        p.marginY = vt == 't' ? y : CARD_H - (y + r.height);
        p.marginX = Math.max(0, p.marginX); p.marginY = Math.max(0, p.marginY);
    }

    // =====================================================================
    //  Building the images
    // =====================================================================
    /** Unconverted images. startup is ARGB for ad0 and the transparent gate, RGB for the art-card gate. */
    static class Assets {
        BufferedImage bg, startup, pic0, icon;
        boolean[] startupMask;       // transparent pixels of a transparent gate image
    }

    static Assets build(Project p) {
        Assets a = new Assets();
        double fx = p.focusX / 100.0, fy = p.focusY / 100.0;
        a.bg = p.bg != null ? cover(p.bg, CARD_W, CARD_H, fx, fy) : placeholder(CARD_W, CARD_H);
        LogoBox lb = logoBox(p);
        BufferedImage logoSmall = lb == null ? null : scaleArgb(p.logo, lb.w, lb.h);

        if (!p.gate()) {                                            // ad0: logo on a 280x158 transparent image
            a.startup = new BufferedImage(CANVAS_W, CANVAS_H, BufferedImage.TYPE_INT_ARGB);
            if (logoSmall != null) {
                Graphics2D g = a.startup.createGraphics();
                g.drawImage(logoSmall, lb.x, lb.y, null);
                g.dispose();
            }
        } else if (p.gateTransparent) {                             // gate: logo only
            a.startup = new BufferedImage(GATE_W, GATE_H, BufferedImage.TYPE_INT_ARGB);
            if (logoSmall != null) {
                Graphics2D g = a.startup.createGraphics();
                g.drawImage(logoSmall, lb.x, lb.y, null);
                g.dispose();
            }
            int[] px = a.startup.getRGB(0, 0, GATE_W, GATE_H, null, 0, GATE_W);
            a.startupMask = new boolean[px.length];
            for (int i = 0; i < px.length; i++) a.startupMask[i] = ((px[i] >>> 24) & 255) < 128;
        } else {                                                    // gate: card cut from the art, logo on top
            BufferedImage tile = p.bg != null ? cover(p.bg, GATE_W, GATE_H, fx, fy) : placeholder(GATE_W, GATE_H);
            Graphics2D g = tile.createGraphics();
            g.setColor(new Color(0, 0, 0, 90));
            g.fillRect(0, 0, GATE_W, GATE_H);
            if (logoSmall != null) g.drawImage(logoSmall, lb.x, lb.y, null);
            g.dispose();
            a.startup = tile;
        }

        if (p.makePic0) {
            BufferedImage src = p.pic0 != null ? p.pic0 : p.bg;
            a.pic0 = src != null ? cover(src, SCREEN_W, SCREEN_H, fx, fy) : placeholder(SCREEN_W, SCREEN_H);
            if (p.logoOnPic0 && lb != null) drawLogoOnPic0(p, a.pic0, lb);
        }
        if (p.makeIcon) a.icon = p.icon != null ? cover(p.icon, ICON_SIZE, ICON_SIZE, 0.5, 0.5) : placeholder(ICON_SIZE, ICON_SIZE);
        return a;
    }

    private static void drawLogoOnPic0(Project p, BufferedImage pic, LogoBox lb) {
        int[] c = logoCentre(p);
        double pageW = lb.w * (p.gate() ? 1 : ZOOM);                 // logo width on the page
        double px = CARD_W / 2.0 + c[0], py = CARD_H / 2.0 - c[1];   // logo centre on the page
        double sx, sy, w, ox = 0, oy = 0;
        if (p.pic0Screen) {                                          // same spot on the real screen: card sits at (60,44)
            sx = sy = 1; w = pageW; ox = CARD_ON_SCREEN_X; oy = CARD_ON_SCREEN_Y;
        } else {                                                     // scaled with the picture (the version tested earlier)
            sx = SCREEN_W / (double) CARD_W; sy = SCREEN_H / (double) CARD_H; w = pageW * sx;
            if (!p.gate()) py += 25;                                 // that version measured the page from a spot 25 px lower
        }
        double h = w * p.logo.getHeight() / p.logo.getWidth();
        BufferedImage big = scaleArgb(p.logo, (int) Math.round(w), (int) Math.round(h));
        Graphics2D g = pic.createGraphics();
        g.drawImage(big, (int) Math.round(ox + px * sx - w / 2), (int) Math.round(oy + py * sy - h / 2), null);
        g.dispose();
    }

    // =====================================================================
    //  template.xml
    // =====================================================================
    static String xmlEscape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    /** One text element. Lines are separated by a plain newline inside one <str> (extra <text> blocks are ignored by the Vita). */
    static String textElement(Project p, String indent) {
        String credits = creditsClean(p.credits);
        if (credits.isEmpty()) return "";
        char vt = p.anchor.charAt(0), hz = p.anchor.charAt(1);   // anchor = vertical letter + horizontal letter, e.g. "bl"
        String align = hz == 'l' ? "left" : hz == 'r' ? "right" : "center";
        String valign = vt == 't' ? "top" : "bottom";
        StringBuilder t = new StringBuilder(indent);
        t.append("<text origin=\"background\" align=\"").append(align).append("\" valign=\"").append(valign)
         .append("\" line-align=\"").append(align).append("\" text-valign=\"").append(valign).append("\"");
        if (hz == 'l') t.append(" margin-left=\"").append(p.marginX).append("\"");
        if (hz == 'r') t.append(" margin-right=\"").append(p.marginX).append("\"");
        t.append(vt == 't' ? " margin-top=\"" : " margin-bottom=\"").append(p.marginY).append("\">\r\n");
        t.append(indent).append("\t<str size=\"").append(p.textSize).append("\" bold=\"off\" shadow=\"")
         .append(p.shadow ? "on" : "off").append("\" color=\"").append(String.format("#%06x", p.textColor.getRGB() & 0xFFFFFF))
         .append("\">").append(xmlEscape(credits)).append("</str>\r\n");
        t.append(indent).append("</text>\r\n");
        return t.toString();
    }

    static String template(Project p) {
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n\r\n");
        x.append("<livearea style=\"").append(p.style).append("\" format-ver=\"01.00\" content-rev=\"1\">\r\n");
        x.append("\t<livearea-background>\r\n\t\t<image>").append(p.bgName).append("</image> \r\n\t</livearea-background>\r\n\r\n");
        if (p.gate()) {
            x.append("\t<gate>\r\n\t\t<startup-image>startup.png</startup-image>\r\n\t</gate>\r\n\r\n");
            String t = textElement(p, "\t\t\t");
            if (!t.isEmpty()) x.append("\t<frame id=\"frame1\">\r\n\t\t<liveitem>\r\n").append(t).append("\t\t</liveitem>\r\n\t</frame>\r\n\r\n");
        } else {
            int[] cc = canvasCentre(p);
            int ix = cc[0] - AD0_FRAME_CX, iy = cc[1] - AD0_FRAME_CY;      // offsets from the frame centre, Y up
            x.append("\t<frame id='frame1' multi='o' autoflip='1' >\r\n\t\t<liveitem>\r\n");
            x.append("\t\t\t<target>psla:eboot</target>\r\n");
            x.append("\t\t\t<image ");
            if (p.explicitAlign) x.append("align=\"center\" valign=\"center\" origin=\"frame\" ");
            if (p.explicitAlign || ix != 0) x.append("x=\"").append(ix).append("\" ");
            x.append("y=\"").append(iy).append("\" width=\"").append(CANVAS_W * ZOOM).append("\" height=\"")
             .append(CANVAS_H * ZOOM).append("\">startup.png</image> \r\n");
            x.append(textElement(p, "\t\t\t"));
            x.append("\t\t</liveitem>\r\n\t</frame>\r\n\r\n");
        }
        x.append("</livearea>\r\n");
        return x.toString();
    }

    // =====================================================================
    //  Preview composite (page = 840x500; gate/frame positions from the style table)
    // =====================================================================
    static BufferedImage compose(Project p, Assets a) {
        BufferedImage out = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(a.bg, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        if (p.gate()) {
            int gx = CARD_W / 2 + p.st().gateX - GATE_W / 2, gy = CARD_H / 2 - p.st().gateY - GATE_H / 2;
            g.drawImage(a.startup, gx, gy, null);
            g.setColor(new Color(255, 255, 255, 110));
            g.drawRect(gx, gy, GATE_W, GATE_H);
        } else {
            int[] cc = canvasCentre(p);
            g.drawImage(a.startup, CARD_W / 2 + cc[0] - CANVAS_W * ZOOM / 2, CARD_H / 2 - cc[1] - CANVAS_H * ZOOM / 2,
                        CANVAS_W * ZOOM, CANVAS_H * ZOOM, null);
        }
        Rectangle tr = textRect(p);
        if (tr != null) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, p.textSize));
            FontMetrics fm = g.getFontMetrics();
            String[] lines = creditsClean(p.credits).split("\n");
            int y = tr.y + fm.getAscent();
            for (String line : lines) {
                int lx = p.anchor.charAt(1) == 'l' ? tr.x : p.anchor.charAt(1) == 'r' ? tr.x + tr.width - fm.stringWidth(line)
                                                                                   : tr.x + (tr.width - fm.stringWidth(line)) / 2;
                if (p.shadow) { g.setColor(new Color(0, 0, 0, 170)); g.drawString(line, lx + 1, y + 1); }
                g.setColor(p.textColor);
                g.drawString(line, lx, y);
                y += fm.getHeight();
            }
        }
        if (p.guides) {                                      // preview-only: the system buttons area
            g.setColor(new Color(0, 0, 0, 90));
            g.fillRect(0, 0, CARD_W, TOP_RESERVED);
            g.setColor(new Color(255, 255, 255, 140));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.drawString("system buttons (top " + TOP_RESERVED + " px)", 8, 16);
        }
        g.dispose();
        return out;
    }

    // =====================================================================
    //  Export
    // =====================================================================
    static String safeName(String s) {
        String n = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1).trim();
        while (n.endsWith(".")) n = n.substring(0, n.length() - 1).trim();
        return n;
    }

    static File projectDir(Project p, File outDir) {
        String n = safeName(p.name);
        return n.isEmpty() ? outDir : new File(outDir, n);
    }

    static String export(Project p, File outDir, boolean zip) throws IOException {
        String err = p.validate();
        if (err != null) throw new IOException(err);
        Assets a = build(p);
        File base = projectDir(p, outDir);
        File sce = new File(base, "sce_sys"), contents = new File(sce, "livearea/contents");
        contents.mkdirs();
        List<String> notes = new ArrayList<>();
        List<File> written = new ArrayList<>();

        writeBytes(new File(contents, p.bgName), encodeIndexed(p.bgName, "bg", a.bg, null, capKb("bg", p.strictCaps), notes), written);
        if (p.gate()) {
            writeBytes(new File(contents, "startup.png"),
                       encodeIndexed("startup.png", "startup", a.startup, a.startupMask, capKb("startup", p.strictCaps), notes), written);
        } else {
            byte[] st = pngBytes(a.startup);                  // ad0 keeps the RGBA image that was tested
            if (st.length > capKb("startup", p.strictCaps) * 1024L)
                notes.add("WARNING: startup.png is " + (st.length / 1024) + " KB (cap " + capKb("startup", p.strictCaps) + " KB).");
            writeBytes(new File(contents, "startup.png"), st, written);
        }
        if (p.makePic0) writeBytes(new File(sce, "pic0.png"), encodeIndexed("pic0.png", "pic0", a.pic0, null, capKb("pic0", p.strictCaps), notes), written);
        if (p.makeIcon) writeBytes(new File(sce, "icon0.png"), encodeIndexed("icon0.png", "icon", a.icon, null, capKb("icon", p.strictCaps), notes), written);
        writeBytes(new File(contents, "template.xml"), template(p).getBytes(StandardCharsets.UTF_8), written);

        StringBuilder msg = new StringBuilder("Created " + sce.getAbsolutePath());
        for (String n : notes) msg.append("\n").append(n.startsWith("WARNING") ? "" : "NOTE: ").append(n);
        if (zip) {
            File z = new File(base, "sce_sys.zip");
            try (ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(z))) { addZip(zo, sce, "sce_sys"); }
            msg.append("\nand ").append(z.getAbsolutePath());
        }
        return msg.toString();
    }

    private static void writeBytes(File f, byte[] data, List<File> written) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), data);
        written.add(f);
    }

    private static void addZip(ZipOutputStream zo, File f, String name) throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) return;
            Arrays.sort(kids);
            for (File k : kids) addZip(zo, k, name + "/" + k.getName());
        } else {
            zo.putNextEntry(new ZipEntry(name));
            Files.copy(f.toPath(), zo);
            zo.closeEntry();
        }
    }

    // =====================================================================
    //  Preview panel (draggable boxes)
    // =====================================================================
    static class PreviewPanel extends JPanel {
        interface Picker { int pick(double x, double y); }              // 0 = nothing, otherwise a target id
        interface Press { void press(int target); }
        interface Move { void move(int target, double dx, double dy); } // total movement in image pixels

        BufferedImage img;
        String hint;
        Rectangle boxLogo, boxText;                                       // dashed outlines (image pixels)
        Picker picker; Press onPress; Move onMove;
        private double scale = 1; private int ox, oy, startX, startY, target;

        PreviewPanel(String hint) {
            this.hint = hint;
            setBackground(new Color(38, 38, 42));
            MouseAdapter m = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (picker == null || img == null) return;
                    target = picker.pick((e.getX() - ox) / scale, (e.getY() - oy) / scale);
                    if (target != 0) { startX = e.getX(); startY = e.getY(); if (onPress != null) onPress.press(target); }
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (target != 0 && onMove != null) onMove.move(target, (e.getX() - startX) / scale, (e.getY() - startY) / scale);
                }
                @Override public void mouseReleased(MouseEvent e) { target = 0; }
                @Override public void mouseMoved(MouseEvent e) {
                    if (picker == null || img == null) return;
                    int t = picker.pick((e.getX() - ox) / scale, (e.getY() - oy) / scale);
                    setCursor(t != 0 ? Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR) : Cursor.getDefaultCursor());
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
                g.drawString(hint, (getWidth() - g.getFontMetrics().stringWidth(hint)) / 2, getHeight() / 2);
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
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{5f, 4f}, 0f));
            outline(g, boxLogo, new Color(255, 255, 255, 200), s, x, y);
            outline(g, boxText, new Color(255, 230, 120, 220), s, x, y);
        }

        private static void outline(Graphics2D g, Rectangle r, Color c, double s, int x, int y) {
            if (r == null) return;
            g.setColor(c);
            g.drawRect(x + (int) Math.round(r.x * s), y + (int) Math.round(r.y * s), (int) Math.round(r.width * s), (int) Math.round(r.height * s));
        }
    }

    // =====================================================================
    //  GUI
    // =====================================================================
    static class Gui {
        final Project p = new Project();
        final File settingsFile = new File(System.getProperty("user.home"), ".vita-livearea-editor.properties");
        File lastDir;
        JFrame frame;
        final PreviewPanel live = new PreviewPanel("Pick a background, a logo and an icon to see a preview");
        final PreviewPanel pic = new PreviewPanel("pic0.png preview");
        final PreviewPanel ico = new PreviewPanel("icon0.png preview");
        final Map<String, JTextField> fields = new HashMap<>();
        JComboBox<String> styleBox, bgNameBox, gateBox, anchorBox, pic0ModeBox;
        JLabel styleNote;
        JSlider wSl, fxSl, fySl;
        JSpinner xSp, ySp, mxSp, mySp, sizeSp;
        JCheckBox iconBox, pic0Box, pic0LogoBox, alignBox, shadowBox, quantBox, strictBox, guideBox, zipBox;
        JButton resetPosBtn, colorBtn, pic0Browse, pic0Clear;
        JTextArea creditsArea;
        JTextField nameField;
        final JLabel status = new JLabel(" ");
        boolean syncing;
        int gen;
        final javax.swing.Timer debounce = new javax.swing.Timer(120, e -> render());
        // drag state
        int dragCx, dragCy; Rectangle dragText;

        Gui() { debounce.setRepeats(false); }

        void refresh() { if (!syncing) debounce.restart(); }

        // ---- small builders ----
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
            into.add(lab); into.add(s);
            return s;
        }

        JSpinner spinner(JPanel into, String name, int val, int min, int max, java.util.function.IntConsumer set) {
            JSpinner sp = new JSpinner(new SpinnerNumberModel(val, min, max, 1));
            sp.addChangeListener(e -> { if (!syncing) { set.accept((Integer) sp.getValue()); refresh(); } });
            into.add(new JLabel(name));
            into.add(sp);
            return sp;
        }

        JCheckBox check(String text, boolean val, java.util.function.Consumer<Boolean> set) {
            JCheckBox c = new JCheckBox(text, val);
            c.addActionListener(e -> { if (!syncing) { set.accept(c.isSelected()); applyUi(); refresh(); } });
            return c;
        }

        JComboBox<String> combo(String[] items, java.util.function.IntConsumer set) {
            JComboBox<String> c = new JComboBox<>(items);
            c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
            c.addActionListener(e -> { if (!syncing) { set.accept(c.getSelectedIndex()); applyUi(); refresh(); } });
            return c;
        }

        JPanel fileRow(String kind, String title, boolean optional) {
            JPanel row = new JPanel(new BorderLayout(4, 0));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            JTextField tf = new JTextField();
            tf.setEditable(false);
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
                pic0Browse = browse; pic0Clear = clear;
            }
            row.add(new JLabel(title), BorderLayout.NORTH);
            row.add(tf, BorderLayout.CENTER);
            row.add(btns, BorderLayout.EAST);
            return row;
        }

        // ---- layout ----
        Container buildContent() {
            JPanel left = new JPanel();
            left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
            left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            JPanel stylePanel = section("LiveArea style");
            String[] labels = new String[STYLES.length];
            for (int i = 0; i < STYLES.length; i++) labels[i] = STYLES[i].label;
            styleBox = combo(labels, i -> p.style = STYLES[i].id);
            styleNote = new JLabel(" ");
            stylePanel.add(styleBox); stylePanel.add(styleNote);
            left.add(stylePanel);

            JPanel imgs = section("Images  (Browse, or drag a file onto a box)");
            imgs.add(fileRow("bg", "Background art", false));
            imgs.add(fileRow("logo", "Logo (transparent PNG)", false));
            imgs.add(fileRow("icon", "Icon art", false));
            imgs.add(fileRow("pic0", "pic0 art (optional, default = background art)", true));
            left.add(imgs);

            JPanel files = section("Files to create");
            iconBox = check("icon0.png", p.makeIcon, v -> p.makeIcon = v);
            pic0Box = check("pic0.png (startup picture: background + logo)", p.makePic0, v -> p.makePic0 = v);
            bgNameBox = combo(new String[]{"bg.png  (tested)", "bg0.png  (name used in the docs)"}, i -> p.bgName = i == 0 ? "bg.png" : "bg0.png");
            files.add(iconBox); files.add(pic0Box);
            files.add(new JLabel("Background file name"));
            files.add(bgNameBox);
            left.add(files);

            JPanel logo = section("Logo");
            wSl = slider("Logo width", 60, CANVAS_W, p.logoWidth, v -> p.logoWidth = v, logo);
            JPanel posRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            xSp = spinner(posRow, "Centre X", 0, -420, 420, v -> { p.posCustom = true; p.logoCx = v; });
            ySp = spinner(posRow, "Centre Y (up)", 142, -250, 250, v -> { p.posCustom = true; p.logoCy = v; });
            logo.add(posRow);
            resetPosBtn = new JButton("Reset location");
            resetPosBtn.addActionListener(e -> { p.posCustom = false; refreshPosSpinners(); refresh(); });
            logo.add(resetPosBtn);
            alignBox = check("Write explicit alignment (align/valign/origin) on the logo image", p.explicitAlign, v -> p.explicitAlign = v);
            logo.add(alignBox);
            gateBox = combo(new String[]{"Gate image: art card with the logo", "Gate image: transparent, logo only"}, i -> p.gateTransparent = i == 1);
            logo.add(gateBox);
            logo.add(new JLabel("<html><small>Drag the logo in the preview, or type its centre. X is to the right, Y is up,"
                    + " both measured from the middle of the page.</small></html>"));
            left.add(logo);

            JPanel crop = section("Background crop");
            fxSl = slider("Focus left / right", 0, 100, p.focusX, v -> p.focusX = v, crop);
            fySl = slider("Focus top / bottom", 0, 100, p.focusY, v -> p.focusY = v, crop);
            left.add(crop);

            JPanel txt = section("Text (anywhere on the page, optional)");
            creditsArea = new JTextArea(3, 20);
            creditsArea.getDocument().addDocumentListener(new DocumentListener() {
                void ch() { if (!syncing) { p.credits = creditsArea.getText(); refresh(); } }
                public void insertUpdate(DocumentEvent e) { ch(); }
                public void removeUpdate(DocumentEvent e) { ch(); }
                public void changedUpdate(DocumentEvent e) { ch(); }
            });
            txt.add(new JScrollPane(creditsArea));
            anchorBox = combo(ANCHOR_LABELS, i -> p.anchor = ANCHORS[i]);
            txt.add(new JLabel("Anchored to"));
            txt.add(anchorBox);
            JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            mxSp = spinner(r1, "Margin side", p.marginX, 0, 840, v -> p.marginX = v);
            mySp = spinner(r1, "Margin top/bottom", p.marginY, 0, 500, v -> p.marginY = v);
            txt.add(r1);
            JPanel r2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            sizeSp = spinner(r2, "Size", p.textSize, 8, 48, v -> p.textSize = v);
            colorBtn = new JButton("Colour");
            colorBtn.addActionListener(e -> {
                Color c = JColorChooser.showDialog(frame, "Text colour", p.textColor);
                if (c != null) { p.textColor = c; colorBtn.setBackground(c); refresh(); }
            });
            shadowBox = check("Shadow", p.shadow, v -> p.shadow = v);
            r2.add(colorBtn); r2.add(shadowBox);
            txt.add(r2);
            txt.add(new JLabel("<html><small>Drag the text in the preview (yellow box). Only one text block works per page."
                    + " Keep margins of 20 or more: 0 can be cut off.</small></html>"));
            left.add(txt);

            JPanel p0 = section("pic0 logo position");
            pic0LogoBox = check("Draw the logo on pic0", p.logoOnPic0, v -> p.logoOnPic0 = v);
            pic0ModeBox = combo(new String[]{"Scaled with the picture (the version you tested)", "Same screen position as on the LiveArea"},
                                i -> p.pic0Screen = i == 1);
            p0.add(pic0LogoBox); p0.add(pic0ModeBox);
            left.add(p0);

            JPanel out = section("Output");
            nameField = new JTextField(p.name);
            nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, nameField.getPreferredSize().height));
            nameField.getDocument().addDocumentListener(new DocumentListener() {
                void ch() { if (!syncing) p.name = nameField.getText(); }
                public void insertUpdate(DocumentEvent e) { ch(); }
                public void removeUpdate(DocumentEvent e) { ch(); }
                public void changedUpdate(DocumentEvent e) { ch(); }
            });
            out.add(new JLabel("Project / game name (exports into its own subfolder)"));
            out.add(nameField);
            guideBox = check("Show guides in the preview (system buttons area)", p.guides, v -> p.guides = v);
            quantBox = new JCheckBox("Preview with the final 256-colour conversion", false);
            quantBox.addActionListener(e -> refresh());
            strictBox = check("Strict size caps (bg 128, startup 64, icon 128, pic0 256 KB)", p.strictCaps, v -> p.strictCaps = v);
            zipBox = new JCheckBox("Also create sce_sys.zip", true);
            JButton export = new JButton("Export sce_sys folder...");
            export.addActionListener(e -> doExport());
            out.add(guideBox); out.add(quantBox); out.add(strictBox); out.add(zipBox); out.add(export);
            left.add(out);

            // dragging in the live preview: text first (it sits on top), then the logo
            live.picker = (ix, iy) -> {
                Rectangle t = textRect(p);
                if (t != null && inflate(t, 6).contains(ix, iy)) return 2;
                Rectangle l = p.gate() ? null : logoOnPage(p);
                if (l != null && inflate(l, 8).contains(ix, iy)) return 1;
                return 0;
            };
            live.onPress = t -> {
                int[] c = logoCentre(p);
                if (c != null) { dragCx = c[0]; dragCy = c[1]; }
                dragText = textRect(p);
            };
            live.onMove = (t, dx, dy) -> {
                if (t == 1) {
                    int[] lim = logoCentreLimits(p);
                    p.posCustom = true;
                    p.logoCx = clamp(dragCx + (int) Math.round(dx), lim[0], lim[1]);
                    p.logoCy = clamp(dragCy - (int) Math.round(dy), lim[2], lim[3]);
                    refreshPosSpinners();
                } else if (t == 2 && dragText != null) {
                    Rectangle r = new Rectangle(dragText.x + (int) Math.round(dx), dragText.y + (int) Math.round(dy), dragText.width, dragText.height);
                    setTextFromRect(p, r);
                    syncing = true;
                    anchorBox.setSelectedIndex(Arrays.asList(ANCHORS).indexOf(p.anchor));
                    mxSp.setValue(p.marginX); mySp.setValue(p.marginY);
                    syncing = false;
                }
                render();
            };

            JScrollPane leftScroll = new JScrollPane(left);
            leftScroll.setBorder(null);
            leftScroll.getVerticalScrollBar().setUnitIncrement(16);
            leftScroll.setPreferredSize(new Dimension(420, 700));

            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("LiveArea page", live);
            tabs.addTab("pic0.png", pic);
            tabs.addTab("icon0.png", ico);

            JPanel root = new JPanel(new BorderLayout());
            root.add(leftScroll, BorderLayout.WEST);
            root.add(tabs, BorderLayout.CENTER);
            status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            root.add(status, BorderLayout.SOUTH);
            applyUi();
            return root;
        }

        static Rectangle inflate(Rectangle r, int m) { return new Rectangle(r.x - m, r.y - m, r.width + 2 * m, r.height + 2 * m); }

        /** Show the logo centre (resolved default or custom) in the spinners. */
        void refreshPosSpinners() {
            int[] c = logoCentre(p);
            if (c == null) return;
            boolean was = syncing;
            syncing = true;
            xSp.setValue(c[0]); ySp.setValue(c[1]);
            syncing = was;
        }

        /** Enable / disable controls to match the chosen style and file options. */
        void applyUi() {
            boolean gate = p.gate();
            Style st = p.st();
            styleNote.setText(st.tested
                ? "<html><small>Tested on a Vita. The logo is a frame image, placed freely on the art.</small></html>"
                : "<html><small>Experimental: follows published Vita templates, not tested on hardware yet. The Vita draws the"
                  + " start gate at a fixed spot (" + st.gateX + ", " + st.gateY + " from the page centre), so the logo is the gate image.</small></html>");
            xSp.setEnabled(!gate); ySp.setEnabled(!gate); resetPosBtn.setEnabled(!gate); alignBox.setEnabled(!gate);
            gateBox.setEnabled(gate);
            pic0LogoBox.setEnabled(p.makePic0); pic0ModeBox.setEnabled(p.makePic0 && p.logoOnPic0);
            fields.get("pic0").setEnabled(p.makePic0);
            if (pic0Browse != null) { pic0Browse.setEnabled(p.makePic0); pic0Clear.setEnabled(p.makePic0); }
            fields.get("icon").setEnabled(p.makeIcon);
            refreshPosSpinners();
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
            styleBox.setSelectedIndex(Arrays.asList(STYLES).indexOf(p.st()));
            for (String k : new String[]{"bg", "logo", "icon", "pic0"}) {
                File f = k.equals("bg") ? p.bgFile : k.equals("logo") ? p.logoFile : k.equals("icon") ? p.iconFile : p.pic0File;
                fields.get(k).setText(f == null ? "" : f.getAbsolutePath());
            }
            iconBox.setSelected(p.makeIcon); pic0Box.setSelected(p.makePic0);
            bgNameBox.setSelectedIndex(p.bgName.equals("bg0.png") ? 1 : 0);
            wSl.setValue(p.logoWidth);
            alignBox.setSelected(p.explicitAlign);
            gateBox.setSelectedIndex(p.gateTransparent ? 1 : 0);
            fxSl.setValue(p.focusX); fySl.setValue(p.focusY);
            creditsArea.setText(p.credits);
            anchorBox.setSelectedIndex(Math.max(0, Arrays.asList(ANCHORS).indexOf(p.anchor)));
            mxSp.setValue(p.marginX); mySp.setValue(p.marginY); sizeSp.setValue(p.textSize);
            colorBtn.setBackground(p.textColor); shadowBox.setSelected(p.shadow);
            pic0LogoBox.setSelected(p.logoOnPic0); pic0ModeBox.setSelectedIndex(p.pic0Screen ? 1 : 0);
            nameField.setText(p.name);
            guideBox.setSelected(p.guides); strictBox.setSelected(p.strictCaps);
            syncing = false;
            applyUi();
            refresh();
        }

        void render() {
            final Project snap = p.copy();
            final boolean q = quantBox != null && quantBox.isSelected();
            final int my = ++gen;
            new SwingWorker<BufferedImage[], Void>() {
                @Override protected BufferedImage[] doInBackground() {
                    Assets a = build(snap);
                    if (q) {
                        a.bg = quantize(a.bg, 256, true, null);
                        if (a.pic0 != null) a.pic0 = quantize(a.pic0, 256, true, null);
                        if (a.icon != null) a.icon = quantize(a.icon, 256, true, null);
                        if (snap.gate()) a.startup = quantize(a.startup, 256, true, a.startupMask);
                    }
                    return new BufferedImage[]{compose(snap, a), a.pic0, a.icon};
                }
                @Override protected void done() {
                    if (my != gen) return;
                    try {
                        BufferedImage[] r = get();
                        live.boxLogo = snap.gate() ? null : logoOnPage(snap);
                        live.boxText = textRect(snap);
                        live.setImage(r[0]);
                        pic.hint = "pic0.png is switched off"; pic.setImage(r[1]);
                        ico.hint = "icon0.png is switched off"; ico.setImage(r[2]);
                        String missing = snap.validate();
                        status.setText(missing != null ? missing : snap.gate()
                            ? "Gate position comes from the style. Export converts every image to 256-colour PNGs and checks their size."
                            : "Ready. Export converts every image to the Vita formats and checks their size.");
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
                            "Overwrite?", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) return;
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
            JMenuItem open = new JMenuItem("Open project..."); open.addActionListener(e -> openProject());
            JMenuItem save = new JMenuItem("Save project..."); save.addActionListener(e -> saveProject());
            JMenuItem exp = new JMenuItem("Export sce_sys..."); exp.addActionListener(e -> doExport());
            JMenuItem quit = new JMenuItem("Quit"); quit.addActionListener(e -> quit());
            file.add(open); file.add(save); file.addSeparator(); file.add(exp); file.addSeparator(); file.add(quit);
            mb.add(file);
            frame.setJMenuBar(mb);
            frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { quit(); } });
            if (settingsFile.isFile()) { try { p.load(settingsFile); } catch (IOException ignored) { } }
            syncUi();
            frame.setSize(1240, 820);
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
    //  Command line
    // =====================================================================
    static void help() {
        System.out.println(
            "Vita LiveArea Editor\n\n"
          + "  java -jar LiveAreaEditor.jar                 open the editor\n"
          + "  java -jar LiveAreaEditor.jar --export [options]\n\n"
          + "Images (any format Java can read):\n"
          + "  --bg FILE  --logo FILE  [--icon FILE]  [--pic0 FILE]\n"
          + "Output:\n"
          + "  --out DIR            folder to export into (default: current folder)\n"
          + "  --name \"Game\"        export into DIR/Game/ instead of straight into DIR\n"
          + "  --zip                also write sce_sys.zip\n"
          + "  --no-icon  --no-pic0       leave icon0.png / pic0.png out\n"
          + "  --bg-name bg.png|bg0.png   file name of the background (default bg.png)\n"
          + "  --strict-size              use the conservative size caps\n"
          + "Style and logo:\n"
          + "  --style ad0|a1|a2|a3|a4|a5|psmobile   (default ad0; the others are experimental)\n"
          + "  --logo-width N (276)\n"
          + "  --logo-x N --logo-y N      ad0: logo centre from the page centre, X right, Y up (default: x 0, template y 80)\n"
          + "  --explicit-align           ad0: write align/valign/origin on the logo image\n"
          + "  --gate-transparent         gate styles: logo only, transparent\n"
          + "  --pic0-screen              pic0: put the logo at the same screen position as on the LiveArea\n"
          + "  --no-pic0-logo             pic0: no logo\n"
          + "  --focus-x N --focus-y N    crop focus 0..100 (50)\n"
          + "Text:\n"
          + "  --credits \"Line 1\\nLine 2\"\n"
          + "  --text-anchor bl|bc|br|tl|tc|tr   (bl)   --text-x N (20, side margin)   --text-y N (20, top/bottom margin)\n"
          + "  --text-size N (18)  --color RRGGBB (ffffff)  --no-shadow\n"
          + "  --project FILE       load settings from a .lap file first\n");
    }

    static int cli(String[] args) throws Exception {
        Map<String, String> o = new HashMap<>();
        List<String> flags = Arrays.asList("export", "zip", "no-shadow", "no-pic0-logo", "no-icon", "no-pic0", "strict-size",
                "explicit-align", "gate-transparent", "pic0-screen", "help", "selftest-ui");
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) throw new IllegalArgumentException("Unexpected argument: " + a);
            String k = a.substring(2);
            o.put(k, flags.contains(k) ? "1" : (i + 1 < args.length ? args[++i] : ""));
        }
        if (o.containsKey("help")) { help(); return 0; }
        Project p = new Project();
        if (o.containsKey("project")) p.load(new File(o.get("project")));
        for (String k : new String[]{"bg", "logo", "icon", "pic0"}) if (o.containsKey(k)) p.setImage(k, new File(o.get(k)));
        if (o.containsKey("selftest-ui")) return selftestUi(p);
        if (o.containsKey("style")) {
            if (!knownStyle(o.get("style"))) throw new IllegalArgumentException("Unknown style \"" + o.get("style") + "\". Use: ad0, a1, a2, a3, a4, a5, psmobile");
            p.style = o.get("style");
        }
        if (o.containsKey("name")) p.name = o.get("name");
        if (o.containsKey("bg-name")) {
            if (!o.get("bg-name").equals("bg.png") && !o.get("bg-name").equals("bg0.png")) throw new IllegalArgumentException("--bg-name must be bg.png or bg0.png");
            p.bgName = o.get("bg-name");
        }
        if (o.containsKey("credits")) p.credits = o.get("credits").replace("\\n", "\n");
        if (o.containsKey("logo-width")) p.logoWidth = Integer.parseInt(o.get("logo-width"));
        if (o.containsKey("logo-x") || o.containsKey("logo-y")) {
            int[] def = logoCentre(p);
            p.posCustom = true;
            p.logoCx = o.containsKey("logo-x") ? Integer.parseInt(o.get("logo-x")) : (def == null ? 0 : def[0]);
            p.logoCy = o.containsKey("logo-y") ? Integer.parseInt(o.get("logo-y")) : (def == null ? 142 : def[1]);
        }
        if (o.containsKey("focus-x")) p.focusX = Integer.parseInt(o.get("focus-x"));
        if (o.containsKey("focus-y")) p.focusY = Integer.parseInt(o.get("focus-y"));
        if (o.containsKey("text-anchor")) {
            if (!Arrays.asList(ANCHORS).contains(o.get("text-anchor"))) throw new IllegalArgumentException("--text-anchor must be one of bl bc br tl tc tr");
            p.anchor = o.get("text-anchor");
        }
        if (o.containsKey("text-x")) p.marginX = Integer.parseInt(o.get("text-x"));
        if (o.containsKey("text-y")) p.marginY = Integer.parseInt(o.get("text-y"));
        if (o.containsKey("text-size")) p.textSize = Integer.parseInt(o.get("text-size"));
        if (o.containsKey("color")) p.textColor = new Color(Integer.parseInt(o.get("color"), 16));
        if (o.containsKey("no-shadow")) p.shadow = false;
        if (o.containsKey("no-pic0-logo")) p.logoOnPic0 = false;
        if (o.containsKey("no-icon")) p.makeIcon = false;
        if (o.containsKey("no-pic0")) p.makePic0 = false;
        if (o.containsKey("strict-size")) p.strictCaps = true;
        if (o.containsKey("explicit-align")) p.explicitAlign = true;
        if (o.containsKey("gate-transparent")) p.gateTransparent = true;
        if (o.containsKey("pic0-screen")) p.pic0Screen = true;
        System.out.println(export(p, new File(o.getOrDefault("out", ".")), o.containsKey("zip")));
        return 0;
    }

    /** Builds the whole GUI without a window (works with -Djava.awt.headless=true). */
    static int selftestUi(Project loaded) throws Exception {
        Gui g = new Gui();
        Container c = g.buildContent();
        g.p.bgFile = loaded.bgFile; g.p.bg = loaded.bg; g.p.logoFile = loaded.logoFile; g.p.logo = loaded.logo;
        g.p.iconFile = loaded.iconFile; g.p.icon = loaded.icon;
        g.p.credits = "Line one\nLine two";
        for (String st : new String[]{"ad0", "a1", "psmobile"}) {
            g.p.style = st;
            Assets a = build(g.p.copy());
            BufferedImage live = compose(g.p, a);
            System.out.println("style " + st + ": GUI built (" + c.getComponentCount() + " parts), preview " + live.getWidth() + "x" + live.getHeight());
        }
        return 0;
    }

    public static void main(String[] args) {
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
