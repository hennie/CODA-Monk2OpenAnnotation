/*
 * navis2oac - Simple converter from navis formatted files to Open Annotation
 * RDF/XML format. Complies to OAC phase II beta spec.
 */

package navis2oac;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 *
 * @author hennieb
 */
public class Navis2OAC {
    private static final String NAVIS_ID = "ID";
    private static final String NAVIS_TXT = "TXT";
    private static final String NAVIS_USER = "USER";
    private static final String NAVIS_TIME = "TIME";
    private static final String NAVIS_PAGE_ID = "PAGE_ID";
    private static final String NAVIS_LINE_ID = "LINE_ID";
    private static final String NAVIS_Y1 = "Y1";
    private static final String NAVIS_Y2 = "Y2";
    private static final String NAVIS_ZONE_ID = "ZONE_ID";
    private static final String NAVIS_X = "X";
    private static final String NAVIS_Y = "Y";
    private static final String NAVIS_W = "W";
    private static final String NAVIS_H = "H";
    private static final String NAVIS_TRAILING_TAGS = "TRAILING_TAGS";
    private static final String NAVIS_LINE_IMAGE_URL = "LINE_IMAGE_URL";

    private static final String WORDSEPARATOR = " ";
    private static final int WORDSEPARATOR_LENGTH = 1;
    private static final String PAGESEPARATOR = " ";
    private static final int PAGESEPARATOR_LENGTH = 1;

    // keys for arguments in argumentMap
    private static final String INPUTFILE = "input";
    private static final String OUTPUTFILE = "output";
    private static final String XOFFSET = "x";
    private static final String YOFFSET = "y";
    private static final String XCANVAS = "xcanvas";
    private static final String YCANVAS = "ycanvas";
    private static final String SCALING_FACTOR = "scalefactor";
    private static final String LINE_STRIP_FORMAT = "line_strip_format";

    // for use by line strip cutout service
    private int _scanWidth = 0;
    private int _scanHeight = 0;
    private int _rotationAngle = 0;
    private int _xOrigin = 0;
    private int _yOrigin = 0;

    private SesameStore _sesameStore;

    private Map<String,String> _arguments = new HashMap<String,String>();

    private List<Map<String,String>> _navisAnnotations;
    private TreeSet<Line> _orderedLinesForPage = new TreeSet<Line>();
    private Map<String,TreeSet<TextSegment>> _orderedWordsForLines = new HashMap<String,TreeSet<TextSegment>>();
    private Map<String,TextSegment> _segmentsForLines = new HashMap<String,TextSegment>();
    private Map<String,TextSegment> _textSegments = new HashMap<String,TextSegment>();

