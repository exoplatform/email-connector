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
 */package org.exoplatform.emailConnector.config;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Every XML file the webapp ships parses (EXO-90846). The portal reads them at startup,
 * and one that is not well-formed -- a {@code --} inside a comment is enough -- stops
 * the add-on's whole configuration from loading, a failure no Java test of this module
 * would otherwise see. Lives here because the webapp module has no test phase; the
 * repository layout puts the webapp beside this module.
 */
class WebappXmlWellFormedTest {

  // The webapp sources, from this module's directory, where the build runs its tests.
  private static final Path WEBAPP_SOURCES = Path.of("..", "email-connector-webapps", "src", "main");

  /**
   * Parses every {@code .xml} of the webapp's sources, the installed node modules
   * excluded, and fails naming each file that does not parse. Refuses to pass on
   * finding nearly nothing, which would mean the path is wrong, not that all is well.
   *
   * @throws Exception when the sources cannot be listed or the parser cannot be made
   */
  @Test
  void everyWebappXmlFileParses() throws Exception {
    List<Path> files;
    try (Stream<Path> paths = Files.walk(WEBAPP_SOURCES)) {
      files = paths.filter(path -> path.toString().endsWith(".xml"))
                   .filter(path -> !path.toString().contains("node_modules"))
                   .toList();
    }
    assertTrue(files.size() >= 4, "Too few XML files found under " + WEBAPP_SOURCES.toAbsolutePath() + ": " + files);
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    builder.setErrorHandler(new DefaultHandler());
    StringBuilder failures = new StringBuilder();
    for (Path file : files) {
      try {
        builder.parse(file.toFile());
      } catch (SAXParseException e) {
        failures.append(file).append(':').append(e.getLineNumber()).append(": ").append(e.getMessage()).append('\n');
      }
    }
    if (failures.length() > 0) {
      fail("Webapp XML files that do not parse:\n" + failures);
    }
  }
}
