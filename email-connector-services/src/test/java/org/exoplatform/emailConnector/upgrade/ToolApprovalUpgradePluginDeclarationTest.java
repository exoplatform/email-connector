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
package org.exoplatform.emailConnector.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.exoplatform.commons.upgrade.UpgradePluginExecutionContext;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.ValueParam;
import org.exoplatform.container.xml.ValuesParam;

/**
 * EXO-92488 -- the plugin as {@code configuration.xml} declares it runs once,
 * at the first version change after it ships, whatever the previous version:
 * a dated build, a development build or a release. It declares no target
 * version, which would skip an instance whose previous version ranks at or
 * after it. These tests read the declared parameters, not values written for
 * the test.
 */
class ToolApprovalUpgradePluginDeclarationTest {

  private static final Path                CONFIGURATION = Path.of("..",
                                                                   "email-connector-webapps",
                                                                   "src",
                                                                   "main",
                                                                   "resources",
                                                                   "conf",
                                                                   "portal",
                                                                   "configuration.xml");

  private static final String              PLUGIN_NAME   = "EmailToolApprovalUpgradePlugin";

  private static ToolApprovalUpgradePlugin declaredPlugin;

  private static String                    declaredProfiles;

  @BeforeAll
  static void readDeclaredPlugin() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    Document document = factory.newDocumentBuilder().parse(CONFIGURATION.toFile());
    NodeList plugins = document.getElementsByTagName("component-plugin");
    Element declaration = null;
    for (int i = 0; i < plugins.getLength() && declaration == null; i++) {
      Element plugin = (Element) plugins.item(i);
      if (PLUGIN_NAME.equals(childText(plugin, "name"))) {
        declaration = plugin;
      }
    }
    assertNotNull(declaration, PLUGIN_NAME + " must be declared in " + CONFIGURATION.toAbsolutePath());
    declaredProfiles = declaration.getAttribute("profiles");
    declaredPlugin = new ToolApprovalUpgradePlugin(initParams(declaration));
  }

  @Test
  void runsOnAnInstanceUpgradedFromADatedBuild() {
    assertTrue(declaredPlugin.shouldProceedToUpgrade("7.3.0-20261110", "7.3.0-20261028", null),
               "The plugin must run when an instance moves from one dated build to the next");
  }

  @Test
  void runsOnAnInstanceUpgradedFromADevelopmentBuild() {
    assertTrue(declaredPlugin.shouldProceedToUpgrade("7.3.x-maintenance-SNAPSHOT-rev13",
                                                     "7.3.x-maintenance-SNAPSHOT-rev12",
                                                     null),
               "The plugin must run when an acceptance server moves to a newer development build");
  }

  @Test
  void runsOnAnInstanceUpgradedFromTheRelease() {
    assertTrue(declaredPlugin.shouldProceedToUpgrade("7.3.1-20261201", "7.3.0", null),
               "The plugin must run when an instance moves on from the 7.3.0 release: it declares no target version");
  }

  @Test
  void neverRunsAgainOnceItRan() {
    assertFalse(declaredPlugin.shouldProceedToUpgrade("7.3.0-20261117",
                                                      "7.3.0-20261110",
                                                      new UpgradePluginExecutionContext("7.3.0-20261110", 1)),
                "Once it ran, the plugin must leave the flags to the administrator");
  }

  @Test
  void runsAfterTheMcpServerToolServiceExists() {
    assertTrue(declaredPlugin.isAsyncUpgradeExecution(),
               "The plugin must run asynchronously, on its own thread");
  }

  /**
   * The plugin uses the MCP server's classes, which an instance without that
   * add-on lacks: it is declared under the add-on's profile, as the add-on's
   * other MCP classes are.
   */
  @Test
  void isDeclaredUnderTheMcpServerProfile() {
    assertEquals("mcp-server", declaredProfiles);
  }

  @Test
  void gatesTheThreeMailboxTools() {
    assertEquals(List.of("mark_read", "mark_unread", "remove_email_category"), declaredPlugin.getToolNames());
  }

  private static InitParams initParams(Element declaration) {
    InitParams initParams = new InitParams();
    Element initParamsElement = (Element) declaration.getElementsByTagName("init-params").item(0);
    NodeList valueParams = initParamsElement.getElementsByTagName("value-param");
    for (int i = 0; i < valueParams.getLength(); i++) {
      Element element = (Element) valueParams.item(i);
      ValueParam param = new ValueParam();
      param.setName(childText(element, "name"));
      param.setValue(childText(element, "value"));
      initParams.addParameter(param);
    }
    NodeList valuesParams = initParamsElement.getElementsByTagName("values-param");
    for (int i = 0; i < valuesParams.getLength(); i++) {
      Element element = (Element) valuesParams.item(i);
      ValuesParam param = new ValuesParam();
      param.setName(childText(element, "name"));
      NodeList values = element.getElementsByTagName("value");
      ArrayList<String> list = new ArrayList<>();
      for (int j = 0; j < values.getLength(); j++) {
        list.add(values.item(j).getTextContent().trim());
      }
      param.setValues(list);
      initParams.addParameter(param);
    }
    return initParams;
  }

  private static String childText(Element parent, String tagName) {
    NodeList children = parent.getElementsByTagName(tagName);
    return children.getLength() == 0 ? null : children.item(0).getTextContent().trim();
  }

}
