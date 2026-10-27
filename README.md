VITA LIVEAREA EDITOR
====================
Makes an sce_sys folder for a Vita VPK from your own art: icon0.png, pic0.png,
the LiveArea background, startup.png and template.xml. Every image is converted
to the format the Vita expects, so you can feed it any PNG/JPG at any size.

RUN
  Double-click LiveAreaEditor.jar   (needs Java 11 or newer)
  or:  java -jar LiveAreaEditor.jar
  On Linux install a full JRE (not the "headless" one); see "Shortcut" below.

QUICK USE
  1. Pick (or drag in) Background art, Logo (transparent PNG) and Icon art.
  2. Place the logo (drag it in the preview) and the text (drag the yellow box).
  3. Type the project / game name, then Export. You get
     <chosen folder>/<name>/sce_sys   and, if ticked, <name>/sce_sys.zip.
     Exporting the same name again asks before overwriting.

FILES TO CREATE
  icon0.png and pic0.png can each be switched off. pic0 is the startup picture:
  your background art with the logo on it (or its own art if you give one).
  The background file can be called bg.png (the name that was tested on a Vita)
  or bg0.png (the name used in the Vita developer docs); template.xml follows.

LIVEAREA STYLES
  ad0 ......... full-screen art with the logo anywhere on it. TESTED on a Vita.
  a1 a2 a3 a4 . start gate in the centre.                     EXPERIMENTAL
  a5 psmobile . start gate on the right.                      EXPERIMENTAL
  In gate styles the Vita draws the 280x158 start image at a fixed spot, so the
  logo location controls are off. startup.png becomes either a card cut from your
  art with the logo on it, or just the logo with transparent surroundings. The
  gate styles are built from published templates and documentation and have NOT
  been tested on hardware: please test before releasing anything that uses them.

LOGO LOCATION (ad0)
  Drag the logo in the preview, or type its centre. X is to the right and Y is up,
  both measured from the middle of the page (so 0,0 is the centre). The tool works
  out the x / y that go into template.xml for you.
  - Default spot = the tested file: no x, y="80".
  - "Write explicit alignment" adds align="center" valign="center" origin="frame"
    to the logo image. That is how the Vita docs describe the offsets, but the
    plain form is the one that was tested, so it is off by default.
  - The top 44 px of the page belong to the system buttons; dragging stops there.

TEXT ANYWHERE
  Choose an anchor (bottom/top x left/centre/right) and margins, or just drag the
  text. The tool picks the nearest anchor, so any spot on the page is reachable.
  Bottom left is the form that was tested on a Vita; the other anchors use the
  documented margin attributes but are untested.
  Only ONE text block works per page. Extra text blocks in the same liveitem are
  ignored by the Vita, and extra liveitems flip like slides instead of showing
  together. Put all lines in the one block (one line per line).
  Keep margins at 20 or more; 0 can be cut off. The preview font is approximate.

pic0 LOGO POSITION
  "Scaled with the picture" keeps the version that was tested earlier.
  "Same screen position" puts the logo exactly where it appears on the real
  screen (the page sits at 60,44 on the 960x544 screen). Pick whichever looks
  right on your Vita.

IMAGE FORMATS AND SIZE
  icon0.png      128x128   8-bit indexed (256 colours)
  pic0.png       960x544   8-bit indexed
  bg / bg0.png   840x500   8-bit indexed
  startup.png    ad0: 280x158 RGBA with transparent surroundings (as tested)
                 gate styles: 280x158 8-bit indexed (card, or logo + transparency)
  Pictures with few colours keep their exact colours; others get an adaptive
  palette with dithering. Each file is kept under 420 KB (the limit mentioned in
  the Vita samples). If one is too big the tool tries undithered, then fewer
  colours, and tells you. "Strict size caps" uses tighter limits (bg 128, startup
  64, icon0 128, pic0 256 KB) at some cost in quality.

OTHER
  - Settings are remembered between runs. File > Save project keeps a .lap file.
  - param.sfo and eboot.bin are not created; this tool only makes sce_sys.
  - The same things work without a window: java -jar LiveAreaEditor.jar --help

SHORTCUT (Linux)
  ~/.local/share/applications/livearea-editor.desktop :
    [Desktop Entry]
    Type=Application
    Name=Vita LiveArea Editor
    Exec=java -jar /full/path/to/LiveAreaEditor.jar
    Terminal=false
  then: chmod +x that file.

WHERE THE LAYOUT NUMBERS COME FROM
  Page size 840x500 at (60,44) on the 960x544 screen, the 44 px system strip, the
  centre-based X-right / Y-up convention, frame-relative x/y in template.xml, the
  ad0 frame (840x414, centre 43 px below the page centre) and the gate positions
  of each style are measurements and conventions documented by the LiveAreaDesigner
  project (MIT, by Roberto Tortora), the PS Vita developer wiki (template.xml) and
  the vitasdk livearea sample. This tool's code is original and shares none of
  theirs.

REBUILD FROM SOURCE
  javac -d classes LiveAreaEditor.java
  jar --create --file LiveAreaEditor.jar --main-class LiveAreaEditor -C classes .
