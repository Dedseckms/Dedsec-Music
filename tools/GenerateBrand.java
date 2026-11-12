import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** The Android adaptive icon is the single source for desktop and installer marks. */
class GenerateBrand {
    static String attr(Element e, String name) { return e.getAttribute("android:" + name); }
    static Color color(String value) {
        long n = Long.parseLong(value.substring(1), 16);
        return new Color((int)n, value.length() == 9);
    }
    static Path2D path(String data) {
        var matcher = Pattern.compile("[A-Za-z]|[-+]?(?:[0-9]*\\.[0-9]+|[0-9]+)").matcher(data);
        var tokens = new ArrayList<String>(); while (matcher.find()) tokens.add(matcher.group());
        var p = new Path2D.Double(); char command = 'M', previous = 'M'; double x=0,y=0,cx=0,cy=0; int i=0;
        while (i < tokens.size()) {
            if (Character.isLetter(tokens.get(i).charAt(0))) command = tokens.get(i++).charAt(0);
            if (command == 'Z' || command == 'z') { p.closePath(); previous=command; continue; }
            int count = switch(command) { case 'M','L' -> 2; case 'H','V' -> 1; case 'C' -> 6; case 'S' -> 4; default -> throw new IllegalArgumentException("Unsupported Android path command " + command); };
            var v = new double[count]; for (int k=0;k<count;k++) v[k]=Double.parseDouble(tokens.get(i++));
            switch(command) {
                case 'M' -> { x=v[0];y=v[1];p.moveTo(x,y); }
                case 'L' -> { x=v[0];y=v[1];p.lineTo(x,y); }
                case 'H' -> { x=v[0];p.lineTo(x,y); }
                case 'V' -> { y=v[0];p.lineTo(x,y); }
                case 'C' -> { p.curveTo(v[0],v[1],v[2],v[3],v[4],v[5]);cx=v[2];cy=v[3];x=v[4];y=v[5]; }
                case 'S' -> { double sx=previous=='C'||previous=='S'?2*x-cx:x, sy=previous=='C'||previous=='S'?2*y-cy:y; p.curveTo(sx,sy,v[0],v[1],v[2],v[3]);cx=v[0];cy=v[1];x=v[2];y=v[3]; }
            }
            previous=command; if(command=='M')command='L';
        }
        return p;
    }
    public static void main(String[] args) throws Exception {
        var factory=DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        var xml=factory.newDocumentBuilder().parse(Path.of("app/src/main/res/drawable/ic_launcher_foreground.xml").toFile());
        var backgroundXml=factory.newDocumentBuilder().parse(Path.of("app/src/main/res/values/colors.xml").toFile());
        String background=""; var colors=backgroundXml.getElementsByTagName("color");
        for(int i=0;i<colors.getLength();i++) { var c=(Element)colors.item(i); if(c.getAttribute("name").equals("launcher_background"))background=c.getTextContent().trim(); }
        if(background.isEmpty())throw new IllegalArgumentException("Android launcher background missing");
        var master=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_ARGB);var g=master.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.scale(4,4);
        g.setColor(color(background));g.fill(new RoundRectangle2D.Double(4,4,248,248,116,116));
        g.translate(4,4);g.scale(248.0/72,248.0/72);g.translate(-18,-18);
        var svg=new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"256\" height=\"256\" viewBox=\"18 18 72 72\">\n<rect x=\"18\" y=\"18\" width=\"72\" height=\"72\" rx=\"16\" fill=\""+background+"\"/>\n");
        var paths=xml.getElementsByTagName("path");
        for(int i=0;i<paths.getLength();i++) {
            var e=(Element)paths.item(i);var geometry=path(attr(e,"pathData"));String fill=attr(e,"fillColor"), stroke=attr(e,"strokeColor"), width=attr(e,"strokeWidth");
            if(!fill.isEmpty()&&color(fill).getAlpha()>0){g.setColor(color(fill));g.fill(geometry);} else fill="none";
            if(!stroke.isEmpty()){g.setColor(color(stroke));g.setStroke(new BasicStroke(Float.parseFloat(width),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.draw(geometry);}
            svg.append("<path d=\"").append(attr(e,"pathData")).append("\" fill=\"").append(fill).append("\"");
            if(!stroke.isEmpty())svg.append(" stroke=\"").append(stroke).append("\" stroke-width=\"").append(width).append("\" stroke-linecap=\"round\"");
            svg.append("/>\n");
        }
        g.dispose();svg.append("</svg>\n");Files.createDirectories(Path.of("branding"));Files.writeString(Path.of("branding/icon.svg"),svg);
        var iosIcon = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
        var iosGraphics = iosIcon.createGraphics(); iosGraphics.setColor(color(background)); iosGraphics.fillRect(0, 0, 1024, 1024);
        iosGraphics.drawImage(master, 0, 0, null); iosGraphics.dispose();
        var iosPath = Path.of("ios/Resources/Assets.xcassets/AppIcon.appiconset"); Files.createDirectories(iosPath);
        ImageIO.write(iosIcon, "png", iosPath.resolve("AppIcon.png").toFile());
        int[] sizes={16,24,32,48,64,128,256};var images=new ArrayList<byte[]>();
        for(int size:sizes){var bitmap=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);var graphics=bitmap.createGraphics();graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);graphics.drawImage(master,0,0,size,size,null);graphics.dispose();var output=new ByteArrayOutputStream();ImageIO.write(bitmap,"png",output);images.add(output.toByteArray());}
        var png=images.get(images.size()-1);Files.write(Path.of("branding/app-icon.png"),png);Files.write(Path.of("desktop/src/main/resources/app-icon.png"),png);
        int offset=6+16*sizes.length,total=offset;for(var bytes:images)total+=bytes.length;
        var ico=ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);ico.putShort((short)0).putShort((short)1).putShort((short)sizes.length);
        for(int i=0;i<sizes.length;i++){ico.put((byte)sizes[i]).put((byte)sizes[i]).put((byte)0).put((byte)0).putShort((short)1).putShort((short)32).putInt(images.get(i).length).putInt(offset);offset+=images.get(i).length;}
        for(var bytes:images)ico.put(bytes);Files.write(Path.of("branding/app-icon.ico"),ico.array());
        System.out.println("Android vector icon exported to desktop PNG, SVG and seven-size ICO.");
    }
}
