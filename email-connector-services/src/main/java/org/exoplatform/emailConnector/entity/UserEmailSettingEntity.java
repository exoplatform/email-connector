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

import java.util.List;

import org.exoplatform.emailConnector.model.SyncStatus;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UserEmailSettingEntity {

  private String       emailConnectorId;

  private String       emailAddress;

  private String       emailPassword;

  private Integer      emailBoxUserSyncPeriod;

  private SyncStatus   emailSyncStatus;

  private int          emailSyncFailedAttemps;

  private Long         lastEmailSyncStartDate;

  // New-mail notification preference. notifyAllCategories null/true => notify for every new email
  // (default, current behaviour); false => notify only for emails whose Inbox category is in
  // notifyCategories. Unset (null) resolves to "All".
  private Boolean      notifyAllCategories;

  // Inbox category ids to be notified about when notifyAllCategories is false.
  private List<Long>   notifyCategories;

  // Default Inbox category the mailbox drawer opens positioned to; null => None (show all).
  private Long         defaultCategoryView;

  /**
   * Whether this user wants their address book synced. Opt-in: a connector
   * offering CardDAV does not mean every user of it wants their contacts pulled
   * in, and the sync costs a request per user per period.
   * <p>
   * There is deliberately no second credential beside it. The address book is the
   * same provider as the mailbox, reached with the same account, so the sync uses
   * the mail credentials. A deployment whose address book lives on a different
   * account is the multi-account plan's problem, not this one's.
   */
  private Boolean      carddavEnabled;


  public UserEmailSettingEntity(String emailConnectorId,
                                String emailAddress,
                                String emailPassword,
                                Integer emailBoxUserSyncPeriod,
                                SyncStatus emailSyncStatus,
                                int emailSyncFailedAttemps,
                                Long lastEmailSyncStartDate) {
    this.emailConnectorId = emailConnectorId;
    this.emailAddress = emailAddress;
    this.emailPassword = emailPassword;
    this.emailBoxUserSyncPeriod = emailBoxUserSyncPeriod;
    this.emailSyncStatus = emailSyncStatus;
    this.emailSyncFailedAttemps = emailSyncFailedAttemps;
    this.lastEmailSyncStartDate = lastEmailSyncStartDate;
  }
}
