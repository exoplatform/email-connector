/**
 * Copyright (C) 2025 eXo Platform SAS
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

/**
 * Where a contact row came from. Stored as a string discriminator on
 * {@code EMAIL_CONTACT.SOURCE} (the {@link MailFolder} precedent) rather than an
 * enum column, so later sources (GRAPH) are a constant away, never a schema or
 * enum-ordinal migration. The platform directory is never COPIED into the
 * table: a {@link #DIRECTORY} row is a link — the platform username — whose
 * display fields (name, avatar, address) resolve live from the profile at read
 * time, so a renamed or departed colleague never rots into a stale snapshot.
 */
public final class EmailContactSource {

  private EmailContactSource() {
  }

  /** Collected automatically from the user's own cached mail. */
  public static final String COLLECTED = "COLLECTED";

  /** Typed by hand in the contact form. */
  public static final String MANUAL    = "MANUAL";

  /**
   * Imported from the platform's people directory: a link to the platform
   * identity, resolved live at read time — never a copy of the profile.
   */
  public static final String DIRECTORY = "DIRECTORY";

  /** Synced from a CardDAV address book (phase 3 fills these). */
  public static final String CARDDAV   = "CARDDAV";

  /** Read from Microsoft Graph (phase 4 seam, nothing writes it yet). */
  public static final String GRAPH     = "GRAPH";
}
