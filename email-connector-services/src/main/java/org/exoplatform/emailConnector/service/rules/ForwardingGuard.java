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
package org.exoplatform.emailConnector.service.rules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.ForwardingDestination;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.UserEmailSettingService;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.social.util.JsonUtils;

/**
 * Who may forward mail where: the deployment's switch per connector, its allowed
 * destination domains, and the destinations each user confirmed. The one place both the
 * forwarding service (before anything is written) and the rules engines (when the Sieve
 * is generated, or a forward sent to the server) ask, so the two checks cannot drift.
 * <p>
 * <b>The switch</b>, {@value #AUTHORING_PROPERTY}{@code [.<connectorId>]}, default
 * false: the per-connector key wins over the deployment-wide one, except that the
 * deployment-wide key set to {@code false} switches authoring off everywhere at once,
 * whatever the per-connector keys say -- the kill switch. Off, eXo sets no new forward
 * and no new forwarding rule; a forward eXo set before stays on the server, shown with
 * its band, and can be removed.
 * <p>
 * <b>The allowed domains</b>, {@value #ALLOWED_DOMAINS_PROPERTY}{@code [.<connectorId>]},
 * a comma- or space-separated list of domains, matched exactly; when empty, the domain
 * of the user's own mailbox address -- mail stays in the organisation.
 * <p>
 * <b>The confirmed destinations</b> are kept per user in the {@code GLOBAL} setting
 * context, never in the user's own: the platform's settings REST lets a user write any
 * key of their own context, and a confirmation a session could write for itself would
 * prove nothing. Only this class writes them, after a code sent to the destination was
 * entered.
 */
@Component
public class ForwardingGuard {

  private static final Log     LOG                      = ExoLogger.getLogger(ForwardingGuard.class);

  /** The deployment's switch for setting a forward from eXo, per connector. */
  public static final String   AUTHORING_PROPERTY       = "email.connector.forwarding.authoring.enabled";

  /** The domains a forward may go to, per connector. */
  public static final String   ALLOWED_DOMAINS_PROPERTY = "email.connector.forwarding.allowedDomains";

  /** Forwarding is switched off for the caller's connector. */
  public static final String   DISABLED                 = "emailConnector.forwarding.disabled";

  /** The destination's domain is not one the connector allows. */
  public static final String   DOMAIN_NOT_ALLOWED       = "emailConnector.forwarding.destination.notAllowed";

  /** The destination was never confirmed by the code sent to it. */
  public static final String   NOT_CONFIRMED            = "emailConnector.forwarding.destination.notConfirmed";

  /** The destination is the mailbox itself. */
  public static final String   OWN_ADDRESS              = "emailConnector.forwarding.destination.own";

  /** The setting scope of everything forwarding keeps, in the {@code GLOBAL} context. */
  public static final Scope    FORWARDING_SCOPE         = Scope.APPLICATION.id("EMAIL_CONNECTOR_FORWARDING");

  /** The key prefix of a user's confirmed destinations, followed by the user name. */
  static final String          CONFIRMED_KEY_PREFIX     = "confirmed.";

  /** The most confirmed destinations kept per user; the oldest goes first. */
  static final int             MAX_CONFIRMED            = 10;

  @Autowired
  private SettingService          settingService;

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  /**
   * Whether eXo may set a forward, or a rule that forwards, on a connector.
   *
   * @param connector the connector, possibly null
   * @return true only when the deployment enabled it for this connector and did not
   *         switch it off everywhere
   */
  public static boolean authoringEnabled(EmailConnector connector) {
    String global = StringUtils.trimToNull(System.getProperty(AUTHORING_PROPERTY));
    if ("false".equalsIgnoreCase(global) || connector == null || connector.getId() == null) {
      return false;
    }
    String own = StringUtils.trimToNull(System.getProperty(AUTHORING_PROPERTY + "." + connector.getId()));
    return Boolean.parseBoolean(own == null ? global : own);
  }

