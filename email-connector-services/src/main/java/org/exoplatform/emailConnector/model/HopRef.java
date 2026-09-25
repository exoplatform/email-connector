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
 * The server half of an eXo rule that must also run at delivery: the server rule that
 * sets the rule's keyword on the mails its conditions match, so that eXo's own rule
 * picks them up at the next sync. A reconciliation keeps exactly these on the server.
 *
 * @param ref the server rule's reference, chosen by the eXo rule, e.g.
 *          {@code hop-<filterId>}
 * @param name the rule's name
 * @param matchAll true when every condition must hold
 * @param conditions the conditions, a copy of the eXo rule's
 * @param keyword the keyword to set, {@value ServerRule#TAG_PREFIX}{@code <id>}
 * @param stop whether the rules after it are skipped for a matched mail
 */
public record HopRef(String ref, String name, boolean matchAll, List<ServerRule.Condition> conditions, String keyword, boolean stop) {

  /** The prefix every hop's reference begins with. */
  public static final String REF_PREFIX = "hop-";

  /**
   * The server rule this hop is published as: its conditions, and one action, the
   * keyword.
   *
   * @return the rule, enabled
   * @throws IllegalArgumentException when the reference is not a hop's
   */
  public ServerRule toRule() {
    if (ref == null || !ref.startsWith(REF_PREFIX)) {
      // The user's rules are numbered; a hop is named apart, so neither overwrites the other.
      throw new IllegalArgumentException(ServerRule.INVALID);
    }
    return new ServerRule(ref,
                          name,
                          true,
                          matchAll,
                          conditions,
                          List.of(new ServerRule.Action(ServerRule.TAG, null, null, keyword)),
                          stop);
  }
}
