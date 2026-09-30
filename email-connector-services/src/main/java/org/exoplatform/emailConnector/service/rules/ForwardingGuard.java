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

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.emailConnector.model.ConnectorForwarding;
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
 * forwarding service (before any engine is called for a forward, and before anything is
 * written) and the Sieve generator (re-checking every redirect it emits) ask, so the two
 * checks cannot drift.
 * <p>
 * <b>The switch</b>, resolved in one place ({@link #resolve}): the deployment-wide key
 * {@value #AUTHORING_PROPERTY} set to {@code false} switches authoring off everywhere at
 * once -- the kill switch; else what the connector administration screen saved for the
 * connector; else, while it was never saved, {@value #AUTHORING_PROPERTY}{@code
 * [.<connectorId>]}, the per-connector key winning, default false. Off, eXo sets no new forward
 * and no new forwarding rule; a forward eXo set before stays on the server, shown with
 * its band, and can be removed.
 * <p>
 * <b>The allowed domains</b>, matched exactly: the administration screen's list, else
 * {@value #ALLOWED_DOMAINS_PROPERTY}{@code [.<connectorId>]}, a comma- or space-separated
 * list; when empty, the domain of the user's own mailbox address -- mail stays in the
 * organisation.
 * <p>
 * <b>The confirmed destinations</b> count for {@value #CONFIRMATION_TTL_PROPERTY} days
 * (90 by default) for a new forward or a new forwarding filter; a forward already on
 * keeps running until it is changed. They are kept per user in the {@code GLOBAL} setting
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

  /** The key prefix of the hash of the Sieve script eXo last wrote for a user. */
  static final String          SCRIPT_KEY_PREFIX        = "script.";

  /** How many days a confirmed destination counts for a new forward or filter. */
  public static final String   CONFIRMATION_TTL_PROPERTY = "email.connector.forwarding.confirmation.ttlDays";

  /** The default of {@value #CONFIRMATION_TTL_PROPERTY}. */
  public static final long     DEFAULT_CONFIRMATION_TTL_DAYS = 90;

  /** The longest a confirmation may count, in days: a hundred years. */
  static final long            MAX_CONFIRMATION_TTL_DAYS = 36_500;

  /** A domain the administration screen refused. */
  public static final String   INVALID_DOMAIN           = "emailConnector.forwarding.domain.invalid";

  /** The key prefix of what the administration screen saved, followed by the connector id. */
  public static final String   CONNECTOR_KEY_PREFIX     = "connector.";

  /** The most allowed domains a connector may list. */
  static final int             MAX_DOMAINS              = 50;

  /** A plain domain: dot-separated labels, at least two. */
  private static final Pattern DOMAIN                   = Pattern.compile(
      "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)++[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

  /** The most confirmed destinations kept per user; the oldest goes first. */
  static final int             MAX_CONFIRMED            = 10;

  @Autowired
  private SettingService          settingService;

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  /** The clock confirmations are dated with; a test moves it. */
  private Clock                   clock = Clock.systemUTC();

  /**
   * Whether eXo may set a forward, or a filter that forwards, on a connector -- the one
   * resolver every check reads, in this order: the deployment-wide key set to
   * {@code false} switches it off everywhere, whatever else says; then what the connector
   * administration screen saved for the connector; then, while the screen was never
   * saved, the per-connector key, else the deployment-wide one, default false. Read at
   * each call: a save applies at once.
   *
   * @param connector the connector, possibly null
   * @return true when users of the connector may author a forward
   */
  public boolean authoringEnabled(EmailConnector connector) {
    ConnectorForwarding resolved = resolve(settingService, connector);
    return resolved.isAuthoringEnabled() && !resolved.isKillSwitch();
  }

  /**
   * The domains a forward may go to on a connector: what the administration screen
   * saved, else the properties; when that is empty, the domain of the user's own mailbox.
   *
   * @param connector the connector
   * @param mailboxAddress the user's own mailbox address, whose domain is the default
   * @return the domains, lower-case, never null; empty when nothing is allowed
   */
  public List<String> allowedDomains(EmailConnector connector, String mailboxAddress) {
    List<String> domains = resolve(settingService, connector).getAllowedDomains();
    if (!domains.isEmpty()) {
      return new ArrayList<>(domains);
    }
    List<String> own = new ArrayList<>();
    if (StringUtils.contains(mailboxAddress, '@')) {
      own.add(ForwardingDestination.domainOf(mailboxAddress.trim().toLowerCase(Locale.ROOT)));
    }
    return own;
  }

  /**
   * Whether a destination's domain is allowed on a connector.
   *
   * @param destination a normalised destination
   * @param connector the connector
   * @param mailboxAddress the user's own mailbox address
   * @return true when its domain is exactly one of the allowed ones
   */
  public boolean isAllowedDomain(String destination, EmailConnector connector, String mailboxAddress) {
    return destination != null && allowedDomains(connector, mailboxAddress).contains(ForwardingDestination.domainOf(destination));
  }

  /**
   * How forwarding is set for a connector: the administration screen's saved value, else
   * the properties. The kill switch is reported beside it and never folded into it, so
   * the screen shows -- and a save keeps -- what the administrator set, and it applies
   * again once the kill switch is removed; {@link #authoringEnabled} applies it. The
   * allowed domains are the configured ones, empty when none (each user's own mailbox
   * domain then applies).
   *
   * @param settingService the settings, where the screen's value is kept
   * @param connector the connector, possibly null
   * @return the settings as set; {@code saved} says where they came from, and
   *         {@code killSwitch} whether the deployment overrides them
   */
  public static ConnectorForwarding resolve(SettingService settingService, EmailConnector connector) {
    String global = StringUtils.trimToNull(System.getProperty(AUTHORING_PROPERTY));
    boolean killSwitch = "false".equalsIgnoreCase(global);
    ConnectorForwarding saved = connector == null || connector.getId() == null ? null : saved(settingService, connector.getId());
    ConnectorForwarding resolved = new ConnectorForwarding();
    resolved.setKillSwitch(killSwitch);
    if (saved != null) {
      resolved.setSaved(true);
      resolved.setAuthoringEnabled(saved.isAuthoringEnabled());
      resolved.setAllowedDomains(saved.getAllowedDomains());
    } else {
      String own = connector == null || connector.getId() == null ? null
                                                                  : StringUtils.trimToNull(System.getProperty(AUTHORING_PROPERTY + "."
                                                                      + connector.getId()));
      resolved.setAuthoringEnabled(Boolean.parseBoolean(own == null ? global : own));
      resolved.setAllowedDomains(propertyDomains(connector));
    }
    if (connector == null || connector.getId() == null) {
      resolved.setAuthoringEnabled(false);
    }
    return resolved;
  }

  /**
   * Saves what the connector administration screen sets for a connector, in the global
   * settings, which only administrators can write. The caller checked the administrator.
   *
   * @param settingService the settings
   * @param connectorId the connector
   * @param authoringEnabled whether users may forward
   * @param allowedDomains the domains, each a plain domain
   * @throws IllegalArgumentException {@value #INVALID_DOMAIN} for anything but a plain
   *           domain
   */
  public static void save(SettingService settingService, long connectorId, boolean authoringEnabled, List<String> allowedDomains) {
    Set<String> domains = new LinkedHashSet<>();
    for (String item : allowedDomains == null ? List.<String> of() : allowedDomains) {
      String domain = StringUtils.removeStart(StringUtils.trimToEmpty(item).toLowerCase(Locale.ROOT), "@");
      if (!DOMAIN.matcher(domain).matches() || domain.length() > 253) {
        throw new IllegalArgumentException(INVALID_DOMAIN);
      }
      domains.add(domain);
    }
    if (domains.size() > MAX_DOMAINS) {
      throw new IllegalArgumentException(INVALID_DOMAIN);
    }
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("enabled", authoringEnabled);
    value.put("allowedDomains", new ArrayList<>(domains));
    settingService.set(Context.GLOBAL, FORWARDING_SCOPE, CONNECTOR_KEY_PREFIX + connectorId, SettingValue.create(JsonUtils.toJsonString(value)));
  }

  /**
   * What the administration screen saved for a connector.
   *
   * @param settingService the settings
   * @param connectorId the connector
   * @return the saved settings, or null when never saved or unreadable
   */
  @SuppressWarnings("unchecked")
  private static ConnectorForwarding saved(SettingService settingService, long connectorId) {
    SettingValue<?> value = settingService.get(Context.GLOBAL, FORWARDING_SCOPE, CONNECTOR_KEY_PREFIX + connectorId);
    if (value == null || value.getValue() == null) {
      return null;
    }
    try {
      Map<String, Object> stored = JsonUtils.fromJsonString(value.getValue().toString(), Map.class);
      if (stored == null || !(stored.get("enabled") instanceof Boolean enabled)) {
        return null;
      }
      List<String> domains = new ArrayList<>();
      if (stored.get("allowedDomains") instanceof List<?> list) {
        list.forEach(item -> domains.add(String.valueOf(item).toLowerCase(Locale.ROOT)));
      }
      return new ConnectorForwarding(enabled, domains, true, false);
    } catch (RuntimeException e) {
      LOG.warn("The forwarding settings saved for connector {} could not be read: the properties apply", connectorId, e);
      return null;
    }
  }

  /**
   * The allowed domains the properties configure for a connector.
   *
   * @param connector the connector, possibly null
   * @return the domains, lower-case; empty when none is configured
   */
  private static List<String> propertyDomains(EmailConnector connector) {
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
    }
    return new ArrayList<>(domains);
  }


  /**
   * The destinations a user confirmed, by entering the code sent to each, less than
   * {@value #CONFIRMATION_TTL_PROPERTY} days ago: an older confirmation no longer counts
   * for a new forward or a new forwarding filter. A forward already on keeps running
   * until it is changed; nothing here removes it.
   *
   * @param username the user
   * @return the destinations, normalised, never null
   */
  public Set<String> confirmedDestinations(String username) {
    long oldest = clock.millis() - confirmationTtlDays() * 86_400_000L;
    Set<String> confirmed = new LinkedHashSet<>();
    storedConfirmations(username).forEach((destination, date) -> {
      if (date >= oldest) {
        confirmed.add(destination);
      }
    });
    return confirmed;
  }

  /**
   * Records that a user confirmed a destination, now: only ever called once the code sent
   * to it was entered. Expired confirmations are dropped, and the most recent ones kept.
   *
   * @param username the user
   * @param destination the destination, normalised
   */
  public void confirm(String username, String destination) {
    long oldest = clock.millis() - confirmationTtlDays() * 86_400_000L;
    Map<String, Long> confirmations = new LinkedHashMap<>();
    storedConfirmations(username).forEach((address, date) -> {
      if (date >= oldest && !address.equals(destination)) {
        confirmations.put(address, date);
      }
    });
    confirmations.put(destination, clock.millis());
    List<Map<String, Object>> kept = new ArrayList<>();
    confirmations.forEach((address, date) -> kept.add(Map.of("address", address, "date", date)));
    List<Map<String, Object>> latest = kept.size() > MAX_CONFIRMED ? kept.subList(kept.size() - MAX_CONFIRMED, kept.size()) : kept;
    settingService.set(Context.GLOBAL,
                       FORWARDING_SCOPE,
                       CONFIRMED_KEY_PREFIX + username,
                       SettingValue.create(JsonUtils.toJsonString(latest)));
  }

  /**
   * A user's stored confirmations, oldest first: each address and when it was confirmed.
   * An entry that is not an address with a date -- an older format included -- counts as
   * never confirmed.
   *
   * @param username the user
   * @return the confirmations, by address
   */
  @SuppressWarnings("unchecked")
  private Map<String, Long> storedConfirmations(String username) {
    Map<String, Long> confirmations = new LinkedHashMap<>();
    SettingValue<?> value = settingService.get(Context.GLOBAL, FORWARDING_SCOPE, CONFIRMED_KEY_PREFIX + username);
    if (value == null || value.getValue() == null) {
      return confirmations;
    }
    try {
      List<Object> stored = JsonUtils.fromJsonString(value.getValue().toString(), List.class);
      for (Object item : stored == null ? List.of() : stored) {
        if (item instanceof Map<?, ?> entry && entry.get("date") instanceof Number date) {
          try {
            confirmations.put(ForwardingDestination.normalize(String.valueOf(entry.get("address"))), date.longValue());
          } catch (IllegalArgumentException e) {
            LOG.debug("A stored forwarding destination of user {} is not an address and is ignored", username);
          }
        }
      }
    } catch (RuntimeException e) {
      LOG.debug("The confirmed forwarding destinations of user {} could not be read", username, e);
    }
    return confirmations;
  }

  /**
   * How many days a confirmation counts, from {@value #CONFIRMATION_TTL_PROPERTY}.
   *
   * @return days, from one to {@value #MAX_CONFIRMATION_TTL_DAYS}
   */
  static long confirmationTtlDays() {
    try {
      return Math.min(MAX_CONFIRMATION_TTL_DAYS,
                      Math.max(1,
                               Long.parseLong(System.getProperty(CONFIRMATION_TTL_PROPERTY,
                                                                 String.valueOf(DEFAULT_CONFIRMATION_TTL_DAYS))
                                                    .trim())));
    } catch (NumberFormatException e) {
      return DEFAULT_CONFIRMATION_TTL_DAYS;
    }
  }

  /**
   * Replaces the clock; for tests.
   *
   * @param newClock the clock
   */
  public void setClock(Clock newClock) {
    this.clock = newClock;
  }

  /**
   * Records the hash of the Sieve script eXo just wrote for a user, where the user cannot
   * write it: what tells a script eXo wrote from one edited outside eXo when it comes to
   * forwarding -- the hash the reply and the rules compare with sits in the user's own
   * settings, which the user can rewrite.
   *
   * @param username the user
   * @param hash the SHA-256 of the text written; null writes nothing
   */
  public void recordScriptHash(String username, String hash) {
    if (hash != null) {
      settingService.set(Context.GLOBAL, FORWARDING_SCOPE, SCRIPT_KEY_PREFIX + username, SettingValue.create(hash));
    }
  }

  /**
   * The hash of the Sieve script eXo last wrote for a user.
   *
   * @param username the user
   * @return the hash, or null when eXo recorded none
   */
  public String lastWrittenScriptHash(String username) {
    SettingValue<?> value = settingService.get(Context.GLOBAL, FORWARDING_SCOPE, SCRIPT_KEY_PREFIX + username);
    return value == null || value.getValue() == null ? null : value.getValue().toString();
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