  /**
   * The domains a forward may go to on a connector.
   *
   * @param connector the connector
   * @param mailboxAddress the user's own mailbox address, whose domain is the default
   * @return the domains, lower-case, never null; empty when nothing is allowed
   */
  public static List<String> allowedDomains(EmailConnector connector, String mailboxAddress) {
    String configured = null;
    if (connector != null && connector.getId() != null) {
      configured = StringUtils.trimToNull(System.getProperty(ALLOWED_DOMAINS_PROPERTY + "." + connector.getId()));
    }
    if (configured == null) {
      configured = StringUtils.trimToNull(System.getProperty(ALLOWED_DOMAINS_PROPERTY));
    }
    Set<String> domains = new LinkedHashSet<>();
    if (configured != null) {
      for (String item : configured.split("[,\\s]+")) {
        String domain = StringUtils.removeStart(item.trim().toLowerCase(Locale.ROOT), "@");
        if (!domain.isEmpty()) {
          domains.add(domain);
        }
      }
    } else if (StringUtils.contains(mailboxAddress, '@')) {
      domains.add(ForwardingDestination.domainOf(mailboxAddress.trim().toLowerCase(Locale.ROOT)));
    }
    return new ArrayList<>(domains);
  }

  /**
   * Whether a destination's domain is allowed on a connector.
   *
   * @param destination a normalised destination
   * @param connector the connector
   * @param mailboxAddress the user's own mailbox address
   * @return true when its domain is exactly one of the allowed ones
   */
  public static boolean isAllowedDomain(String destination, EmailConnector connector, String mailboxAddress) {
    return destination != null && allowedDomains(connector, mailboxAddress).contains(ForwardingDestination.domainOf(destination));
  }

  /**
   * The destinations a user confirmed, by entering the code sent to each.
   *
   * @param username the user
   * @return the destinations, normalised, never null
   */
  @SuppressWarnings("unchecked")
  public Set<String> confirmedDestinations(String username) {
    SettingValue<?> value = settingService.get(Context.GLOBAL, FORWARDING_SCOPE, CONFIRMED_KEY_PREFIX + username);
    Set<String> confirmed = new LinkedHashSet<>();
    if (value == null || value.getValue() == null) {
      return confirmed;
    }
    try {
      List<Object> stored = JsonUtils.fromJsonString(value.getValue().toString(), List.class);
      for (Object item : stored == null ? List.of() : stored) {
        try {
          confirmed.add(ForwardingDestination.normalize(String.valueOf(item)));
        } catch (IllegalArgumentException e) {
          LOG.debug("A stored forwarding destination of user {} is not an address and is ignored", username);
        }
      }
    } catch (RuntimeException e) {
      LOG.debug("The confirmed forwarding destinations of user {} could not be read", username, e);
    }
    return confirmed;
  }

  /**
   * Records that a user confirmed a destination: only ever called once the code sent to
   * it was entered.
   *
   * @param username the user
   * @param destination the destination, normalised
   */
  public void confirm(String username, String destination) {
    Set<String> confirmed = confirmedDestinations(username);
    confirmed.remove(destination);
    confirmed.add(destination);
    List<String> kept = new ArrayList<>(confirmed);
    if (kept.size() > MAX_CONFIRMED) {
      kept = kept.subList(kept.size() - MAX_CONFIRMED, kept.size());
    }
    settingService.set(Context.GLOBAL,
                       FORWARDING_SCOPE,
                       CONFIRMED_KEY_PREFIX + username,
                       SettingValue.create(JsonUtils.toJsonString(kept)));
  }

  /**
   * The destinations a write made as the session's caller may forward to: the ones the
   * caller confirmed whose domain the caller's connector still allows. What the
   * generators check every {@code redirect} against, whatever the script's header holds.
   * The deployment's switch is not part of it: a forward set while it was on keeps its
   * destination until the caller removes it.
   *
   * @param session the caller's own session
   * @return the destinations, normalised; empty when none
   */
  public Set<String> authorizedDestinations(MailboxAclSession session) {
    if (session == null || session.username() == null) {
      return Set.of();
    }
    String mailboxAddress = mailboxAddress(session.username());
    Set<String> authorized = new LinkedHashSet<>();
    for (String destination : confirmedDestinations(session.username())) {
      if (isAllowedDomain(destination, session.connector(), mailboxAddress) && !destination.equals(mailboxAddress)) {
        authorized.add(destination);
      }
    }
    return authorized;
  }

  /**
   * A user's own mailbox address.
   *
   * @param username the user
   * @return the address, lower-case, or null when none is connected
   */
  public String mailboxAddress(String username) {
    UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
    String address = setting == null ? null : StringUtils.trimToNull(setting.getEmailAddress());
    return address == null ? null : address.toLowerCase(Locale.ROOT);
  }
}
