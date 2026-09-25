/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.model.ServerRule.Action;
import org.exoplatform.emailConnector.model.ServerRule.Condition;

/**
 * The closed vocabulary of a server rule: what validation accepts, how it normalises,
 * and the code it refuses everything else with.
 */
public class ServerRuleTest {

  private static final Condition FROM_A = new Condition("FROM", "CONTAINS", null, "a");

  private static final Action    STAR   = new Action("STAR", null, null, null);

  /**
   * Codes are upper-cased, texts trimmed, a header name lower-cased, a domain without
   * its {@code @}, and the fields a condition or action does not use dropped.
   */
  @Test
  public void testNormalisation() {
    ServerRule rule = new ServerRule(null,
                                     "  Name  ",
                                     true,
                                     true,
                                     List.of(new Condition("from", "matches_domain", "ignored", " @Acme.COM "),
                                             new Condition("header", "contains", " X-Spam ", " yes "),
                                             new Condition("is_list", "is_true", "x", "y")),
                                     List.of(new Action("star", "CUSTOM:1", "p", "k"), new Action("move_to_folder", "CUSTOM:1", "Box", "k")),
                                     false).validated();
    assertEquals("Name", rule.name());
    assertEquals(new Condition("FROM", "MATCHES_DOMAIN", null, "acme.com"), rule.conditions().get(0));
    assertEquals(new Condition("HEADER", "CONTAINS", "x-spam", "yes"), rule.conditions().get(1));
    assertEquals(new Condition("IS_LIST", "IS_TRUE", null, null), rule.conditions().get(2));
    assertEquals(new Action("STAR", null, null, null), rule.actions().get(0));
    assertEquals(new Action("MOVE_TO_FOLDER", "CUSTOM:1", "Box", null), rule.actions().get(1));
  }

  /**
   * Each bound of the vocabulary answers its code.
   */
  @Test
  public void testRefusals() {
    assertCode(ServerRule.INVALID, rule("bad ref", "n", List.of(FROM_A), List.of(STAR)));
    assertCode(ServerRule.INVALID_NAME, rule(null, " ", List.of(FROM_A), List.of(STAR)));
    assertCode(ServerRule.INVALID_NAME, rule(null, "x".repeat(101), List.of(FROM_A), List.of(STAR)));
    assertCode(ServerRule.INVALID_NAME, rule(null, "a\nb", List.of(FROM_A), List.of(STAR)));
    assertCode(ServerRule.INVALID_CONDITION, rule(null, "n", List.of(), List.of(STAR)));
    assertCode(ServerRule.INVALID_CONDITION, rule(null, "n", java.util.Collections.nCopies(11, FROM_A), List.of(STAR)));
    for (Condition condition : List.of(new Condition("BODY", "CONTAINS", null, "a"),
                                       new Condition("FROM", "GT", null, "a"),
                                       new Condition("FROM", "CONTAINS", null, " "),
                                       new Condition("FROM", "CONTAINS", null, "a\r\nb"),
                                       new Condition("FROM", "CONTAINS", null, "a b"),
                                       new Condition("FROM", "CONTAINS", null, "x".repeat(501)),
                                       new Condition("FROM", "MATCHES_DOMAIN", null, "acme.com\"; stop"),
                                       new Condition("SUBJECT", "MATCHES_DOMAIN", null, "acme.com"),
                                       new Condition("HEADER", "CONTAINS", "X-Bad: y", "a"),
                                       new Condition("HEADER", "CONTAINS", null, "a"),
                                       new Condition("MESSAGE_SIZE", "GT", null, "0"),
                                       new Condition("MESSAGE_SIZE", "GT", null, "1K"),
                                       new Condition("MESSAGE_SIZE", "GT", null, "10000001"),
                                       new Condition("MESSAGE_SIZE", "CONTAINS", null, "1"),
                                       new Condition("IS_LIST", "CONTAINS", null, "a"),
                                       new Condition("FROM", null, null, "a"))) {
      assertCode(ServerRule.INVALID_CONDITION, rule(null, "n", List.of(condition), List.of(STAR)));
    }
    assertCode(ServerRule.INVALID_ACTION, rule(null, "n", List.of(FROM_A), List.of()));
    for (List<Action> actions : List.of(List.of(new Action("REDIRECT", null, null, null)),
                                        List.of(new Action("DISCARD", null, null, null)),
                                        List.of(STAR, STAR),
                                        List.of(new Action("MOVE_TO_FOLDER", "CUSTOM:1", null, null)),
                                        List.of(new Action("MOVE_TO_FOLDER", "CUSTOM:1", "a\nb", null)),
                                        List.of(new Action("MARK_JUNK", "JUNK", "Junk", null), new Action("DELETE", "TRASH", "Trash", null)),
                                        List.of(new Action("TAG", null, null, "\\Deleted")),
                                        List.of(new Action("TAG", null, null, "exo-filter-a b")))) {
      assertCode(ServerRule.INVALID_ACTION, rule(null, "n", List.of(FROM_A), actions));
    }
  }

  /**
   * What decides the wrapper's order and what reconciliation may remove.
   */
  @Test
  public void testFilesOrStopsAndHops() {
    assertFalse(rule(null, "n", List.of(FROM_A), List.of(STAR)).filesOrStops());
    assertTrue(rule(null, "n", List.of(FROM_A), List.of(new Action("DELETE", "TRASH", "Trash", null))).filesOrStops());
    assertTrue(new ServerRule(null, "n", true, true, List.of(FROM_A), List.of(STAR), true).filesOrStops());
    assertTrue(rule(null, "n", List.of(FROM_A), List.of(new Action("TAG", null, null, "exo-filter-1"))).isHop());
    assertFalse(rule(null, "n", List.of(FROM_A), List.of(STAR, new Action("TAG", null, null, "exo-filter-1"))).isHop());
    HopRef hop = new HopRef("hop-1", "Hop", true, List.of(FROM_A), "exo-filter-1", false);
    assertTrue(hop.toRule().validated().isHop());
  }

  /**
   * A rule, enabled, matching all, not stopping.
   *
   * @param ref the reference
   * @param name the name
   * @param conditions the conditions
   * @param actions the actions
   * @return the rule
   */
  private static ServerRule rule(String ref, String name, List<Condition> conditions, List<Action> actions) {
    return new ServerRule(ref, name, true, true, conditions, actions, false);
  }

  /**
   * Asserts that validating a rule fails with a code.
   *
   * @param code the code
   * @param rule the rule
   */
  private static void assertCode(String code, ServerRule rule) {
    assertEquals(code, assertThrows(IllegalArgumentException.class, rule::validated).getMessage(), String.valueOf(rule));
  }
}
