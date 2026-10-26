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
 * What a rule would match among the mails eXo keeps of the owner's inbox, before it is
 * saved.
 *
 * @param total how many of them it matches
 * @param scanned how many were looked at: the cached inbox, not the whole mailbox
 * @param sample the newest matches, at most ten
 * @param notPreviewable the condition fields eXo cannot evaluate on a cached mail,
 *          present in the rule: {@code HEADER}, {@code MESSAGE_SIZE}
 * @param approximate true for a rule the server runs, whose comparisons are the
 *          server's and not eXo's
 */
public record FilterPreview(int total, int scanned, List<Row> sample, List<String> notPreviewable, boolean approximate) {

  /**
   * Keeps the lists unmodifiable, and never null.
   *
   * @param total the count
   * @param scanned the window
   * @param sample the sample
   * @param notPreviewable the fields not evaluated
   * @param approximate whether the count is the server's approximation
   */
  public FilterPreview {
    sample = sample == null ? List.of() : List.copyOf(sample);
    notPreviewable = notPreviewable == null ? List.of() : List.copyOf(notPreviewable);
  }

  /**
   * One mail of the sample.
   *
   * @param emailId the cached row's id
   * @param subject its subject
   * @param sender its sender's address
   * @param receivedDate when it arrived, in milliseconds
   */
  public record Row(Long emailId, String subject, String sender, Long receivedDate) {
  }
}
