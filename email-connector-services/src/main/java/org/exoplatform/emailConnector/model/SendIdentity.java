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
package org.exoplatform.emailConnector.model;

import java.util.Date;

/**
 * The name a mail goes out under when a delegate writes in the owner's name (EXO-90583,
 * phase 3): the shape, the share it was granted on, and the owner's address and display
 * name -- all read from the share's row, never from the request. Only
 * {@code EmailDelegationService#checkSendMode} makes one; a send that has none is sent in
 * the sender's own name, which is the only default there is.
 *
 * @param mode {@link SendMode#ON_BEHALF} or {@link SendMode#AS}, never {@link SendMode#NONE}
 * @param delegationId the share the consent is on
 * @param ownerMailbox the owner's address, the mail's {@code From}
 * @param ownerFullName the owner's display name, null when the profile names none
 * @param consentDate when the owner set the consent the mail is sent under, so a refusal
 *          by the server is recorded against that consent and no later one
 */
public record SendIdentity(SendMode mode, long delegationId, String ownerMailbox, String ownerFullName, Date consentDate) {

  /**
   * Whether the mail names its sender apart from its author: {@code Sender:} the
   * delegate, on behalf only.
   *
   * @return true for {@link SendMode#ON_BEHALF}
   */
  public boolean namesTheSender() {
    return mode == SendMode.ON_BEHALF;
  }
}
