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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Guards the contract that makes a tool exist at all: every public method of
 * the MCP tool plugins must have a matching entry (its snake_case name) in
 * {@code ai-tool-definitions.json} — a tool missing from that file is silently
 * dropped at runtime, which no other test would ever notice — and the entries
 * must not name methods that are gone. Also pins the approval flags of the
 * tools whose writes must never run unconfirmed.
 */
class AiToolDefinitionsTest {

  /** The tool plugin classes whose public methods become MCP tools. */
  private static final List<Class<?>> TOOL_PLUGINS   =
                                                   List.of(EmailMcpTool.class, EmailContactMcpTool.class);

  /** The tools that must carry {@code require_approval: true}. */
  private static final Set<String>    APPROVAL_TOOLS =
                                                     Set.of("send_email",
                                                            "reply_email",
                                                            "reply_all",
                                                            "forward_email",
                                                            "archive_email",
                                                            "delete_email",
                                                            "create_contact");

  /**
   * Every public tool method must be declared in the JSON, and every JSON entry
   * must still name a tool method — the two sets are the same set.
   */
  @Test
  void everyToolMethodIsDeclaredAndViceVersa() throws Exception {
    Set<String> methodNames = new TreeSet<>();
    for (Class<?> plugin : TOOL_PLUGINS) {
      Arrays.stream(plugin.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
            .map(Method::getName)
            .map(AiToolDefinitionsTest::toSnakeCase)
            .forEach(methodNames::add);
    }
    Set<String> declaredNames = new TreeSet<>(readDefinitions().keySet());
    assertEquals(declaredNames,
                 methodNames,
                 "Tool methods and ai-tool-definitions.json entries must match one to one: "
                     + "a method missing from the JSON is silently dropped at runtime, "
                     + "and a JSON entry without a method promises a tool that does not exist.");
  }

  /**
   * The write tools that reach the outside world or the user's data must be
   * approval-gated, and the read tools must not be — an approval prompt on
   * every search would train the user to click through the ones that matter.
   */
  @Test
  void approvalFlagsMatchTheToolsRiskiness() throws Exception {
    Map<String, JsonNode> definitions = readDefinitions();
    for (String name : APPROVAL_TOOLS) {
      JsonNode tool = definitions.get(name);
      assertTrue(tool != null && tool.path("require_approval").asBoolean(false),
                 "Tool " + name + " must carry require_approval: true");
    }
    for (String name : Set.of("search_contacts", "get_contact", "suggest_recipients", "list_shared_mailboxes")) {
      JsonNode tool = definitions.get(name);
      assertFalse(tool == null || tool.path("require_approval").asBoolean(false),
                  "Read tool " + name + " must not be approval-gated");
    }
  }

  /**
   * EXO-90555 -- a tool that takes a {@code mailbox} declares it, and a tool that
   * declares one takes it: an undeclared parameter is never sent by an agent, so the
   * tool would silently act on the user's own mailbox; a declared one the method lacks
   * is dropped, with the same effect. And the one new tool is a read, flagged as such.
   */
  @Test
  void aMailboxParameterIsDeclaredWhereverAToolTakesIt() throws Exception {
    Map<String, JsonNode> definitions = readDefinitions();
    Set<String> taking = new TreeSet<>();
    for (Method method : EmailMcpTool.class.getDeclaredMethods()) {
      if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
        continue;
      }
      for (java.lang.reflect.Parameter parameter : method.getParameters()) {
        assertTrue(parameter.isNamePresent(), "compiled with -parameters, or tool arguments bind to null");
        if ("mailbox".equals(parameter.getName())) {
          taking.add(toSnakeCase(method.getName()));
        }
      }
    }
    Set<String> declaring = new TreeSet<>();
    definitions.forEach((name, tool) -> {
      if (tool.path("input_schema").path("properties").has("mailbox")) {
        declaring.add(name);
      }
    });
    assertEquals(declaring, taking);
    assertEquals(16, taking.size(), "every email tool but the account, the categories and the listing of shares");
    String search = definitions.get("search_emails").path("description").asText();
    assertTrue(search.contains("never a mail_remote_id in its place") && search.contains("With mailbox"),
               "the chaining rule and the mailbox sentence are the description the model reads");
    // A hit not in the synced copy cannot be opened by the reading tools, which read that
    // copy: no description may send the model to one with its mail_remote_id.
    assertTrue(search.contains("A hit without an email_id is not in the synced copy"), search);
    assertFalse(search.contains("use its mail_remote_id"), search);
    String thread = definitions.get("get_email_thread").path("description").asText();
    assertFalse(thread.contains("mail_remote_id) through get_email_full"), thread);
    JsonNode listing = definitions.get("list_shared_mailboxes");
    assertTrue(listing.path("annotations").path("readOnlyHint").asBoolean(false), "a read");
    assertFalse(listing.path("annotations").path("destructiveHint").asBoolean(true), "not destructive");
  }

  /** The tools that send a mail, and so take the name it leaves under (EXO-90585). */
  private static final Set<String>    SENDING_TOOLS  = Set.of("send_email", "reply_email", "reply_all", "forward_email");