    private String _pageID;
    private String _blockID;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        new Navis2OAC().startConversion(args);
    }

    public void startConversion(String[] args) {
        File outputFile = null;

        _navisAnnotations = new ArrayList();
        _sesameStore = new SesameStore();

        // process arguments (including input file name(s) )
        processArgs(args);

        // read and parse input file(s)
        String outputFileName = _arguments.get(OUTPUTFILE);
        if (outputFileName != null) {
            outputFile = new File(outputFileName);
            if (!outputFile.exists()) {
                try {
                    boolean success = outputFile.createNewFile();
                } catch (IOException ex) {
                    Logger.getLogger(Navis2OAC.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }

        String inputFile = _arguments.get(INPUTFILE);

        if (inputFile != null) {
            String lineStrips = _arguments.get(LINE_STRIP_FORMAT);

            if (lineStrips != null && lineStrips.equals("true")) {
                parseLineStripFile(inputFile);
            } else {
                parseInputFile(inputFile);
            }
        }

        // determine line and word order, text offsets, line and page texts and rects
        deriveImplicitInformation();

        // apply possible pixel offset to line and word zone regions
        applyOffsets(_arguments.get(XOFFSET), _arguments.get(YOFFSET));

        // apply overall scaling factor to spatial coordinates
        applyScaling(_arguments.get(SCALING_FACTOR));

        // set Canvas dimensions from arguments, if present. Otherwise keep estimate as
        // set in 'deriveImplicitInformation
        setCanvasDimensions(_arguments.get(XCANVAS), _arguments.get(YCANVAS), _arguments.get(SCALING_FACTOR));

        // create triples and add them to the RDF store
        String lineStrips = _arguments.get(LINE_STRIP_FORMAT);
        if (lineStrips != null && lineStrips.equals("true")) { 
            addTriplesToStoreLS();
        } else {
            addTriplesToStore();
        }

        // export OA graph to RDF/XML file (or output format?)
        if (outputFile != null && outputFile.exists()) {
            _sesameStore.exportToRDFXML(outputFile);
        } else {
            _sesameStore.exportToRDFXML(null);
        }
    }


    public void processArgs(String[] args) {
        List<String> inputArgs = new ArrayList<String>();
        inputArgs.addAll(Arrays.asList(args));

        // argument syntax:
        // --inputfile=<filename>
        // --xoffset=<numpixels>
        // --yoffset=<numpixels>
        // --xcanvas=<numpixels>
        // --ycanvas=<numpixels>
        // --scalefactor=<scalefactor> , scale DOWN by ...
        // --linestrips
        // --outputfile=<filename>

        for (String arg : inputArgs) {
            if (arg.startsWith("--inputfile=")) {
                _arguments.put(INPUTFILE, arg.substring(arg.indexOf("=") + 1));
         //       System.out.println(file.substring(file.indexOf("=") + 1));
            } else if (arg.startsWith("--outputfile=")) {
                _arguments.put(OUTPUTFILE, arg.substring(arg.indexOf("=") + 1));
            } else if (arg.startsWith("--xoffset=")) {
                _arguments.put(XOFFSET, arg.substring(arg.indexOf("=") + 1));
         //       System.out.println(file.substring(file.indexOf("=") + 1));
            } else if (arg.startsWith("--yoffset=")) {
                _arguments.put(YOFFSET, arg.substring(arg.indexOf("=") + 1));
         //       System.out.println(file.substring(file.indexOf("=") + 1));
            } else if (arg.startsWith("--xcanvas=")) {
                _arguments.put(XCANVAS, arg.substring(arg.indexOf("=") + 1));
         //      System.out.println(file.substring(file.indexOf("=") + 1));
            } else if (arg.startsWith("--ycanvas=")) {
                _arguments.put(YCANVAS, arg.substring(arg.indexOf("=") + 1));
         //       System.out.println(file.substring(file.indexOf("=") + 1));
            } else if (arg.startsWith("--scalefactor")) {
                _arguments.put(SCALING_FACTOR, arg.substring(arg.indexOf("=") + 1));
            } else if (arg.startsWith("--linestrips")) {
                _arguments.put(LINE_STRIP_FORMAT, "true");
            } else {
                System.err.println("Illegal argument");
                System.exit(1);
            }
        }
    }

    public void parseInputFile(String inputFileName) {
        BufferedReader br = null;

        try {
            br = new BufferedReader(new FileReader(inputFileName));

        } catch (FileNotFoundException ex) {
            Logger.getLogger(Navis2OAC.class.getName()).log(Level.SEVERE, null, ex);
        }

        if (br != null) {
            String line = null;
            try {
                line = br.readLine();
                while (line != null) {
                    parseLine(line);
                    line = br.readLine();
                }

                br.close();

            } catch (IOException ex) {
                Logger.getLogger(Navis2OAC.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    private void parseLine(String line) {
        Map<String,String> navisRecord = new HashMap<String,String>();
        String id = null;

        // split in txt, id, user and time parts
        int start = line.indexOf("<txt>");
        int end = line.indexOf("</txt>");
        if (start >= 0 && end >= 0 && start + 5 < end) {
            navisRecord.put(NAVIS_TXT, line.substring(start + 5, end));
        }

        start = line.indexOf("<id>");
        end = line.indexOf("</id>");
        if (start >= 0 && end >= 0 && start + 4 < end) {
            id = line.substring(start + 4, end);
            navisRecord.put(NAVIS_ID, id);

            StringTokenizer tokenizer = new StringTokenizer(id, "-=");
            while (tokenizer.hasMoreTokens()) {
                String s = tokenizer.nextToken();

                if (s.trim().equals("navis")) {
                    if (tokenizer.hasMoreTokens()) {navisRecord.put(NAVIS_PAGE_ID, tokenizer.nextToken());}
                }
                if (s.equals("line")) {
                    if (tokenizer.hasMoreTokens()) {navisRecord.put(NAVIS_LINE_ID, tokenizer.nextToken());}
                }
                if (s.equals("y1")) {
                    if (tokenizer.hasMoreTokens()) {navisRecord.put(NAVIS_Y1, tokenizer.nextToken());}
                }
                if (s.equals("y2")) {
                    if (tokenizer.hasMoreTokens()) {navisRecord.put(NAVIS_Y2, tokenizer.nextToken());}
                }
                if (s.equals("zone")) {
                    if (tokenizer.hasMoreTokens()) {navisRecord.put(NAVIS_ZONE_ID, tokenizer.nextToken());}
                }
                if (s.equals("x")) {
                    if (tokenizer.hasMoreTokens()) {navisRecord.put(NAVIS_X, tokenizer.nextToken());}
                }
                if (s.equals("y")) {
                    if (tokenizer.hasMoreTokens()) {navisRecord.put(NAVIS_Y, tokenizer.nextToken());}
                }
                if (s.equals("w")) {
                    if (tokenizer.hasMoreTokens()) {navisRecord.put(NAVIS_W, tokenizer.nextToken());}
                }
                if (s.equals("h")) {
                    if (tokenizer.hasMoreTokens()) {navisRecord.put(NAVIS_H, tokenizer.nextToken());}

                    // everything after current token is trailing_tags
                    if (tokenizer.hasMoreTokens()) {
                        String token = tokenizer.nextToken();
                        int pos = id.lastIndexOf(token);
                        if (pos > 0){
                            navisRecord.put(NAVIS_TRAILING_TAGS, id.substring(pos).trim());
                        }
                    }
                }
            }
        }

        start = line.indexOf("<user>");
        end = line.indexOf("</user>");
        if (start >= 0 && end >= 0 && start + 6 < end) {
            navisRecord.put(NAVIS_USER, line.substring(start + 6, end));
        }

        start = line.indexOf("<time>");
        end = line.indexOf("</time>");
        if (start >= 0 && end >= 0 && start + 6 < end) {
            navisRecord.put(NAVIS_TIME, line.substring(start + 6, end));
        }

        _navisAnnotations.add(navisRecord);
    }

    public void parseLineStripFile(String inputFileName) {
    //    System.out.println("processing: " + inputFileName);

        Document doc = null;
        DocumentBuilder builder = null;

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true); // never forget this!

        try {
            builder = factory.newDocumentBuilder();

        } catch (ParserConfigurationException ex) {
            Logger.getLogger(Navis2OAC.class.getName()).log(Level.SEVERE, null, ex);
        }

        XPathFactory xPathFactory = XPathFactory.newInstance();
        XPath xpath = xPathFactory.newXPath();

        // read and parse line strip XML input file
        try {
            doc = builder.parse(inputFileName);

        } catch (SAXException ex) {
            Logger.getLogger(Navis2OAC.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Navis2OAC.class.getName()).log(Level.SEVERE, null, ex);
        }

        extractInfoFromXML(doc, xpath);
    }

    public void extractInfoFromXML(Document doc, XPath xpath) {

        try {
            // retrieve page_image
            XPathExpression expr = xpath.compile("//start_process_cutout");
            Object result = expr.evaluate(doc, XPathConstants.NODESET);
            NodeList nodes = (NodeList) result;

            for (int i = 0; i < nodes.getLength(); i++) {
                Node n = nodes.item(i);

                // get elements from XML
                NodeList topElements = (NodeList) n.getChildNodes();

                for (int j= 0; j < topElements.getLength(); j++) {
                    Node element = topElements.item(j);

                    if (element.getNodeType() == Node.ELEMENT_NODE) {

                        String nodeName = element.getNodeName();
                        String textContent = element.getTextContent();

                        if (!nodeName.equals("linestrips") && !(nodeName.equals("status"))) {
                     //       System.out.println(nodeName + " = " + textContent);

                            if (nodeName.equals("page_image_original")) {
                                _pageID = textContent;
                            } else if(nodeName.equals("page_image_cutout")) {
                                _blockID = textContent;
                            }else if(nodeName.equals("width")) {
                                _scanWidth = Integer.parseInt(textContent);
                            } else if(nodeName.equals("height")) {
                                _scanHeight = Integer.parseInt(textContent);
                            } else if(nodeName.equals("angle")) {
                                _rotationAngle = Integer.parseInt(textContent);
                            } else if(nodeName.equals("x1")) {
                                _xOrigin = Integer.parseInt(textContent);
                            } else if(nodeName.equals("y1")) {
                                _yOrigin = Integer.parseInt(textContent);
                            } else if(nodeName.equals("x2")) {
                                _arguments.put(XCANVAS, textContent);
                            } else if(nodeName.equals("y2")) {
                                _arguments.put(YCANVAS, textContent);
                            }
                        }
                    }
                }
            }

            expr = xpath.compile("//linestrip");
            result = expr.evaluate(doc, XPathConstants.NODESET);
            nodes = (NodeList) result;

            for (int k = 0; k < nodes.getLength(); k++) {
                Map<String,String> navisRecord = new HashMap<String,String>();

                Node m = nodes.item(k);

                // get line elements from XML
                NodeList lineElements = (NodeList) m.getChildNodes();

                for (int j= 0; j < lineElements.getLength(); j++) {
                    Node element = lineElements.item(j);

                    if (element.getNodeType() == Node.ELEMENT_NODE) {

                        String nodeName = element.getNodeName();
                        String textContent = element.getTextContent();

                        if (nodeName.equals("line_image")) {
                            navisRecord.put(NAVIS_LINE_IMAGE_URL, textContent);
                        } else if (nodeName.equals("id")) {
                            navisRecord.put(NAVIS_LINE_ID, textContent);
                        } else if (nodeName.equals("y1")) {
                            navisRecord.put(NAVIS_Y1, textContent);
                        } else if (nodeName.equals("y2")) {
                            navisRecord.put(NAVIS_Y2, textContent);
                        }    
                    }
                }
                navisRecord.put(NAVIS_PAGE_ID, _pageID);

                _navisAnnotations.add(navisRecord);
            }

        } catch (XPathExpressionException ex) {
            Logger.getLogger(Navis2OAC.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public void deriveImplicitInformation() {
        for (Map<String,String> navisRecord : _navisAnnotations) {
     //       System.out.println(navisRecord.toString());

            if (navisRecord.get(NAVIS_X) == null) { // line record, assume that all zone records have X set
                processLineRecord(navisRecord);
            }
            else {  // word zone record
                processWordzoneRecord(navisRecord);
            }

            // add line to lines for page
            if (_pageID == null) _pageID = navisRecord.get(NAVIS_PAGE_ID);

            _orderedLinesForPage.add(new Line(getGlobalLineID(navisRecord)));
        }

        // construct and add TextSegments for lines (where needed) and page
        // add character offsets and ranges on the way

        int pageW = -1;
        int pageH = -1;
        String pageTXT = "";
        int charOffset = 0;
        int lineOffset = 0;
        int lineRange = 0;

        for (Line line : _orderedLinesForPage) { // loop over lines of page
            int lineY = -1;
            int lineW = -1;
            int lineH = -1;
            String lineTXT = "";

            // reset for this line
            lineOffset = charOffset;
            lineRange = 0;

            if  ((_orderedWordsForLines.get(line.lineID) != null) &&
                 (_segmentsForLines.get(line.lineID) == null)) {         // no line segment/line record exists, only wordzones

                // add wordzone text to full page text, set wordzone char offsets
                for (TextSegment seg : _orderedWordsForLines.get(line.lineID)) {
                    String y1 = seg.navisRecord.get(NAVIS_Y1);
                    String y2 = seg.navisRecord.get(NAVIS_Y2);
                    int x = seg.x;
                    int w = seg.w;

                    lineY = Integer.parseInt(y1);
                    lineH = Integer.parseInt(y2) - lineY;
                    lineW = Math.max(lineW, x + w);

                    if (!lineTXT.equals("")) lineTXT += WORDSEPARATOR;
                    lineTXT += seg.navisRecord.get(NAVIS_TXT);

                    seg.textOffset = charOffset;
                    seg.textRange = seg.navisRecord.get(NAVIS_TXT).length();

                    charOffset += seg.textRange + Navis2OAC.WORDSEPARATOR_LENGTH;
                    lineRange += seg.textRange + Navis2OAC.WORDSEPARATOR_LENGTH;
                }
            }

            if (_segmentsForLines.get(line.lineID) != null) {

                TextSegment lineSegment = _segmentsForLines.get(line.lineID);

                lineH = lineSegment.h;
                lineTXT = lineSegment.navisRecord.get(NAVIS_TXT);

                // set offset and range
                lineSegment.textOffset = charOffset;

                if (lineSegment.navisRecord.get(NAVIS_TXT) != null) {
                    lineSegment.textRange = lineSegment.navisRecord.get(NAVIS_TXT).length();
                }

                charOffset += lineSegment.textRange + Navis2OAC.WORDSEPARATOR_LENGTH;
                lineRange += lineSegment.textRange + Navis2OAC.WORDSEPARATOR_LENGTH;

                if (_orderedWordsForLines.get(line.lineID) != null) {    // also wordzones, align their offsets and ranges
                    int offsetInLine = 0;
                    int previousOffsetInLine = 0;

                    for (TextSegment seg : _orderedWordsForLines.get(line.lineID)) {
                        String wzText = seg.navisRecord.get(NAVIS_TXT);

                        int wzPos = lineTXT.indexOf(wzText, offsetInLine);  // first occurance in lineTXT, starting at last position
                        if (wzPos >= 0) {   // wzText found
                            seg.textOffset = lineOffset + wzPos;
                            previousOffsetInLine = offsetInLine;
                            offsetInLine += wzPos;
                        } else {    // possibly more wz annots start at same position, search from previous offset
                            wzPos = lineTXT.indexOf(wzText, previousOffsetInLine);
                            if (wzPos >= 0) {
                                seg.textOffset = lineOffset + wzPos;
                                offsetInLine = previousOffsetInLine + wzPos;
                            }
                        }

                        seg.textRange = wzText.length();
                    }
                }
            }

            pageW = Math.max(pageW, lineW);
            pageH = Math.max(pageH, lineY + lineH);

            if (!pageTXT.equals("")) pageTXT += PAGESEPARATOR;
            pageTXT += lineTXT;
        }

        // for all lines of page, set line width

        // and add segment for page
        TextSegment ps = new TextSegment(0, 0, pageW, pageH, pageTXT, 0, lineOffset + lineRange - Navis2OAC.PAGESEPARATOR_LENGTH, null);
        _textSegments.put(_pageID, ps);
    }

    public void processLineRecord(Map<String,String> lineRecord) {
        TextSegment ts = new TextSegment(0,
                Integer.parseInt(lineRecord.get(NAVIS_Y1)),
                -1,
                Integer.parseInt(lineRecord.get(NAVIS_Y2)) - Integer.parseInt(lineRecord.get(NAVIS_Y1)),
                lineRecord.get(NAVIS_TXT),
                -1, -1, lineRecord);


        _textSegments.put(getGlobalLineID(lineRecord), ts);

        // add text segment to segmentsForLines
        _segmentsForLines.put(getGlobalLineID(lineRecord),ts);
    }

    public void processWordzoneRecord(Map<String,String> wordzoneRecord) {
        TextSegment ts = new TextSegment(Integer.parseInt(wordzoneRecord.get(NAVIS_X)),
                Integer.parseInt(wordzoneRecord.get(NAVIS_Y1)) + Integer.parseInt(wordzoneRecord.get(NAVIS_Y)),
                Integer.parseInt(wordzoneRecord.get(NAVIS_W)),
                Integer.parseInt(wordzoneRecord.get(NAVIS_H)),
                wordzoneRecord.get(NAVIS_TXT),
                -1, -1, wordzoneRecord);


        _textSegments.put(wordzoneRecord.get(NAVIS_ID), ts);

        // add wordzone segment to words for line
        String navisLineID = getGlobalLineID(wordzoneRecord);
        TreeSet<TextSegment> wordsForLine = _orderedWordsForLines.get(navisLineID);
        if (wordsForLine == null) {
            wordsForLine = new TreeSet<TextSegment>();
            _orderedWordsForLines.put(navisLineID, wordsForLine);
        }
        wordsForLine.add(ts);
    }

    private String getGlobalLineID(Map<String,String> navisRecord) {
        // lineID is supposed to be exactly 3 digits, if necessary padded with 0's
        String lineID = navisRecord.get(NAVIS_LINE_ID);
        while (lineID.length() < 3) {
            lineID = "0" + lineID;
        }

        return navisRecord.get(NAVIS_PAGE_ID) + "-" + lineID;
    }

    private void applyOffsets(String xOffset, String yOffset) {
        int x = 0;
        int y = 0;

        if (xOffset != null) x = Integer.parseInt(xOffset);
        if (yOffset != null) y = Integer.parseInt(yOffset);

        if (x >= 0 && y >= 0 && (x > 0 || y > 0)) {
            for (String segID : _textSegments.keySet()) {
                TextSegment seg = _textSegments.get(segID);

                if (seg.w > 0) {    // do not apply offset to line strips
                    seg.x += x;
                }
                seg.y += y;
            }
        }
    }

    private void applyScaling(String scalingFactor) {
        double s = 0;

        if (scalingFactor != null) s = Double.parseDouble(scalingFactor);

        if (s > 0) {
            for (String segID : _textSegments.keySet()) {
                TextSegment seg = _textSegments.get(segID);

                seg.x = (int) (seg.x/s);
                seg.y = (int) (seg.y/s);
                if (seg.w != -1) seg.w = (int) (seg.w/s); // -1 indicates 'unspecified', do not scale down to zero
                seg.h = (int) (seg.h/s);
            }
        }
    }

    private void setCanvasDimensions(String xCanvas, String yCanvas, String scalingFactor) {
        double s = 0;

        if (scalingFactor != null) s = Double.parseDouble(scalingFactor);

        if (xCanvas != null && yCanvas != null) {
            TextSegment pageSeg = _textSegments.get(_pageID);
            
            pageSeg.w = Integer.parseInt(xCanvas);
            pageSeg.h = Integer.parseInt(yCanvas);

            if (s > 0) {
                pageSeg.w = (int) (pageSeg.w/s);
                pageSeg.h = (int) (pageSeg.h/s);
            }
        }
    }

    public void addTriplesToStore() {
        TextSegment seg = _textSegments.get(_pageID);

        // create Canvas
        // id, type, title, height, width
        URI canvasURI = URI.create(SesameStore.CATCHPLUS + _pageID);

        _sesameStore.addTriple(canvasURI, SesameStore.RDF_TYPE, SesameStore.SC_CANVAS);
        _sesameStore.addTriple(canvasURI, SesameStore.DC_TITLE, "Canvas for " + _pageID);
        _sesameStore.addTriple(canvasURI, SesameStore.EXIF_HEIGHT, Integer.toString(seg.h));
        _sesameStore.addTriple(canvasURI, SesameStore.EXIF_WIDTH, Integer.toString(seg.w));

        // create full page text annotation
        URI annotationURI = URI.create("urn:uuid:" + UUID.randomUUID());
        URI fullTextBodyURI = URI.create("urn:uuid:" + UUID.randomUUID());

        _sesameStore.addTriple(annotationURI, SesameStore.RDF_TYPE, SesameStore.OAC_ANNOTATION);
        _sesameStore.addTriple(annotationURI, SesameStore.RDF_TYPE, SesameStore.CP_TEXTANNOTATION);
        _sesameStore.addTriple(annotationURI, SesameStore.OAC_HASBODY, fullTextBodyURI);
        _sesameStore.addTriple(annotationURI, SesameStore.OAC_HASTARGET, canvasURI);
        _sesameStore.addTriple(annotationURI, SesameStore.DC_TITLE, "Full text of page " + _pageID);

        // ... and it's full text Body
        _sesameStore.addTriple(fullTextBodyURI, SesameStore.RDF_TYPE, SesameStore.CNT_CONTENTASTEXT);
        _sesameStore.addTriple(fullTextBodyURI, SesameStore.RDF_TYPE, SesameStore.OAC_BODY);
        _sesameStore.addTriple(fullTextBodyURI, SesameStore.CNT_CHARS, seg.text);
        _sesameStore.addTriple(fullTextBodyURI, SesameStore.CNT_CHARACTERENCODING, "UTF-8");

        // create image annotation
        URI imageAnnotURI = URI.create("urn:uuid:" + UUID.randomUUID());
        URI imageURI = URI.create(SesameStore.CATCHPLUS + _pageID + ".jpg"); //fake URI

        _sesameStore.addTriple(imageAnnotURI, SesameStore.RDF_TYPE, SesameStore.OAC_ANNOTATION);
        _sesameStore.addTriple(imageAnnotURI, SesameStore.RDF_TYPE, SesameStore.CP_IMAGEANNOTATION);
        _sesameStore.addTriple(imageAnnotURI, SesameStore.OAC_HASBODY, imageURI);
        _sesameStore.addTriple(imageAnnotURI, SesameStore.OAC_HASTARGET, canvasURI);
        _sesameStore.addTriple(imageAnnotURI, SesameStore.DC_TITLE, "Image annotation of " + _pageID);

        // create TextAnnotations for each line

        for (Line l : _orderedLinesForPage) {
            TextSegment lineSeg = _textSegments.get(l.lineID);

            if (lineSeg != null) {
                addTriplesForTextSegment(lineSeg, l.lineID, canvasURI, fullTextBodyURI);
            }

            if (_orderedWordsForLines.get(l.lineID) != null) {   // there are wordzone annots for this line
                for (TextSegment wSeg : _orderedWordsForLines.get(l.lineID)) {
                    addTriplesForTextSegment(wSeg, "", canvasURI, fullTextBodyURI);
                }
            }
        }
    }

    private void addTriplesForTextSegment(TextSegment seg, String id, URI canvasURI, URI fullTextURI) {
        URI segAnnotURI = URI.create("urn:uuid:" + UUID.randomUUID());
        URI annotationType;
        if (seg.navisRecord == null) {  // generated TextSegment
            annotationType = SesameStore.CP_TEXTANNOTATION;
        } else if (seg.navisRecord.get(Navis2OAC.NAVIS_ID) == null) {   // TODO: now Linestrip, do better test
            annotationType = SesameStore.CP_LINESTRIP;
        } else {
            annotationType = SesameStore.CP_MONKANNOTATION;
            id = seg.navisRecord.get(Navis2OAC.NAVIS_ID);
        }
        URI constrainedBodyURI = URI.create("urn:uuid:" + UUID.randomUUID());
        URI constrainedTargetURI = URI.create("urn:uuid:" + UUID.randomUUID());

        _sesameStore.addTriple(segAnnotURI, SesameStore.RDF_TYPE, SesameStore.OAC_ANNOTATION);
        _sesameStore.addTriple(segAnnotURI, SesameStore.RDF_TYPE, annotationType);
        _sesameStore.addTriple(segAnnotURI, SesameStore.OAC_HASBODY, constrainedBodyURI);
        _sesameStore.addTriple(segAnnotURI, SesameStore.OAC_HASTARGET, constrainedTargetURI);
        _sesameStore.addTriple(segAnnotURI, SesameStore.DC_TITLE, "Annotation for " + id);
        _sesameStore.addTriple(segAnnotURI, SesameStore.DC_IDENTIFIER, id);

        if (seg.text != null) _sesameStore.addTriple(segAnnotURI, SesameStore.CP_CHARS, seg.text);

        if (seg.navisRecord != null) {  // MonkAnnotation, possibly has extra fields from navis format
            if (seg.navisRecord.get(Navis2OAC.NAVIS_USER) != null)
                _sesameStore.addTriple(segAnnotURI, SesameStore.DCTERMS_CREATOR , seg.navisRecord.get(Navis2OAC.NAVIS_USER));
            if (seg.navisRecord.get(Navis2OAC.NAVIS_TIME) != null)
                _sesameStore.addTriple(segAnnotURI, SesameStore.DCTERMS_CREATED , seg.navisRecord.get(Navis2OAC.NAVIS_TIME));
            if (seg.navisRecord.get(Navis2OAC.NAVIS_TRAILING_TAGS) != null)
                _sesameStore.addTriple(segAnnotURI, SesameStore.CP_TRAILINGTAGS , seg.navisRecord.get(Navis2OAC.NAVIS_TRAILING_TAGS));
        }

        // ... its ConstrainedBody + Constraint
        URI textConstraintURI = URI.create("urn:uuid:" + UUID.randomUUID());

        _sesameStore.addTriple(constrainedBodyURI, SesameStore.RDF_TYPE, SesameStore.OAC_CONSTRAINEDBODY);
        _sesameStore.addTriple(constrainedBodyURI, SesameStore.OAC_CONSTRAINS, fullTextURI);
        _sesameStore.addTriple(constrainedBodyURI, SesameStore.OAC_CONSTRAINEDBY, textConstraintURI);

        String cText = "\"<textsegment offset=\""
                    + seg.textOffset
                    + "\" range=\""
                    + seg.textRange + "\"/>\"";
        _sesameStore.addTriple(textConstraintURI, SesameStore.RDF_TYPE, SesameStore.OAC_CONSTRAINT);
        _sesameStore.addTriple(textConstraintURI, SesameStore.RDF_TYPE, SesameStore.CP_INLINETEXTCONSTRAINT);
        _sesameStore.addTriple(textConstraintURI, SesameStore.RDF_TYPE, SesameStore.CNT_CONTENTASTEXT);
        _sesameStore.addTriple(textConstraintURI, SesameStore.CNT_CHARS, cText);
        _sesameStore.addTriple(textConstraintURI, SesameStore.CNT_CHARACTERENCODING, "UTF-8");

        // ... and its ConstrainedTarget + Constraint
        URI svgConstraintURI = URI.create("urn:uuid:" + UUID.randomUUID());

        _sesameStore.addTriple(constrainedTargetURI, SesameStore.RDF_TYPE, SesameStore.OAC_CONSTRAINEDTARGET);
        _sesameStore.addTriple(constrainedTargetURI, SesameStore.OAC_CONSTRAINS, canvasURI);
        _sesameStore.addTriple(constrainedTargetURI, SesameStore.OAC_CONSTRAINEDBY, svgConstraintURI);

        cText = "\"<rect x=\""
                    + seg.x
                    + "\" y=\""
                    + seg.y
                     + "\" width=\""
                    + seg.w
                     + "\" height=\""
                    + seg.h
                    + "\"/>\"";
        _sesameStore.addTriple(svgConstraintURI, SesameStore.RDF_TYPE, SesameStore.OAC_CONSTRAINT);
        _sesameStore.addTriple(svgConstraintURI, SesameStore.RDF_TYPE, SesameStore.CP_SVGCONSTRAINT);
        _sesameStore.addTriple(svgConstraintURI, SesameStore.RDF_TYPE, SesameStore.CNT_CONTENTASTEXT);
        _sesameStore.addTriple(svgConstraintURI, SesameStore.DC_FORMAT, "image/svg+xml");
        _sesameStore.addTriple(svgConstraintURI, SesameStore.CP_CHARS, cText);
        _sesameStore.addTriple(svgConstraintURI, SesameStore.CNT_CHARACTERENCODING, "UTF-8");
    }

    public void addTriplesToStoreLS() {
        TextSegment seg = _textSegments.get(_pageID);

        // create Canvas
        URI canvasURI = URI.create("urn:uuid:" + UUID.randomUUID());

        _sesameStore.addTriple(canvasURI, SesameStore.RDF_TYPE, SesameStore.SC_CANVAS);
        _sesameStore.addTriple(canvasURI, SesameStore.DC_TITLE, "Canvas for " + _pageID);
        _sesameStore.addTriple(canvasURI, SesameStore.EXIF_HEIGHT, Integer.toString(_scanHeight));
        _sesameStore.addTriple(canvasURI, SesameStore.EXIF_WIDTH, Integer.toString(_scanWidth));

        // create image annotation
        if (_pageID != null) {
            URI imageAnnotURI = URI.create("urn:uuid:" + UUID.randomUUID());
            URI imageURI = URI.create(_pageID);

            _sesameStore.addTriple(imageAnnotURI, SesameStore.RDF_TYPE, SesameStore.OA_ANNOTATION);
            _sesameStore.addTriple(imageAnnotURI, SesameStore.OA_HASBODY, imageURI);
            _sesameStore.addTriple(imageAnnotURI, SesameStore.OA_HASTARGET, canvasURI);
            _sesameStore.addTriple(imageAnnotURI, SesameStore.DC_TITLE, "Image annotation of " + _pageID);

            _sesameStore.addTriple(imageURI, SesameStore.RDF_TYPE, SesameStore.DCTYPES_IMAGE);
        }

        // create an empty annotation for the line strip block
        // create SpecificResource for line strip block
        URI textBlockAnnotURI  = URI.create("urn:uuid:" + UUID.randomUUID());
        URI textBlockRegionURI  = URI.create("urn:uuid:" + UUID.randomUUID());
        URI blockSelectorURI = URI.create("urn:uuid:" + UUID.randomUUID());

        _sesameStore.addTriple(textBlockAnnotURI, SesameStore.RDF_TYPE, SesameStore.OA_ANNOTATION);
        _sesameStore.addTriple(textBlockAnnotURI, SesameStore.RDF_TYPE, SesameStore.CP_LINESTRIPREGION);
        _sesameStore.addTriple(textBlockAnnotURI, SesameStore.OA_HASTARGET, textBlockRegionURI);

        _sesameStore.addTriple(textBlockRegionURI, SesameStore.RDF_TYPE, SesameStore.OA_SPECIFICRESOURCE);
        _sesameStore.addTriple(textBlockRegionURI, SesameStore.OA_HASSELECTOR, blockSelectorURI);
        _sesameStore.addTriple(textBlockRegionURI, SesameStore.OA_HASSOURCE, canvasURI);

        // svg for line strip block: a rotated rect

        _sesameStore.addTriple(blockSelectorURI, SesameStore.RDF_TYPE, SesameStore.OAX_SVGSELECTOR);
        _sesameStore.addTriple(blockSelectorURI, SesameStore.RDF_TYPE, SesameStore.CNT_CONTENTASTEXT);

        String cText = "\"<rect x=\""
            + _xOrigin
            + "\" y=\""
            + _yOrigin
            + "\" width=\""
            + _arguments.get(XCANVAS)
            + "\" height=\""
            + _arguments.get(YCANVAS)
            + "\" transform=\"rotate("
            + _rotationAngle
            + ",0,0)"
            + "\"/>\"";

        _sesameStore.addTriple(blockSelectorURI, SesameStore.CNT_CHARS, cText);
        _sesameStore.addTriple(blockSelectorURI, SesameStore.CNT_CHARACTERENCODING, "UTF-8");

        // create Image annotation for (optional) cutout block image
        if (_blockID != null) {
            URI blockImageAnnotURI = URI.create("urn:uuid:" + UUID.randomUUID());
            URI imageURI = URI.create(_blockID);

            _sesameStore.addTriple(blockImageAnnotURI, SesameStore.RDF_TYPE, SesameStore.OA_ANNOTATION);
            _sesameStore.addTriple(blockImageAnnotURI, SesameStore.OA_HASBODY, imageURI);
            _sesameStore.addTriple(blockImageAnnotURI, SesameStore.OA_HASTARGET, textBlockRegionURI);

            _sesameStore.addTriple(imageURI, SesameStore.RDF_TYPE, SesameStore.DCTYPES_IMAGE);
        }

        // create Line strips for each line

        for (Line l : _orderedLinesForPage) {
            TextSegment lineSeg = _textSegments.get(l.lineID);

            if (lineSeg != null) {
                addTriplesForLineStrip(lineSeg, l.lineID, textBlockRegionURI);
            }
        }
    }

    private void addTriplesForLineStrip(TextSegment seg, String id, URI textBlockRegionURI) {
        URI lineStripAnnotURI = URI.create("urn:uuid:" + UUID.randomUUID());
        URI lineBoxURI = URI.create("urn:uuid:" + UUID.randomUUID());

        // create unique id for line strip from image url, block position and line number
  //      URI pageURI = URI.create(_pageID);

        String lineStripID = _pageID + "/" + _xOrigin + "-" + _yOrigin + "/" + seg.navisRecord.get(NAVIS_LINE_ID);

        // annotation for each line strip
        _sesameStore.addTriple(lineStripAnnotURI, SesameStore.RDF_TYPE, SesameStore.OA_ANNOTATION);
        _sesameStore.addTriple(lineStripAnnotURI, SesameStore.RDF_TYPE, SesameStore.CP_LINESTRIP);
        _sesameStore.addTriple(lineStripAnnotURI, SesameStore.OA_HASTARGET, lineBoxURI);
        _sesameStore.addTriple(lineStripAnnotURI, SesameStore.DC_IDENTIFIER, lineStripID);

        // target of line strip is box, relative to textBlockRegion
        URI lineBoxSelectorURI = URI.create("urn:uuid:" + UUID.randomUUID());

        _sesameStore.addTriple(lineBoxURI, SesameStore.RDF_TYPE, SesameStore.OA_SPECIFICRESOURCE);
        _sesameStore.addTriple(lineBoxURI, SesameStore.OA_HASSELECTOR, lineBoxSelectorURI);
        _sesameStore.addTriple(lineBoxURI, SesameStore.OA_HASSOURCE, textBlockRegionURI);

        // svg selector for line strip box
        _sesameStore.addTriple(lineBoxSelectorURI, SesameStore.RDF_TYPE, SesameStore.OAX_SVGSELECTOR);
        _sesameStore.addTriple(lineBoxSelectorURI, SesameStore.RDF_TYPE, SesameStore.CNT_CONTENTASTEXT);

        String cText = "\"<rect x=\""
            + seg.x
            + "\" y=\""
            + seg.y
            + "\" width=\""
            + seg.w
            + "\" height=\""
            + seg.h
            + "\"/>\"";

        _sesameStore.addTriple(lineBoxSelectorURI, SesameStore.CNT_CHARS, cText);
        _sesameStore.addTriple(lineBoxSelectorURI, SesameStore.CNT_CHARACTERENCODING, "UTF-8");

        // attach line strip image to line box
        URI stripImageAnnotURI = URI.create("urn:uuid:" + UUID.randomUUID());
        URI imageURI = URI.create(seg.navisRecord.get(NAVIS_LINE_IMAGE_URL));

        _sesameStore.addTriple(stripImageAnnotURI, SesameStore.RDF_TYPE, SesameStore.OA_ANNOTATION);
        _sesameStore.addTriple(stripImageAnnotURI, SesameStore.OA_HASBODY, imageURI);
        _sesameStore.addTriple(stripImageAnnotURI, SesameStore.OA_HASTARGET, lineBoxURI);

        _sesameStore.addTriple(imageURI, SesameStore.RDF_TYPE, SesameStore.DCTYPES_IMAGE);
    }

    /**
     * Segment information for page, line and word zone segments.
     * Natural sorting order per page: line by line, then word by word
     */
    private class TextSegment implements Comparable<TextSegment> {
        
        protected int x;
        protected int y;
        protected int w;
        protected int h;
        protected String text;
        protected int textOffset = -1; // relative to full page text
        protected int textRange;
        protected Map<String,String> navisRecord;

        public TextSegment(int x, int y, int w, int h, String text, int textOffset, int textRange, Map<String, String> navisRecord) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.text = text;
            this.textOffset = textOffset;
            this.textRange = textRange;
            this.navisRecord = navisRecord;
        }

        public int compareTo(TextSegment t) {
            if (navisRecord == null) {  // line or page text, sorting on textOffset is best shot

                if (textOffset < t.textOffset) return -1;
                else if (textOffset > t.textOffset) return 1;
                else return 0;
            }
            else {      
                // TODO: take different line id's into account
                // is wordzone. Assume same line, then on x coordinate within line
                if (x < t.x) return -1;
                else if (x > t.x) return 1;
                else return 0;
            }
        }
    }

    /**
     * Line id: is a String with a special sorting order.
     */
    private class Line implements Comparable<Line> {

        protected String lineID;

        public Line(String lineID) {
            this.lineID = lineID;
        }

        public int compareTo(Line lID) {
            int line = Integer.parseInt(lineID.substring(lineID.length() - 3));
            int l = Integer.parseInt(lID.lineID.substring(lID.lineID.length() - 3));

            if (line > l)
                return 1;
            else if (line < l)
                return -1;
            else
                return 0;
        }
    }
}
