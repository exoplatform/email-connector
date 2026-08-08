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

import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One contact in full, as the single-contact read answers it to the AI agent.
 * This is the ONLY tool shape that carries phone numbers — a broad search never
 * does — and even here no photo travels: no bytes, no photo URL, no avatar URL.
 * Store bookkeeping the agent cannot use (suppression, file ids, letter index,
 * counters) is deliberately absent.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(value = Include.NON_EMPTY)
public class ContactModel {

  /** The store row id. */
  private Long         id;

  @JsonProperty("display_name")
  private String       displayName;

  @JsonProperty("given_name")
  private String       givenName;

  @JsonProperty("family_name")
  private String       familyName;

  /** The primary address, ready to be used as a recipient. */
  private String       email;

  /** The contact's other known addresses, when an address book brought them. */
  @JsonProperty("secondary_emails")
  private List<String> secondaryEmails;

  /** Phone numbers, answered here and nowhere else in the toolset. */
  private List<String> phones;

  private String       organization;

  private String       title;

  /** Where the row came from: COLLECTED, MANUAL, DIRECTORY or CARDDAV. */
  private String       source;

  /** Whether the user starred this contact. */
  private boolean      favorite;

  /** Whether the address belongs to a platform colleague. */
  @JsonProperty("platform_user")
  private boolean      platformUser;

  /** The platform profile link, set only for platform colleagues. */
  @JsonProperty("profile_url")
  private String       profileUrl;

  /** When the user's mail last showed this address, for collected contacts. */
  @JsonProperty("last_seen_date")
  private Date         lastSeenDate;
}
