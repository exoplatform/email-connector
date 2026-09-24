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

import org.exoplatform.emailConnector.entity.UserEmailSettingEntity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class UserEmailSetting extends UserEmailSettingEntity {

  private String  emailConnectorImageUrl;

  private String  emailConnectorIcon;

  private String  emailConnectorName;

  private String  emailConnectorWebmailUrl;

  private boolean connected;

  /** Whether this user wants their address book synced over CardDAV. */
  private Boolean carddavEnabled;


  /** Whether the bound connector offers an address book at all, filled at read time. */
  private boolean carddavAvailable;

  /**
   * Set at read time when a stored password could not be decoded (a codec the instance
   * cannot initialise, a ciphertext written under another key): the model carries no
   * password although one is stored. What lets a write handed this model back keep the
   * stored ciphertext, where a model built by a caller -- a deliberate passwordless
   * connection -- clears it. Never serialised, never stored.
   */
  @JsonIgnore
  private transient boolean passwordUnreadable;

  /**
   * Set at read time when a password is stored and readable, so that a screen can
   * offer to keep it without ever receiving it: the password itself is not sent back
   * (EXO-90610). Serialised outbound only - a client cannot claim it - and never
   * stored.
   */
  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  private boolean passwordStored;

  /**
   * The decoded password, accepted in a request body and never written in a response
   * (EXO-90610): whatever endpoint returns this model, the password stays on the
   * server. Declared here and not on {@link UserEmailSettingEntity#getEmailPassword()},
   * because the entity is what the settings storage serialises - the same annotation
   * there would drop the password from every stored setting.
   *
   * @return the decoded password, for the server's own use
   */
  @Override
  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  public String getEmailPassword() {
    return super.getEmailPassword();
  }

  public UserEmailSetting(String emailConnectorId,
                          String emailAddress,
                          String emailPassword,
                          Integer emailBoxUserSyncPeriod,
                          SyncStatus emailSyncStatus,
                          int emailSyncFailedAttemps,
                          Long lastEmailSyncStartDate,
                          String emailConnectorImageUrl,
                          String emailConnectorIcon,
                          String emailConnectorName,
                          boolean connected) {
    super(emailConnectorId,
          emailAddress,
          emailPassword,
          emailBoxUserSyncPeriod,
          emailSyncStatus,
          emailSyncFailedAttemps,
          lastEmailSyncStartDate);
    this.emailConnectorImageUrl = emailConnectorImageUrl;
    this.emailConnectorIcon = emailConnectorIcon;
    this.emailConnectorName = emailConnectorName;
    this.connected = connected;

  }

}
