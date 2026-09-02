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
package org.exoplatform.emailConnector.entity;

import java.util.Date;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One folder of a user's mailbox that this add-on knows about beyond the built-in
 * seven -- a folder the user made themselves, discovered by walking their IMAP folder
 * list, and mirrored here only once they have asked for it.
 * <p>
 * The row is three things at once, and they have three different writers. It is the
 * REGISTRY entry whose id is the {@code CUSTOM:<id>} key every cached message of the
 * folder carries (written once, at discovery, and never changed -- a rename on the
 * server changes {@code REMOTE_NAME}, the rows keep their key). It is the user's OPT-IN
 * ({@code SYNC_ENABLED}, written from the settings screen). And it is the folder's own
 * sync memory -- the {@code FolderSyncSnapshot} columns and {@code LAST_SYNC_DATE},
 * written by the sync job every cycle. Two writers over one row is why this entity is
 * {@link DynamicUpdate}: without it, a read-modify-save from the settings screen flushes
 * an UPDATE over every column from the snapshot it read, and silently puts back the
 * sync checkpoint the job committed in between.
 * <p>
 * The snapshot lives on this row rather than in the mailbox's JSON sync state on
 * purpose. The rotation that bounds the sync cost ("the least recently checked folder
 * goes first") needs {@code LAST_SYNC_DATE} per folder anyway, and keeping the snapshot
 * beside it makes "when was this folder last checked, and what did it look like" one
 * read -- while leaving the JSON blob, with its positional-constructor hazard, alone.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@DynamicUpdate
@Entity(name = "EmailFolderEntity")
@Table(name = "EMAIL_FOLDER",
       uniqueConstraints = @UniqueConstraint(name = "UQ_EMAIL_FOLDER_USER_NAME", columnNames = { "USER_ID", "REMOTE_NAME" }))
public class EmailFolderEntity {

  @Id
  @SequenceGenerator(name = "SEQ_EMAIL_FOLDER_ID", sequenceName = "SEQ_EMAIL_FOLDER_ID", allocationSize = 1)
  @GeneratedValue(strategy = GenerationType.AUTO, generator = "SEQ_EMAIL_FOLDER_ID")
  @Column(name = "ID")
  private Long    id;

  @Column(name = "USER_ID")
  private String  userId;

  // The IMAP full name, as JavaMail hands it back (already decoded from modified
  // UTF-7), hierarchy included: "Customers/Acme", "INBOX.Factures". Unique per user
  // (UQ_EMAIL_FOLDER_USER_NAME), which is what makes a rediscovery an upsert rather than
  // a duplicate.
  @Column(name = "REMOTE_NAME")
  private String  remoteName;

  // The last path segment, which is what the interface shows -- exactly as the user
  // wrote it, in whatever language, never translated.
  @Column(name = "DISPLAY_NAME")
  private String  displayName;

  // The server's hierarchy separator, for rendering the path ("Customers / Acme"). Null
  // on a flat namespace.
  @Column(name = "DELIMITER")
  private String  delimiter;

  // CUSTOM for every row written today. Reserved so the built-in folder names could one
  // day move here out of the JSON sync state, the day a user wants to CHOOSE which
  // folder is their Archive -- a decision deliberately not taken now.
  @Column(name = "TYPE")
  private String  type;

  // The opt-in. False at discovery: nothing is mirrored for a folder the user never
  // chose, which is the first of the four rules that keep the sync cost bounded.
  @Column(name = "SYNC_ENABLED")
  private boolean syncEnabled;

  // When the user opted in, so that if an administrator lowers the cap below what a
  // user already enabled, the sync keeps the OLDEST opt-ins and ignores the rest --
  // deterministically, and without deleting anything.
  @Column(name = "ENABLED_DATE")
  private Date    enabledDate;

  // The last discovery walk did not see this folder. One grace walk, then the row and
  // its mirrored rows go: a folder deleted (or renamed -- there is no reliable IMAP
  // signal to tell the two apart) on the server is a folder the user no longer has.
  @Column(name = "MISSING")
  private boolean missing;

  @Column(name = "DISCOVERED_DATE")
  private Date    discoveredDate;

  @Column(name = "LAST_SEEN_DATE")
  private Date    lastSeenDate;

  // Stamped whether the last cycle skipped the folder (unchanged) or synced it: a skip
  // IS a successful check, and the rotation orders on this column.
  @Column(name = "LAST_SYNC_DATE")
  private Date    lastSyncDate;

  // The folder's FolderSyncSnapshot, column by column. Null when never captured, in
  // which case the next sync takes the full path -- the same rule the JSON state has.
  @Column(name = "UID_VALIDITY")
  private Long    uidValidity;

  @Column(name = "UID_NEXT")
  private Long    uidNext;

  @Column(name = "MESSAGE_COUNT")
  private Long    messageCount;

  @Column(name = "HIGHEST_MODSEQ")
  private Long    highestModSeq;

  @Column(name = "WINDOW_SIZE")
  private Integer windowSize;
}
