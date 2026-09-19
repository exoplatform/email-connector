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

import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of the schedule table, as the storage hands it to the service: a flat
 * copy, nothing derived. The mail's content is not here; it is the draft row this
 * one points at ({@code emailId}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailScheduledSend {

  private Long                id;

  private Long                emailId;

  private String              userId;

  private String              draftLocalId;

  private Date                scheduledDate;

  private String              timeZone;

  private ScheduledSendStatus status;

  private Date                nextAttemptDate;

  private int                 attempts;

  private String              claimedBy;

  private Date                claimedDate;

  private String              lastError;

  private Date                createdDate;

  private Date                updatedDate;
}
