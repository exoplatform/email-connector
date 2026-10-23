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

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Where one user's mail import got to (EXO-90846): the state the import drawer polls
 * while the run happens off the request thread, the report it shows when the run ends,
 * and the limits the drawer tells the user about -- read from here so the interface
 * keeps no copy of them.
 * <p>
 * Every mail met lands in exactly one of {@link #added}, {@link #skipped} and
 * {@link #refused}; {@link #refusals} splits the last by reason.
 */
@Data
@NoArgsConstructor
public class MailImportState {

  /**
   * IN_PROGRESS while the files are read, SUCCESS when the run ended (even early, at a
   * limit -- {@link #messageCode} then says which), FAILURE when the run itself broke.
   * Null when no import ever ran.
   */
  private SyncStatus        status;

  /** The folder key the mails go into. */
  private String            folder;

  /** Mails added to the folder on the mail server. */
  private long              added;

  /** Mails the folder already held, by Message-ID, or met twice in the import. */
  private long              skipped;

  /** Mails not added: not a mail, too large, or refused by the mail server. */
  private long              refused;

  /** {@link #refused}, split by {@link MailImportRefusal} name. */
  private Map<String, Long> refusals = new LinkedHashMap<>();

  /** The message code of what cut the run short, or of a FAILURE; null otherwise. */
  private String            messageCode;

  /** The uploaded files' total size, in bytes. */
  private long              totalBytes;

  /** How many of those bytes were read so far: the run's progress. */
  private long              processedBytes;

  /** When the run started. */
  private Long              startedDate;

  /** When the run last wrote this state: a run that stops writing it has died. */
  private Long              updatedDate;

  /** When the run ended, null while it goes. */
  private Long              finishedDate;

  /** The most files one import takes. */
  private int               maxFiles;

  /** The most bytes one import takes, all files together. */
  private long              maxTotalBytes;

  /** The most mails one import reads. */
  private int               maxMails;

  /** The most one mail may weigh. */
  private int               maxMailBytes;
}
