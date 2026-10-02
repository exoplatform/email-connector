/**
 * Copyright (C) 2026 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Cleans an SVG logo fetched from a sender's domain (EXO-90893) down to drawing
 * instructions, or refuses it.
 * <p>
 * <b>Sanitised, not rasterised.</b> Rasterising would need an SVG renderer on the
 * server -- Batik, a large dependency with its own history of external-fetch flaws, run
 * on input from the internet -- for logos that are vector by design (BIMI mandates
 * SVG Tiny PS). An allowlist over a parsed document is small, reviewable, and keeps the
 * logo sharp at every size.
 * <p>
 * <b>What is kept.</b> The elements of SVG Tiny's static drawing set (shapes, paths,
 * groups, gradients, text, local {@code use}, plus the {@code clipPath}, {@code mask}
 * and {@code style} that site icons commonly carry) and their presentation and geometry
 * attributes. <b>What goes</b>: every other element with its whole subtree --
 * {@code script}, {@code foreignObject}, {@code image}, animation, {@code a}, anything
 * outside the SVG namespace -- every attribute not on the list, event handlers
 * included, every link that is not a fragment of the document itself ({@code #id}),
 * every {@code url(...)} reference that is not one, and every {@code style} carrying
 * an {@code @import}, an escape or an external reference. Comments and processing
 * instructions are dropped. A document type declaration refuses the whole document,
 * so no entity can be expanded and nothing external is read while parsing.
 * <p>
 * The response serving it adds a Content-Security-Policy that forbids scripts and
 * external loads, should a browser ever open the logo as a document rather than as an
 * image (where SVG never runs scripts anyway).
 */
public final class SvgLogoSanitizer {

  /** The SVG namespace. */
  public static final String       SVG_NS          = "http://www.w3.org/2000/svg";

  private static final String      XLINK_NS        = "http://www.w3.org/1999/xlink";

  private static final String      XMLNS_NS        = XMLConstants.XMLNS_ATTRIBUTE_NS_URI;

  private static final String      XML_NS          = XMLConstants.XML_NS_URI;

  private static final Log         LOG             = ExoLogger.getLogger(SvgLogoSanitizer.class);

  private static final Set<String> ELEMENTS        = Set.of("svg",
                                                            "g",
                                                            "defs",
                                                            "title",
                                                            "desc",
                                                            "path",
                                                            "rect",
                                                            "circle",
                                                            "ellipse",
                                                            "line",
                                                            "polyline",
                                                            "polygon",
                                                            "text",
                                                            "tspan",
                                                            "linearGradient",
                                                            "radialGradient",
                                                            "stop",
                                                            "solidColor",
                                                            "use",
                                                            "symbol",
                                                            "clipPath",
                                                            "mask",
                                                            "style");

  private static final Set<String> ATTRIBUTES      = Set.of("id",
                                                            "class",
                                                            "style",
                                                            "version",
                                                            "baseProfile",
                                                            "viewBox",
                                                            "preserveAspectRatio",
                                                            "width",
                                                            "height",
                                                            "x",
                                                            "y",
                                                            "x1",
                                                            "y1",
                                                            "x2",
                                                            "y2",
                                                            "cx",
                                                            "cy",
                                                            "r",
                                                            "rx",
                                                            "ry",
                                                            "fx",
                                                            "fy",
                                                            "d",
                                                            "points",
                                                            "dx",
                                                            "dy",
                                                            "transform",
                                                            "fill",
                                                            "fill-opacity",
                                                            "fill-rule",
                                                            "stroke",
                                                            "stroke-width",
                                                            "stroke-linecap",
                                                            "stroke-linejoin",
                                                            "stroke-miterlimit",
                                                            "stroke-dasharray",
                                                            "stroke-dashoffset",
                                                            "stroke-opacity",
                                                            "opacity",
                                                            "color",
                                                            "display",
                                                            "visibility",
                                                            "clip-rule",
                                                            "clip-path",
                                                            "clipPathUnits",
                                                            "mask",
                                                            "maskUnits",
                                                            "maskContentUnits",
                                                            "gradientUnits",
                                                            "gradientTransform",
                                                            "spreadMethod",
                                                            "offset",
                                                            "stop-color",
                                                            "stop-opacity",
                                                            "solid-color",
                                                            "solid-opacity",
                                                            "font-family",
                                                            "font-size",
                                                            "font-weight",
                                                            "font-style",
                                                            "text-anchor",
                                                            "letter-spacing",
                                                            "dominant-baseline",
                                                            "vector-effect",
                                                            "href",
                                                            "type",
                                                            "media");

