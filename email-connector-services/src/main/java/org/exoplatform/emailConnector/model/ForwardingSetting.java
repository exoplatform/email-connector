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

/**
 * A forward of the caller's own mailbox as the mail server holds it, for display only:
 * eXo never writes one in this phase.
 *
 * @param state what could be established
 * @param destinations where mail is forwarded, sorted; only for
 *          {@link ForwardingState#SERVER_FORWARD}, never read out of a script
 * @param keepCopy whether a copy stays in the mailbox; null when not known
 * @param scriptName the script that may forward, for
 *          {@link ForwardingState#MAY_FORWARD_BY_SCRIPT}; empty for a script without a
 *          name, null otherwise
 * @param manageUrl where the user manages the forward (the connector's webmail), or null
 */
public record ForwardingSetting(ForwardingState state,
                                List<String> destinations,
                                Boolean keepCopy,
                                String scriptName,
                                String manageUrl) {

  /**
   * Keeps the destinations unmodifiable and never null.
   *
   * @param state what could be established
   * @param destinations the destinations
   * @param keepCopy whether a copy is kept
   * @param scriptName the script that may forward
   * @param manageUrl where the forward is managed
   */
  public ForwardingSetting {
    destinations = destinations == null ? List.of() : List.copyOf(destinations);
  }

  /**
   * Nothing could be established.
   *
   * @return an {@link ForwardingState#UNKNOWN} answer
   */
  public static ForwardingSetting unknown() {
    return new ForwardingSetting(ForwardingState.UNKNOWN, null, null, null, null);
  }

  /**
   * No forward.
   *
   * @return a {@link ForwardingState#NONE} answer
   */
  public static ForwardingSetting none() {
    return new ForwardingSetting(ForwardingState.NONE, null, null, null, null);
  }

  /**
   * A forward the server holds structurally.
   *
   * @param destinations where mail is forwarded
   * @param keepCopy whether a copy is kept
   * @return a {@link ForwardingState#SERVER_FORWARD} answer, the destinations sorted
   */
  public static ForwardingSetting serverForward(List<String> destinations, boolean keepCopy) {
    return new ForwardingSetting(ForwardingState.SERVER_FORWARD,
                                 destinations == null ? null : destinations.stream().sorted().toList(),
                                 keepCopy,
                                 null,
                                 null);
  }

  /**
   * A script another client manages may forward.
   *
   * @param scriptName its name, empty when it has none
   * @return a {@link ForwardingState#MAY_FORWARD_BY_SCRIPT} answer
   */
  public static ForwardingSetting mayForwardByScript(String scriptName) {
    return new ForwardingSetting(ForwardingState.MAY_FORWARD_BY_SCRIPT, null, null, scriptName, null);
  }

  /**
   * The same answer, with where the user manages it.
   *
   * @param url the webmail's address, or null
   * @return the answer
   */
  public ForwardingSetting withManageUrl(String url) {
    return new ForwardingSetting(state, destinations, keepCopy, scriptName, url);
  }
}
