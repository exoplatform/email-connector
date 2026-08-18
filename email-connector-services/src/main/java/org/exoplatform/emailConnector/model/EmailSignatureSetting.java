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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The signature preference exactly as it is STORED — its own settings document,
 * under its own key, deliberately not new fields on {@code userEmailSetting}:
 * that document is rebuilt whole by {@code setUserEmailSetting}, which
 * hand-copies every field, and a field missed in that copy block is silently
 * dropped on the next unrelated save. A separate key cannot lose to a race it
 * never enters.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EmailSignatureSetting {

  /**
   * Whether the composer should open with the signature at all. Null reads as
   * ON — the feature's whole point is a signature at the bottom of every
   * message, so a user who never touched the switch gets one.
   */
  private Boolean enabled;

  /**
   * The signature as the user rewrote it, already sanitized at save. Null means
   * "never customised": the computed default is used, and keeps following the
   * profile as it changes — which is why null is stored rather than a copy of
   * the default at the time of the first visit.
   */
  private String  customHtml;

  /**
   * The stored file behind the user's own signature image, when they replaced
   * the company logo with one. Null means the branding logo is the image.
   */
  private Long    logoFileId;
}
