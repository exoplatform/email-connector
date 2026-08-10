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

import java.util.Date;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One contact of one user's personal contact store. No all-args constructor on
 * purpose: this model is built at several sites (storage mapping, REST bodies,
 * tests) and a growing positional constructor breaks call sites silently at a
 * distance — setters keep every site explicit.
 */
@Data
@NoArgsConstructor
public class EmailContact {

  private Long         id;

  /** The store owner; every query is scoped by it. */
  private String       userId;

  /** One of the {@link EmailContactSource} discriminators. */
  private String       source;

  /** Lowercased, trimmed; with the owner it is the contact's natural key. */
  private String       primaryEmail;

  /**
   * The contact's other addresses, normalized; the contact is reachable at any
   * of them. On a write the field is three-state, like {@link #photoUploadId}:
   * absent (null) says nothing about them — the stored set is kept, and a
   * moving primary is demoted into it — while a present list is the
   * authoritative set, the form sending every address row it shows.
   */
  private List<String> secondaryEmails;

  private String       displayName;

  private String       givenName;

  private String       familyName;

  /**
   * The uppercased, diacritic-stripped sort key ("FAMILY GIVEN", falling back to
   * the display name, falling back to the address local-part). Derived, never
   * user-visible.
   */
  private String       sortName;

  /**
   * The alphabetical letter this contact files under: "A".."Z", or "#" for sort
   * keys that do not start with a Latin letter. Computed server-side from the
   * sort bucket so the client groups exactly the way the server orders.
   */
  private String       letter;

  private List<String> phones;

  private String       organization;

  private String       title;

  /**
   * The birthday as this store's canonical text: {@code YYYY-MM-DD} when the
   * year is known, {@code --MM-DD} when it is not. Text rather than a date
   * because a year-less birthday — {@code BDAY:--0412}, legal vCard and common
   * in Apple exports — has no honest {@code Date} representation: forcing a
   * fake year is exactly the corruption this field exists to avoid.
   */
  private String       birthday;

  /** The postal address, structured — see {@link PostalAddress} for why. */
  private PostalAddress postalAddress;

  /** The free-text note, possibly multi-line, capped at storage. */
  private String       note;

  /** The contact's web page, as the card or the user wrote it. */
  private String       website;

  /**
   * The platform identity a {@link EmailContactSource#DIRECTORY} row links to.
   * Display fields of such a row resolve live from this profile at read time;
   * the stored name/address are only the fallback when the profile is gone.
   */
  private String       platformUsername;

  /**
   * The removed-collected-contact tombstone: a suppressed row is visible nowhere
   * and the collection upsert skips it, so a deleted collected contact does not
   * resurrect on the next mail from that person.
   */
  private boolean      suppressed;

  /** How many times collection saw this address; feeds the suggest ranking (phase 2). */
  private long         seenCount;

  private Date         lastSeenDate;

  private Date         createdDate;

  private Date         updatedDate;

  /**
   * The {@code FileService} id of the photo the user set on this contact, null
   * when they never set one. Read-only for the caller: it is the service's
   * answer about whether a photo of OURS exists (which
   * {@link #avatarUrl} alone cannot say, a platform profile matching the
   * address filling that same field), so the form knows whether to offer
   * removal. Writing a photo goes through {@link #photoUploadId}.
   */
  private Long         photoFileId;

  /**
   * Write-only, never answered: how a request asks for a photo change. Three
   * distinct states, because "keep what is stored" and "remove what is stored"
   * are different intents and a DTO the client round-trips cannot express them
   * with one value:
   * <ul>
   * <li>{@code null} (absent from the body) — leave the stored photo alone;</li>
   * <li>an empty string — remove the stored photo;</li>
   * <li>an upload id — set (or replace) the photo from that upload.</li>
   * </ul>
   */
  private String       photoUploadId;

  /**
   * Whether the store owner has starred this contact. Read-time enrichment
   * only, never stored on the row: the favorite lives in the platform's
   * favorites store, keyed by this row's id, and is resolved per viewer when
   * the contact is answered.
   */
  private boolean      favorite;

  /** Platform profile avatar when the address is a platform user's, resolved at read time. */
  private String       avatarUrl;

  /** Platform profile link when the address is a platform user's, resolved at read time. */
  private String       profileUrl;
}
