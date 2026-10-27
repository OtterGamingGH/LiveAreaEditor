VITA LIVEAREA EDITOR
====================
Makes a complete sce_sys folder for a Vita VPK from your own art, in the standard
LiveArea "ad0" style: a full-screen background with your logo on top, an optional
credits text in the corner, and a matching pic0.png. Conversion is built in, so you
can feed it any PNG/JPG at any size.

RUN
  Double-click LiveAreaEditor.jar   (needs Java 11 or newer)
  or:  java -jar LiveAreaEditor.jar

USE
  1. Pick (or drag in) Background art, Logo (transparent PNG) and Icon art.
     pic0 art is optional - it defaults to the background art.
  2. Size and place the logo (see LOGO LOCATION below). pic0 gets the logo at the
     matching position automatically.
  3. Optional credits text for the bottom-left (one line per line).
  4. Type the project / game name. Export makes <chosen folder>/<name>/sce_sys
     (and <name>/sce_sys.zip if ticked), so every game gets its own folder.
     Exporting the same name again asks before overwriting - no numbered copies.
  5. Export sce_sys folder...

LOGO LOCATION
  Drag the logo in the LiveArea preview (it has a dashed box), or use the controls:
    Logo width ................ how big the logo is
    Left / right offset ....... moves the logo sideways from the centre
    Distance from top ......... the y value of startup.png in template.xml (default 80)
    Reset location ............ back to centred, y = 80
  Sideways movement happens inside startup.png, which is 280 px wide, so the logo
  needs room to move: make it narrower than 276 first.
  Moving up/down changes the y value in template.xml. The logo on pic0 follows
  automatically so both always sit in the same place.

WHAT IT WRITES
  sce_sys/icon0.png                       128x128  8-bit indexed (256 colours)
  sce_sys/pic0.png                        960x544  8-bit indexed (256 colours)
  sce_sys/livearea/contents/bg.png        840x500  8-bit indexed (256 colours)
  sce_sys/livearea/contents/startup.png   280x158  RGBA, logo on a transparent canvas
  sce_sys/livearea/contents/template.xml  LiveArea style "ad0", startup.png shown 560x316

NOTES
  - Credits go in ONE text block with plain line breaks. Several text blocks in
    one liveitem only showed the first one on a real Vita.
  - Keep the credits' bottom margin at 20 or more; 0 can be cut off.
  - The text in the preview is approximate. The Vita draws its own font.
  - The tool never writes an x value into template.xml: the Vita centres the image
    by itself, and a fixed x moved the logo off-centre on a real Vita.
  - Settings are remembered between runs. File > Save project keeps a .lap file.
  - param.sfo / eboot.bin are not created; this tool only makes sce_sys.

COMMAND LINE (no window)
  java -jar LiveAreaEditor.jar --export --bg bg.png --logo logo.png --icon icon.png \
       --out folder --name "Game" --zip --credits "Line 1\nLine 2" \
       --logo-width 200 --logo-x 30 --screen-y 120
  java -jar LiveAreaEditor.jar --help      shows every option

REBUILD FROM SOURCE
  javac -d classes LiveAreaEditor.java
  jar --create --file LiveAreaEditor.jar --main-class LiveAreaEditor -C classes .
