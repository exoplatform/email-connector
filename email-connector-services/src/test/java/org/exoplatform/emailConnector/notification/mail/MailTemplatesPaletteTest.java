/**
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2026 Meeds Association contact@meeds.io
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.exoplatform.emailConnector.notification.mail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import org.exoplatform.commons.api.notification.service.template.TemplateContext;
import org.exoplatform.commons.notification.mail.MailPalette;
import org.exoplatform.portal.branding.MailDocumentShell;

import groovy.text.GStringTemplateEngine;

/**
 * The mails of the email connector, rendered as the mail channel renders
 * them (through the template engine), in the palette bound to their
 * recipient: no light colour literal is left, so a dark recipient gets dark
 * colours.
 */
class MailTemplatesPaletteTest {

  private static final Path   TEMPLATES      =
                                        Path.of("../email-connector-webapps/src/main/webapp/WEB-INF/conf/email-connector/templates/notification/mail");

  private static final String DARK_SURFACE   = "#1E2328";

  private static final String DARK_TEXT      = "#E6E6E6";

  private static final String DARK_BORDER    = "#3A4148";

  private static final String DARK_BACKGROUND = "#14181B";

  static Stream<String> templates() throws Exception {
    try (Stream<Path> files = Files.list(TEMPLATES)) {
      return files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".gtmpl")).sorted().toList().stream();
    }
  }

  @ParameterizedTest
  @MethodSource("templates")
  void aDarkRecipientGetsTheDarkPalette(String template) throws Exception {
    Map<String, String> palette = new HashMap<>(MailDocumentShell.LIGHT_DEFAULTS);
    palette.put(MailDocumentShell.SURFACE, DARK_SURFACE);
    palette.put(MailDocumentShell.TEXT, DARK_TEXT);
    palette.put(MailDocumentShell.BORDER, DARK_BORDER);
    palette.put(MailDocumentShell.BACKGROUND, DARK_BACKGROUND);
    palette.put(MailPalette.MODE, "dark");

    String html = render(template, palette);

    assertTrue(html.contains("bgcolor=\"" + DARK_SURFACE + "\""), template + " surface: " + html);
    assertTrue(html.contains("color:" + DARK_TEXT), template + " text");
    assertTrue(html.contains(DARK_BORDER), template + " border");
    assertTrue(html.contains("class=\"mail-surface mail-text\""), template + " role classes");
    assertTrue(html.contains("EMAILS_CONTENT_VALUE"), template + " content");
    assertFalse(hasLightLiteral(html), template + " keeps a light literal: " + html);
  }

  @ParameterizedTest
  @MethodSource("templates")
  void aContextWithoutPaletteRendersInTheLightDefaults(String template) throws Exception {
    String html = render(template, null);

    assertTrue(html.contains("bgcolor=\"" + MailDocumentShell.LIGHT_DEFAULTS.get(MailDocumentShell.SURFACE) + "\""), template);
    assertFalse(html.contains("null"), template);
  }

  private static boolean hasLightLiteral(String html) {
    String lower = html.toLowerCase();
    return Stream.of("#ffffff", "#333333", "#d8d8d8", "#f1f1f1", "#999999", "#c7c7c7").anyMatch(lower::contains);
  }

  private static String render(String template, Map<String, String> palette) throws Exception {
    StringWriter writer = new StringWriter();
    TemplateContext ctx = new TemplateContext();
    ctx.put("_writer", writer);
    ctx.put("_templateContext", ctx);
    ctx.put("_ctx", new TemplateHelper());
    ctx.put("EMAILS_CONTENT", "EMAILS_CONTENT_VALUE");
    ctx.put("EMAILS_LINK", "https://exo.example.com/portal");
    if (palette != null) {
      ctx.put(MailPalette.CONTEXT_KEY, palette);
    }
    String source = Files.readString(TEMPLATES.resolve(template), StandardCharsets.UTF_8);
    new GStringTemplateEngine().createTemplate(source).make(ctx).writeTo(writer);
    return writer.toString();
  }

  /**
   * What a template calls on its {@code _ctx}: the labels are echoed back and the shared
   * header and footer are not part of what is rendered here.
   */
  public static class TemplateHelper {
    public String appRes(String key, Object... args) {
      return key;
    }

    public String escapeHTML(String text) {
      return text;
    }

    public void include(String path, Object context) {
      // the shared header and footer have their own tests (commons)
    }
  }
}
