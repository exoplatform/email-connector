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
package org.exoplatform.emailConnector.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.service.EmailBoxService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * An instance that booted once keeps the tool definitions it stored, and takes
 * a changed one only when {@code ai-tool-definition-upgrades.json} lists the
 * digest of what it stored (mcp-server's {@code McpServerToolService}). The
 * digest is the SHA-256 of the definition's description and input schema,
 * keys sorted at every level, as mcp-server computes it.
 */
class AiToolDefinitionUpgradesTest {

  private static final JsonMapper          MAPPER                    = JsonMapper.builder().build();

  /** How many earlier definitions of each changed tool the history of every ref holds. */
  private static final Map<String, Integer> EARLIER_DEFINITIONS       = Map.of("list_emails", 7);

  /**
   * The definition of list_emails feature/rtd shipped before its page was
   * capped and counted (EXO-94363), the one its instances stored.
   */
  private static final String               LIST_EMAILS_BEFORE_94363 =
                                                                     "e9ff224c2207d720aa2e354f784a2754d759c284dc8d9d61963b894452f6c8a8";

  /**
   * Each changed tool lists every earlier definition, the one feature/rtd
   * shipped included, and never the one it ships, which an instance keeps
   * anyway. Mutants: a digest removed, the shipped digest added, the entry
   * dropped.
   *
   * @throws Exception never
   */
  @Test
  void eachChangedToolListsItsEarlierDefinitionsOnly() throws Exception {
    Map<String, List<String>> replaced = replacedDefinitions();
    Map<String, String> shipped = shippedDigests();

    assertEquals(EARLIER_DEFINITIONS.keySet(), replaced.keySet());
    EARLIER_DEFINITIONS.forEach((name, count) -> {
      List<String> digests = replaced.get(name);
      assertEquals(count.intValue(), digests.size(), name);
      assertEquals(count.intValue(), Set.copyOf(digests).size(), name);
      assertFalse(digests.contains(shipped.get(name)), name + " lists its shipped definition");
      digests.forEach(digest -> assertTrue(digest.matches("[0-9a-f]{64}"), digest));
    });
    assertTrue(replaced.get("list_emails").contains(LIST_EMAILS_BEFORE_94363));
  }

  /**
   * The list_emails definition states the page cap and the body cut the tool
   * applies (EXO-94363), where a whole body is read, and that count is what
   * the mirror holds, which syncs the latest SENT and ARCHIVE emails only.
   * Mutants: the cap changed in the code only; the SENT and ARCHIVE window
   * changed in the code only.
   *
   * @throws Exception never
   */
  @Test
  void theListDefinitionStatesTheCapAndTheCutTheToolApplies() throws Exception {
    String description = definitions().get("list_emails").path("description").asString();
    assertTrue(description.contains("at most " + EmailMcpTool.MAX_LIST_LIMIT), description);
    assertTrue(description.contains("cut at 1500 characters"), description);
    assertTrue(description.contains("count") && description.contains("has_more"), description);
    assertTrue(description.contains("get_email_by_id reads one email whole"), description);
    Field window = EmailBoxService.class.getDeclaredField("NON_INBOX_FOLDER_SYNC_LIMIT");
    window.setAccessible(true);
    assertTrue(description.contains("how many emails of that folder the mirror holds"), description);
    assertTrue(description.contains("the latest " + window.getInt(null) + " of SENT and ARCHIVE"), description);
  }

  /**
   * The digest reads the definition, not its layout: the keys of the input
   * schema in another order give the same digest.
   *
   * @throws Exception never
   */
  @Test
  void theDigestIsTheStoredDefinitionsOne() throws Exception {
    JsonNode schema = MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"}}}");
    JsonNode reordered = MAPPER.readTree("{\"properties\":{\"a\":{\"type\":\"integer\"}},\"type\":\"object\"}");
    assertEquals(digest("d", schema), digest("d", reordered));
    assertFalse(digest("d", schema).equals(digest("d.", schema)));
  }

  /**
   * @return by tool name, the digests the upgrades file lists
   * @throws Exception when it can't be read
   */
  private static Map<String, List<String>> replacedDefinitions() throws Exception {
    Map<String, List<String>> replaced = new HashMap<>();
    read("ai-tool-definition-upgrades.json").path("tools").forEach(tool -> {
      List<String> digests = new ArrayList<>();
      tool.path("replaced_definitions").forEach(digest -> digests.add(digest.asString()));
      replaced.put(tool.path("name").asString(), digests);
    });
    return replaced;
  }

  /**
   * @return by tool name, the digest of the definition the add-on ships
   * @throws Exception when it can't be read
   */
  private static Map<String, String> shippedDigests() throws Exception {
    Map<String, String> digests = new HashMap<>();
    for (Map.Entry<String, JsonNode> tool : definitions().entrySet()) {
      digests.put(tool.getKey(), digest(tool.getValue().path("description").asString(), tool.getValue().path("input_schema")));
    }
    return digests;
  }

  /**
   * @return by tool name, the definitions the add-on ships
   * @throws Exception when they can't be read
   */
  private static Map<String, JsonNode> definitions() throws Exception {
    Map<String, JsonNode> byName = new HashMap<>();
    read("ai-tool-definitions.json").path("tools").forEach(tool -> byName.put(tool.path("name").asString(), tool));
    return byName;
  }

  /**
   * @param resource a resource of the add-on's classpath
   * @return its JSON tree
   * @throws Exception when it can't be read
   */
  private static JsonNode read(String resource) throws Exception {
    try (InputStream input = EmailMcpTool.class.getClassLoader().getResourceAsStream(resource)) {
      return MAPPER.readTree(input);
    }
  }

  /**
   * @param description a tool's description
   * @param inputSchema its input schema
   * @return the definition's digest, as mcp-server computes it
   * @throws Exception never
   */
  private static String digest(String description, JsonNode inputSchema) throws Exception {
    ObjectNode definition = MAPPER.createObjectNode();
    definition.put("description", description);
    definition.set("input_schema", inputSchema);
    byte[] hash = MessageDigest.getInstance("SHA-256")
                               .digest(MAPPER.writeValueAsString(withSortedKeys(definition)).getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(hash);
  }

  /**
   * @param node a JSON tree
   * @return a copy whose objects list their keys in sorted order at every
   *         level
   */
  private static JsonNode withSortedKeys(JsonNode node) {
    if (node.isObject()) {
      ObjectNode sorted = MAPPER.createObjectNode();
      List<Map.Entry<String, JsonNode>> properties = new ArrayList<>(node.properties());
      properties.sort(Map.Entry.comparingByKey());
      properties.forEach(property -> sorted.set(property.getKey(), withSortedKeys(property.getValue())));
      return sorted;
    } else if (node.isArray()) {
      ArrayNode array = MAPPER.createArrayNode();
      node.values().forEach(value -> array.add(withSortedKeys(value)));
      return array;
    } else {
      return node;
    }
  }
}