  /**
   * How deep kept elements may nest: a logo is a few groups deep. A document whose kept
   * elements nest deeper is refused whole, which keeps the cleaning and the
   * serializer's recursion bounded -- a few thousand nested groups, well inside the size
   * limit, would overflow the stack. A dropped element goes with its whole subtree,
   * which is never walked.
   */
  static final int                 MAX_DEPTH       = 64;

  /** A {@code url(...)} reference, quoted or not. */
  private static final Pattern     URL_REFERENCE   = Pattern.compile("url\\(\\s*['\"]?([^)'\"]*)['\"]?\\s*\\)",
                                                                     Pattern.CASE_INSENSITIVE);

  private SvgLogoSanitizer() {
  }

  /**
   * Cleans an SVG document down to its drawing.
   *
   * @param svg the document's bytes, as fetched
   * @return the cleaned document, UTF-8, or null when it is not an SVG document this
   *         accepts (not well-formed, a document type declaration, a root other than
   *         {@code svg} in the SVG namespace, elements nested deeper than
   *         {@link #MAX_DEPTH})
   */
  public static byte[] sanitize(byte[] svg) {
    if (svg == null || svg.length == 0) {
      return null; // NOSONAR null is "refused", an empty array would be an empty logo
    }
    try {
      Document document = parser().parse(new ByteArrayInputStream(svg));
      Element root = document.getDocumentElement();
      if (root == null || !SVG_NS.equals(root.getNamespaceURI()) || !"svg".equals(root.getLocalName())) {
        return null; // NOSONAR as above
      }
      if (!clean(root, 1)) {
        return null; // NOSONAR as above
      }
      return serialize(document);
    } catch (Exception e) {
      LOG.debug("An SVG logo was refused: {}", e.getClass().getSimpleName());
      return null; // NOSONAR as above
    }
  }

  /**
   * A parser that refuses document type declarations and reads nothing external.
   *
   * @return the parser
   * @throws Exception when the platform's parser does not support a required feature
   */
  private static DocumentBuilder parser() throws Exception { // NOSONAR the JAXP factory's own signatures
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    builder.setErrorHandler(null);
    return builder;
  }

