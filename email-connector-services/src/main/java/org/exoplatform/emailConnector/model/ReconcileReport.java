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
 * What a reconciliation of server-side hops did.
 *
 * @param published the references published or re-published because they were missing
 *          or differed
 * @param removed the references of hops removed from the server because no eXo rule
 *          needs them any more
 * @param rules the rules as the server holds them afterwards
 */
public record ReconcileReport(List<String> published, List<String> removed, ServerRuleSet rules) {

  /**
   * Keeps the lists unmodifiable, and never null.
   *
   * @param published the published references
   * @param removed the removed references
   * @param rules the rules afterwards
   */
  public ReconcileReport {
    published = published == null ? List.of() : List.copyOf(published);
    removed = removed == null ? List.of() : List.copyOf(removed);
  }

  /**
   * Whether the reconciliation wrote anything.
   *
   * @return true when a hop was published or removed
   */
  public boolean changed() {
    return !published.isEmpty() || !removed.isEmpty();
  }
}
