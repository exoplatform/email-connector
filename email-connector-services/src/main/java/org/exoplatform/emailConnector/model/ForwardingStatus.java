/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.model;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the mailbox band says about the caller's forward, cached by eXo so opening the
 * mailbox costs no connection to the mail server until it is older than
 * {@code email.connector.absence.status.ttlSeconds}; refreshed at once after a change
 * made in eXo. Kept in the {@code GLOBAL} setting context, which the caller cannot write
 * through the platform's settings REST: a band a session could switch off for itself
 * would be no safeguard.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ForwardingStatus {

  /** What could be established; null before anything was read. */
  private ForwardingState                     state;

  /** Where every mail is forwarded, for {@link ForwardingState#SERVER_FORWARD}. */
  private List<String>                        destinations = new ArrayList<>();

  /** Whether a copy stays in the mailbox; null when not known. */
  private Boolean                             keepCopy;

  /** Whether eXo set the forward. */
  private boolean                             managedByExo;

  /** The other client's script that may forward, empty when it has no name. */
  private String                              scriptName;

  /** eXo's rules that forward a copy of the mail they match. */
  private List<ForwardingSetting.RuleForward> ruleForwards = new ArrayList<>();

  /** When the server was last read, in milliseconds. */
  private long                                lastServerReadDate;

  /**
   * The status of a forward read from the server.
   *
   * @param forwarding the forward
   * @param readDate when it was read
   * @return the status
   */
  public static ForwardingStatus of(ForwardingSetting forwarding, long readDate) {
    ForwardingStatus status = new ForwardingStatus();
    status.setState(forwarding.state());
    status.setDestinations(new ArrayList<>(forwarding.destinations()));
    status.setKeepCopy(forwarding.keepCopy());
    status.setManagedByExo(forwarding.managedByExo());
    status.setScriptName(forwarding.scriptName());
    status.setRuleForwards(new ArrayList<>(forwarding.ruleForwards()));
    status.setLastServerReadDate(readDate);
    return status;
  }
}
