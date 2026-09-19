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

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A scheduled mail as the "Scheduled" view lists it: who it goes to, what it says
 * in one line, when it goes, and where it stands. The body is not carried; the
 * snippet is.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledEmail {

  // The draft's handle, which every action on the schedule is addressed by.
  private String               draftLocalId;

  // The draft's conversation, so a reader can open the whole mail -- body and
  // attachments -- through the conversation read that already serves scheduled drafts.
  private String               threadId;

  private List<EmailRecipient> to;

  private String               subject;

  private String               snippet;

  // The chosen instant, in epoch milliseconds (UTC).
  private long                 scheduledDate;

  // The zone the instant was chosen in, for display only.
  private String               timeZone;

  private ScheduledSendStatus  status;

  // A ScheduledSendError name when the mail was not sent, else null.
  private String               lastError;
}
