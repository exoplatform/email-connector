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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One mail of the user's Scheduled view as the scheduled-send tools return it
 * (EXO-91996): what the Email app's Scheduled view shows, plus the handle to change or
 * cancel it.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(value = Include.NON_EMPTY)
public class ScheduledEmailModel {

  /** The draft's id, to pass as draft_id to reschedule_email and cancel_scheduled_email. */
  @JsonProperty("draft_id")
  private String       draftId;

  private List<String> to;

  private String       subject;

  /** The first words of the body. */
  private String       snippet;

  /** When it is sent, in its time zone, such as 2026-10-23T08:30:00. */
  @JsonProperty("send_at")
  private String       sendAt;

  /** The time zone it was scheduled in. */
  @JsonProperty("time_zone")
  private String       timeZone;

  /** The same instant in UTC, such as 2026-10-23T06:30:00Z. */
  @JsonProperty("send_at_utc")
  private String       sendAtUtc;

  /**
   * SCHEDULED (waiting), SENDING, FAILED (not sent: see error), UNCERTAIN (it may have
   * been sent: the user checks their Sent folder in the Email app) or SENT.
   */
  private String       status;

  /** Why it was not sent, when it failed or is uncertain. */
  private String       error;

  /**
   * The mailbox shared with the user that it was written in and is sent from, as its
   * owner's address; empty for the user's own mailbox.
   */
  private String       mailbox;

  /** Where the user opens their Scheduled view in the Email app. */
  private String       link;
}
