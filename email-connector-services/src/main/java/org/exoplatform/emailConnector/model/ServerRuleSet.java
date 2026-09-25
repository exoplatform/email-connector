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

import java.util.List;
import java.util.Optional;

/**
 * The server rules eXo manages for one user, as the server holds them, in the order the
 * server applies them, with where they stand.
 *
 * @param rules the rules, in order
 * @param state where they stand compared with what eXo last wrote; the engine answers
 *          {@link ServerRulesState#OWN} for a script it can read, and the caller turns it
 *          into {@link ServerRulesState#MODIFIED} when the hash differs from its own
 * @param foreignScriptName the script another client manages that the server also runs,
 *          or whose activity is in the way; null when none
 * @param scriptHash the SHA-256 of eXo's script as the server holds it; null when none
 */
public record ServerRuleSet(List<ServerRule> rules, ServerRulesState state, String foreignScriptName, String scriptHash) {

  /**
   * Keeps the list unmodifiable, and never null.
   *
   * @param rules the rules
   * @param state the state
   * @param foreignScriptName the other script
   * @param scriptHash the hash
   */
  public ServerRuleSet {
    rules = rules == null ? List.of() : List.copyOf(rules);
  }

  /**
   * No rule, no script.
   *
   * @return the empty set
   */
  public static ServerRuleSet none() {
    return new ServerRuleSet(List.of(), ServerRulesState.NONE, null, null);
  }

  /**
   * The rule of a reference.
   *
   * @param ref the reference
   * @return the rule, or empty
   */
  public Optional<ServerRule> find(String ref) {
    return rules.stream().filter(rule -> rule.ref() != null && rule.ref().equals(ref)).findFirst();
  }

  /**
   * The same set in another state.
   *
   * @param newState the state
   * @return the set
   */
  public ServerRuleSet withState(ServerRulesState newState) {
    return new ServerRuleSet(rules, newState, foreignScriptName, scriptHash);
  }
}