  /**
   * EXO-90585 -- the four sending tools, and only they, take and declare an optional
   * {@code identity} limited to its three words; each stays approval-gated, and its
   * description tells the model to name, before approval, in whose name the mail
   * leaves, that the user's own is the default, and where the owner's allowed modes are
   * read. The listing of shares says it gives them.
   *
   * @throws Exception when the definitions cannot be read
   */
  @Test
  void anIdentityIsDeclaredOnTheFourSendingToolsAndTheApprovalMustNameIt() throws Exception {
    Map<String, JsonNode> definitions = readDefinitions();
    Set<String> taking = new TreeSet<>();
    for (Method method : EmailMcpTool.class.getDeclaredMethods()) {
      if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()
          && Arrays.stream(method.getParameters()).anyMatch(parameter -> "identity".equals(parameter.getName()))) {
        taking.add(toSnakeCase(method.getName()));
      }
    }
    Set<String> declaring = new TreeSet<>();
    definitions.forEach((name, tool) -> {
      if (tool.path("input_schema").path("properties").has("identity")) {
        declaring.add(name);
      }
    });
    assertEquals(new TreeSet<>(SENDING_TOOLS), taking);
    assertEquals(new TreeSet<>(SENDING_TOOLS), declaring);
    for (String name : SENDING_TOOLS) {
      JsonNode tool = definitions.get(name);
      JsonNode identity = tool.path("input_schema").path("properties").path("identity");
      assertEquals("[\"me\",\"owner_on_behalf\",\"owner\"]", identity.path("enum").toString(), name);
      tool.path("input_schema").path("required").forEach(required -> assertFalse("identity".equals(required.asText()),
                                                                                 name + ": the user's own name is the default"));
      assertTrue(tool.path("require_approval").asBoolean(false), name);
      String description = tool.path("description").asText();
      assertTrue(description.contains("goes out in the user's own name unless identity names the owner"), name + ": " + description);
      assertTrue(description.contains("always say in words in whose name it leaves: 'as you', 'on behalf of <owner's name>' or 'as <owner's name>'"),
                 name + ": " + description);
      assertTrue(description.contains("send_modes"), name + ": " + description);
      assertFalse(description.contains("from the user's own address, with a copy"), name + ": " + description);
      assertFalse(description.contains("still goes out from the user's own address"), name + ": " + description);
    }
    assertTrue(definitions.get("reply_all").path("description").asText().contains("The owner is not copied, whatever the identity."));
    assertTrue(definitions.get("list_shared_mailboxes").path("description").asText().contains("send_modes"));
  }

  /**
   * EXO-90585 -- the approval card of each sending tool shows the identity argument as
   * given, and says what an empty one and each of the three words mean: the card is
   * drawn from the arguments before the tool runs, so it is where the user sees in whose
   * name the mail leaves. The bundle ships in the webapp module, next door.
   *
   * @throws Exception when the bundle cannot be read
   */
  @Test
  void theApprovalCardOfEachSendingToolShowsTheIdentity() throws Exception {
    java.nio.file.Path bundle = java.nio.file.Path.of("..",
                                                      "email-connector-webapps",
                                                      "src",
                                                      "main",
                                                      "resources",
                                                      "locale",
                                                      "portlet",
                                                      "AiAgentChat_en.properties");
    assertTrue(java.nio.file.Files.isRegularFile(bundle), bundle.toAbsolutePath().toString());
    java.util.Properties texts = new java.util.Properties();
    try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(bundle, java.nio.charset.StandardCharsets.UTF_8)) {
      texts.load(reader);
    }
    for (String name : SENDING_TOOLS) {
      String text = texts.getProperty("AiAgentChat.tool.confirm." + name);
      assertTrue(text != null && text.contains("In whose name it leaves (empty or me: your own; owner_on_behalf: on behalf of the "
          + "shared mailbox's owner, you shown as the sender; owner: as the shared mailbox's owner, you not named): "
          + "<strong>{identity}</strong>"), name + ": " + text);
      assertTrue(text.contains("<strong>{mailbox}</strong>"), name + ": " + text);
    }
  }

  /**
   * Reads the shipped tool definitions off the classpath, keyed by tool name.
   *
   * @return the name → definition map
   * @throws Exception when the resource is missing or unparsable
   */
  private Map<String, JsonNode> readDefinitions() throws Exception {
    // Duplicate keys refused (EXO-90555 review): the MCP server's plain ObjectMapper keeps
    // the LAST of two "description" keys silently, so a sentence added to the first never
    // reaches the model -- search_emails carried two for months.
    JsonNode root = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                                      .readTree(getClass().getResourceAsStream("/ai-tool-definitions.json"));
    Map<String, JsonNode> byName = new HashMap<>();
    root.path("tools").forEach(tool -> byName.put(tool.path("name").asText(), tool));
    return byName;
  }

  /**
   * The camelCase → snake_case conversion the MCP server applies to derive a
   * tool name from a method name.
   *
   * @param methodName the Java method name
   * @return the tool name
   */
  private static String toSnakeCase(String methodName) {
    return methodName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
  }
}
