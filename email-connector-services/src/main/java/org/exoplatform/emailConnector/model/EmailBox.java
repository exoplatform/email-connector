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

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailBox {

  private List<Email>                emails;

  private SyncStatus                 emailSyncStatus;

  private String                     webmailUrl;

  // What each conversation looks like from OUTSIDE the folder being listed, keyed by
  // thread id: its total message count across every folder (INBOX/SENT/ARCHIVE/
  // ALL_MAIL/DRAFTS) and whether it carries an unsent draft. The list shows the full
  // conversation count on each thread badge — like Gmail — rather than only its
  // messages in the listed folder, and marks a conversation the user has a reply
  // half-written in. Both are facts the listing itself cannot hold: it only ever
  // carries one folder's rows.
  private Map<String, ThreadSummary> threadSummaries;

  // Message count per folder, so the list's folder switch only offers folders with
  // mail (e.g. no empty Archive tab on Gmail).
  private Map<String, Integer>       folderCounts;
}
