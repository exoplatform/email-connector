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
 * A user's email signature as the REST layer speaks it: the stored preference
 * plus what only the server can compute — the default signature assembled from
 * the profile. The client renders {@code customHtml} when there is one and
 * {@code defaultHtml} otherwise; sending {@code customHtml} null on a save
 * means "back to the default", which keeps following the profile.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EmailSignature {

  /** Whether the composer opens with the signature. Null reads as ON. */
  private Boolean enabled;

  /** The user's own signature markup, or null when the default applies. */
  private String  customHtml;

  /**
   * The signature computed from the caller's profile as it stands right now.
   * Never stored — a stored copy would go stale the day the profile changes.
   */
  private String  defaultHtml;

  /**
   * Whether the signature image is one the user uploaded, rather than the
   * company logo. What the settings screen needs to offer "reset the image".
   */
  private boolean customLogo;
}
