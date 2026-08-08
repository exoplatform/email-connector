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
package org.exoplatform.emailConnector.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of a contact search answered to the AI agent: exactly what it needs
 * to recognize the person and address them next (chain the {@code email} into
 * {@code send_email}), and nothing more personal than that.
 * <p>
 * Deliberately thinner than the store's own {@code EmailContact}: a list result
 * NEVER carries phone numbers, photo bytes or photo URLs — those stay behind
 * the single-contact read ({@code get_contact}, which still excludes photos),
 * so a broad query cannot bulk-harvest them.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(value = Include.NON_EMPTY)
public class ContactHitModel {

  /** The store row id, to chain into {@code get_contact}. */
  private Long    id;

  @JsonProperty("display_name")
  private String  displayName;

  /** The primary address, ready to be used as a recipient. */
  private String  email;

  private String  organization;

  private String  title;

  /** Where the row came from: COLLECTED, MANUAL, DIRECTORY or CARDDAV. */
  private String  source;

  /** Whether the user starred this contact. */
  private boolean favorite;

  /** Whether the address belongs to a platform colleague. */
  @JsonProperty("platform_user")
  private boolean platformUser;

  /** The platform profile link, set only for platform colleagues. */
  @JsonProperty("profile_url")
  private String  profileUrl;
}
