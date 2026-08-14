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
 * The remote mail folder a cached message belongs to. Stored as a string
 * discriminator on {@code EMAIL_BOX.FOLDER}; IMAP UIDs are per-folder, so the
 * cache is keyed by (userId, folder, mailRemoteId).
 */
public final class MailFolder {

  private MailFolder() {
  }

  public static final String INBOX    = "INBOX";

  public static final String SENT     = "SENT";

  public static final String ARCHIVE  = "ARCHIVE";

  // The Gmail "All Mail" (\All) superset. Never bulk-synced (it duplicates every
  // folder); rows land here only via on-demand thread completion, so an archived
  // message that lost its INBOX label still shows inline when its thread is opened.
  public static final String ALL_MAIL = "ALL_MAIL";

  // Unsent drafts. The one folder whose rows are AUTHORED here rather than mirrored
  // from the server, so it inverts the cache's usual direction: for INBOX / SENT /
  // ARCHIVE the server is the truth and the local row a copy, while a draft is
  // written locally first and pushed up afterwards. Everything that treats "not on
  // the server" as "delete the local row" therefore has to make an exception for it
  // — see EmailBoxService#cleanupObsoleteEmails.
  public static final String DRAFTS   = "DRAFTS";
}
