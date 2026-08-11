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
package org.exoplatform.emailConnector.entity;

import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of the per-user contact store. No all-args constructor on purpose
 * (unlike the older entities): rows are built by the storage mapper with
 * setters, so adding a column never silently shifts positional call sites.
 */
@Data
@NoArgsConstructor
@Entity(name = "EmailContactEntity")
@Table(name = "EMAIL_CONTACT")
public class EmailContactEntity {

  @Id
  @SequenceGenerator(name = "SEQ_EMAIL_CONTACT_ID", sequenceName = "SEQ_EMAIL_CONTACT_ID", allocationSize = 1)
  @GeneratedValue(strategy = GenerationType.AUTO, generator = "SEQ_EMAIL_CONTACT_ID")
  @Column(name = "ID")
  private Long    id;

  @Column(name = "USER_ID")
  private String  userId;

  // One of the EmailContactSource discriminators (COLLECTED / MANUAL / CARDDAV, later GRAPH).
  @Column(name = "SOURCE")
  private String  source;

  // Lowercased, trimmed. Unique with USER_ID: dedupe is a database fact, not a service promise.
  @Column(name = "PRIMARY_EMAIL")
  private String  primaryEmail;

  // Secondary addresses as a joined "type,value;type,value" string (the EMAIL_BOX recipients
  // precedent; a child table for at most a handful of values is overkill). VARCHAR, not CLOB,
  // so it stays LIKE-searchable on HSQLDB.
  @Column(name = "EMAILS")
  private String  emails;

  @Column(name = "DISPLAY_NAME")
  private String  displayName;

  @Column(name = "GIVEN_NAME")
  private String  givenName;

  @Column(name = "FAMILY_NAME")
  private String  familyName;

  // Uppercased, diacritic-stripped "FAMILY GIVEN" / display name / local-part. Drives the
  // in-bucket alphabetical order.
  @Column(name = "SORT_NAME")
  private String  sortName;

  // The letter bucket (0..25 = A..Z of SORT_NAME's first character, 26 = everything else).
  // An integer rather than the letter itself because ordering punctuation, digits and
  // non-Latin scripts against letters is collation-dependent (MySQL's UCA sorts them before
  // letters, codepoint collations scatter them before AND after) — and the A–Z rail's
  // offset arithmetic needs every bucket contiguous on every dialect.
  @Column(name = "SORT_BUCKET")
  private int     sortBucket;

  // Semicolon-joined phone numbers; phase 1 manual add may set one.
  @Column(name = "PHONES")
  private String  phones;

  @Column(name = "ORGANIZATION")
  private String  organization;

  @Column(name = "TITLE")
  private String  title;

  // The platform identity a DIRECTORY row links to. The row's name/email columns are
  // only the fallback for when this profile is gone: display resolves live from it.
  @Column(name = "PLATFORM_USERNAME")
  private String  platformUsername;

  // CardDAV bookkeeping (phase 3): the vCard photo stored via FileService, the resource
  // href/etag/uid on the server, and the email connector carrying the address book.
  @Column(name = "PHOTO_FILE_ID")
  private Long    photoFileId;

  @Column(name = "HREF")
  private String  href;

  @Column(name = "ETAG")
  private String  etag;

  @Column(name = "VCARD_UID")
  private String  vcardUid;

  @Column(name = "CONNECTOR_ID")
  private Long    connectorId;

  // The "remove a collected contact" tombstone: hidden everywhere, skipped by the
  // collection upsert, so a deleted collected contact stays deleted.
  @Column(name = "SUPPRESSED")
  private boolean suppressed;

  @Column(name = "SEEN_COUNT")
  private long    seenCount;

  @Column(name = "LAST_SEEN_DATE")
  private Date    lastSeenDate;

  @Column(name = "CREATED_DATE")
  private Date    createdDate;

  @Column(name = "UPDATED_DATE")
  private Date    updatedDate;
}