  /**
   * Cleans an element kept: its attributes filtered, then its children, each kept and
   * cleaned or removed with its subtree.
   *
   * @param element the element
   * @param depth its depth, the root being 1
   * @return false when an element kept sits deeper than {@link #MAX_DEPTH}: the
   *         document is refused
   */
  private static boolean clean(Element element, int depth) {
    if (depth > MAX_DEPTH) {
      return false;
    }
    cleanAttributes(element);
    List<Node> children = new ArrayList<>();
    NodeList nodes = element.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      children.add(nodes.item(i));
    }
    boolean styleElement = "style".equals(element.getLocalName());
    for (Node child : children) {
      switch (child.getNodeType()) {
      case Node.ELEMENT_NODE -> {
        Element childElement = (Element) child;
        if (!styleElement && SVG_NS.equals(childElement.getNamespaceURI()) && ELEMENTS.contains(childElement.getLocalName())) {
          if (!clean(childElement, depth + 1)) {
            return false;
          }
        } else {
          element.removeChild(child);
        }
      }
      case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
        if (styleElement && !isSafeStyle(child.getNodeValue())) {
          element.removeChild(child);
        }
      }
      default -> element.removeChild(child);
      }
    }
    return true;
  }

  /**
   * Removes the attributes of an element that are not on the list, or whose value
   * could reach outside the document.
   *
   * @param element the element
   */
  private static void cleanAttributes(Element element) {
    NamedNodeMap attributes = element.getAttributes();
    List<Attr> removed = new ArrayList<>();
    for (int i = 0; i < attributes.getLength(); i++) {
      Attr attribute = (Attr) attributes.item(i);
      if (!isKept(attribute)) {
        removed.add(attribute);
      }
    }
    for (Attr attribute : removed) {
      element.removeAttributeNode(attribute);
    }
  }

  /**
   * Whether an attribute stays.
   * <ul>
   * <li>Namespace declarations stay: they declare, they do not point anywhere.</li>
   * <li>{@code href} (plain or {@code xlink:}) stays when it is a fragment of this
   * document.</li>
   * <li>{@code xml:space} stays.</li>
   * <li>Any other attribute stays when it is on the list, in no namespace, and its value
   * holds no external {@code url(...)} reference, no script scheme, and, for
   * {@code style}, nothing {@link #isSafeStyle} refuses.</li>
   * </ul>
   *
   * @param attribute the attribute
   * @return true when it stays
   */
  private static boolean isKept(Attr attribute) {
    String namespace = attribute.getNamespaceURI();
    String name = attribute.getLocalName() != null ? attribute.getLocalName() : attribute.getName();
    String value = StringUtils.defaultString(attribute.getValue());
    if (XMLNS_NS.equals(namespace)) {
      return true;
    }
    if ("href".equals(name) && (namespace == null || XLINK_NS.equals(namespace))) {
      return value.trim().startsWith("#");
    }
    if (XML_NS.equals(namespace)) {
      return "space".equals(name);
    }
    if (namespace != null || !ATTRIBUTES.contains(name)) {
      return false;
    }
    if ("style".equals(name)) {
      return isSafeStyle(value);
    }
    return hasOnlyLocalReferences(value) && !value.toLowerCase(Locale.ROOT).contains("script:");
  }

  /**
   * Whether a style sheet or a {@code style} attribute stays: no {@code @import}, no
   * backslash escape (which could spell any of what follows), no {@code expression(},
   * no script scheme, no {@code image-set(} (which takes a bare string as a URL), and
   * no {@code url(...)} but to a fragment of the document.
   *
   * @param style the style text
   * @return true when it stays
   */
  private static boolean isSafeStyle(String style) {
    if (style == null) {
      return true;
    }
    String lower = style.toLowerCase(Locale.ROOT);
    return !lower.contains("@import") && !lower.contains("\\") && !lower.contains("expression(") && !lower.contains("script:")
        && !lower.contains("image-set(") && hasOnlyLocalReferences(style);
  }

  /**
   * Whether every {@code url(...)} in a value points at a fragment of the document,
   * and no {@code url(} is left unparsed.
   *
   * @param value the value
   * @return true when it does
   */
  private static boolean hasOnlyLocalReferences(String value) {
    Matcher matcher = URL_REFERENCE.matcher(value);
    int references = 0;
    while (matcher.find()) {
      references++;
      if (!matcher.group(1).trim().startsWith("#")) {
        return false;
      }
    }
    return references == StringUtils.countMatches(value.toLowerCase(Locale.ROOT), "url(");
  }

  /**
   * Writes the cleaned document out, UTF-8, without reading anything external.
   *
   * @param document the document
   * @return its bytes
   * @throws Exception when it cannot be written
   */
  private static byte[] serialize(Document document) throws Exception { // NOSONAR the JAXP factory's own signatures
    TransformerFactory factory = TransformerFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    Transformer transformer = factory.newTransformer();
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    transformer.transform(new DOMSource(document), new StreamResult(out));
    return out.toByteArray();
  }
}
