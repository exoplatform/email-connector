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
package org.exoplatform.emailConnector.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailSecurityWarning;
import org.exoplatform.emailConnector.model.EmailSecurityWarningType;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.RemoteContentSettings;
import org.exoplatform.emailConnector.model.SanitizedEmailBody;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.emailConnector.utils.EmailContactUtils;
import org.exoplatform.emailConnector.utils.EmailHtmlSanitizer;
import org.exoplatform.emailConnector.utils.EmailSecurityUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.profile.ProfileFilter;

import io.meeds.social.util.JsonUtils;

/**
 * Remote content and phishing warnings for the mail reader (EXO-90841).
 * <p>
 * Every received message served to the reader goes through {@link #decorate}: its HTML
 * body is cleaned ({@link EmailHtmlSanitizer}), with the resources it would fetch from
 * the internet held back unless the user agreed, and the reasons it looks suspicious
 * are listed for the banner. The user's choices (blocking on or off, the senders always
 * trusted) are a per-user setting, like the read-receipt ones.
 */
@Service
public class EmailSecurityService {

  /** The per-user setting holding the {@link RemoteContentSettings}. */
  public static final String  SETTINGS_KEY                  = "emailRemoteContentSettings";

  /**
   * The organisation's mail domains, comma-separated, beyond the reader's own mailbox
   * domain: a sender writing from one of them is never taken for an impersonator.
   */
  public static final String  ORGANISATION_DOMAINS_PROPERTY = "email.connector.security.organisationDomains";

  /** The answer to a trusted-sender request without a valid address. */
  public static final String  INVALID_SENDER                = "emailConnector.remoteContent.invalidSender";

  /** How many senders a user may trust, so the setting stays a small value. */
  static final int            MAX_TRUSTED_SENDERS           = 500;

  private static final Log    LOG                           = ExoLogger.getLogger(EmailSecurityService.class);

  /** How many directory matches a sender's name is compared against. */
  private static final int    NAME_MATCH_LIMIT              = 10;

  @Autowired
  private SettingService      settingService;

  @Autowired
  private IdentityManager     identityManager;

  /**
   * The user's remote-content choices; blocking on and no trusted sender when they
   * never chose. Never null.
   *
   * @param username the user
   * @return the choices
   */
  public RemoteContentSettings getSettings(String username) {
    RemoteContentSettings stored = null;
    SettingValue<?> value = settingService.get(Context.USER.id(username), UserEmailSettingService.EMAIL_CONNECTOR_SCOPE, SETTINGS_KEY);
    if (value != null && value.getValue() != null) {
      try {
        stored = JsonUtils.fromJsonString(value.getValue().toString(), RemoteContentSettings.class);
      } catch (Exception e) {
        // Unreadable choices fall back on the defaults, which block.
        LOG.warn("The remote-content settings of user {} could not be read, using the defaults", username, e);
      }
    }
    if (stored == null) {
      return new RemoteContentSettings(true, List.of());
    }
    return new RemoteContentSettings(stored.isBlockRemoteContent(),
                                     stored.getTrustedSenders() == null ? List.of() : List.copyOf(stored.getTrustedSenders()));
  }

  /**
   * Stores whether the user's mail holds remote content back. The trusted senders are
   * kept as they are.
   *
   * @param username the user
   * @param blockRemoteContent whether remote content waits for consent
   * @return the choices as they now stand
   */
  public RemoteContentSettings setBlockRemoteContent(String username, boolean blockRemoteContent) {
    RemoteContentSettings settings = getSettings(username);
    return store(username, new RemoteContentSettings(blockRemoteContent, settings.getTrustedSenders()));
  }

  /**
   * Trusts a sender: their messages load remote content without asking. Trusting one
   * already trusted changes nothing. Past {@link #MAX_TRUSTED_SENDERS}, the oldest is
   * forgotten.
   *
   * @param username the user
   * @param address the sender's address
   * @return the choices as they now stand
   * @throws IllegalArgumentException {@link #INVALID_SENDER} when the address is not one
   */
  public RemoteContentSettings trustSender(String username, String address) {
    String normalised = normaliseAddress(address);
    RemoteContentSettings settings = getSettings(username);
    Set<String> senders = new LinkedHashSet<>(settings.getTrustedSenders());
    senders.remove(normalised);
    senders.add(normalised);
    List<String> kept = new ArrayList<>(senders);
    if (kept.size() > MAX_TRUSTED_SENDERS) {
      kept = kept.subList(kept.size() - MAX_TRUSTED_SENDERS, kept.size());
    }
    return store(username, new RemoteContentSettings(settings.isBlockRemoteContent(), kept));
  }

  /**
   * Stops trusting a sender. Forgetting one not trusted changes nothing.
   *
   * @param username the user
   * @param address the sender's address
   * @return the choices as they now stand
   * @throws IllegalArgumentException {@link #INVALID_SENDER} when the address is not one
   */
  public RemoteContentSettings forgetSender(String username, String address) {
    String normalised = normaliseAddress(address);
    RemoteContentSettings settings = getSettings(username);
    List<String> kept = settings.getTrustedSenders().stream().filter(sender -> !sender.equals(normalised)).toList();
    return store(username, new RemoteContentSettings(settings.isBlockRemoteContent(), kept));
  }

  /**
   * A short value that changes whenever the user's remote-content choices change, for
   * the reader's cache validators: a copy cached with images blocked must not be
   * confirmed as current once the user trusted its sender or switched blocking off.
   *
   * @param username the user
   * @return the fingerprint of the choices
   */
  public int settingsFingerprint(String username) {
    RemoteContentSettings settings = getSettings(username);
    return Objects.hash(settings.isBlockRemoteContent(), settings.getTrustedSenders());
  }

  /**
   * Prepares received messages for the reader. For each message that is not a draft:
   * <ul>
   * <li>the phishing warnings are listed; for the mailbox's own sent mail (a row of its
   * SENT folder), only an authentication failure;</li>
   * <li>an HTML body is cleaned, with remote content kept only when
   * {@code showRemoteContent} is set or the user switched blocking off, or, for a
   * message with no warning, when its sender is trusted or it is the mailbox's own sent
   * mail; the content then says whether anything was held back.</li>
   * </ul>
   * A draft is the user's own text, read back by the composer, and is left alone.
   *
   * @param emails the messages, changed in place
   * @param username the user reading them
   * @param showRemoteContent whether the user just asked to load remote content
   */
  public void decorate(Collection<Email> emails, String username, boolean showRemoteContent) {
    if (emails == null || emails.isEmpty()) {
      return;
    }
    RemoteContentSettings settings = getSettings(username);
    Map<String, Optional<Profile>> namesSeen = new HashMap<>();
    for (Email email : emails) {
      if (email == null || email.getContent() == null || StringUtils.isNotBlank(email.getDraftLocalId())) {
        continue;
      }
      EmailContent content = email.getContent();
      String senderAddress = senderAddress(email);
      // The mailbox's own sent mail, decided by where the row lives, never by its From,
      // which whoever wrote the message chose.
      boolean ownMessage = MailFolder.SENT.equals(email.getFolder());
      boolean html = content.isHtml() && content.getBody() != null;
      // Cleaned with remote content held back first: its links are what the warnings
      // judge, and the warnings decide whether an exemption may apply.
      SanitizedEmailBody sanitized = html ? EmailHtmlSanitizer.sanitize(content.getBody(), false) : null;
      List<EmailSecurityWarning> warnings = warnings(email, senderAddress, sanitized, ownMessage, namesSeen);
      if (html && sanitized.remoteContentBlocked()) {
        // The user's own choices hold whatever the message looks like; trusting a sender
        // or one's own sent mail holds only for a message giving no reason for doubt, so
        // a forged From does not borrow that trust.
        boolean exempt = warnings.isEmpty()
            && (ownMessage || senderAddress != null && settings.getTrustedSenders().contains(senderAddress));
        if (showRemoteContent || !settings.isBlockRemoteContent() || exempt) {
          sanitized = EmailHtmlSanitizer.sanitize(content.getBody(), true);
        }
      }
      if (html) {
        content.setBody(sanitized.html());
      }
      content.setRemoteContentBlocked(html && sanitized.remoteContentBlocked());
      content.setSecurityWarnings(warnings);
    }
  }

  /**
   * Prepares one received message for the reader, as {@link #decorate(Collection, String, boolean)}.
   *
   * @param email the message, changed in place
   * @param username the user reading it
   * @param showRemoteContent whether the user just asked to load remote content
   */
  public void decorate(Email email, String username, boolean showRemoteContent) {
    if (email != null) {
      decorate(List.of(email), username, showRemoteContent);
    }
  }

  /**
   * Why a message looks suspicious. Three rules, each chosen to stay quiet on
   * legitimate mail, newsletters first:
   * <ul>
   * <li>{@code AUTHENTICATION_FAILED}: the receiving server said the sender's domain
   * failed DMARC, or SPF / DKIM with nothing else passing
   * ({@link EmailSecurityUtils#authenticationFailure}).</li>
   * <li>{@code IMPERSONATION}: the sender's display name is, in full and with at least
   * two words, the name of a platform user, while the address is none of the platform's
   * users' and its domain is not the organisation's ({@link #impersonation}).</li>
   * <li>{@code DECEPTIVE_LINK}: a link's text is a web address on another domain than
   * its target. Not judged on bulk or automated mail (List-Unsubscribe, List-Id without
   * List-Post, Auto-Submitted) nor on forwards, where click-tracking redirects make
   * every such link "deceptive" -- unless the message failed its sender
   * authentication, since those headers and the subject are the sender's to write.</li>
   * </ul>
   *
   * @param email the message
   * @param senderAddress its sender's address, normalised, or null
   * @param sanitized its cleaned HTML body, or null for a plain-text one
   * @param ownMessage whether it is the mailbox's own sent mail, judged only on its
   *          authentication then
   * @param namesSeen the directory answers already obtained for this request, by name
   * @return the warnings, possibly empty
   */
  private List<EmailSecurityWarning> warnings(Email email,
                                              String senderAddress,
                                              SanitizedEmailBody sanitized,
                                              boolean ownMessage,
                                              Map<String, Optional<Profile>> namesSeen) {
    List<EmailSecurityWarning> warnings = new ArrayList<>();
    String authFailure = email.getContent().getAuthFailure();
    if (StringUtils.isNotBlank(authFailure)) {
      warnings.add(new EmailSecurityWarning(EmailSecurityWarningType.AUTHENTICATION_FAILED, authFailure, null));
    }
    if (ownMessage) {
      return warnings;
    }
    EmailSecurityWarning impersonation = impersonation(email, senderAddress, namesSeen);
    if (impersonation != null) {
      warnings.add(impersonation);
    }
    String mailType = EmailConnectorUtils.getMailType(email);
    boolean massMail = EmailConnectorUtils.MAIL_TYPE_BULK.equals(mailType) || EmailConnectorUtils.MAIL_TYPE_AUTOMATED.equals(mailType);
    // The exemption rests on headers the sender writes, so it holds only for a message
    // the receiving server did not find failing its sender authentication.
    boolean exempt = (massMail || EmailConnectorUtils.isForward(email)) && StringUtils.isBlank(authFailure);
    if (sanitized != null && !exempt) {
      EmailSecurityWarning link = EmailSecurityUtils.deceptiveLink(sanitized.links());
      if (link != null) {
        warnings.add(link);
      }
    }
    return warnings;
  }

  /**
   * Whether a message's sender borrows a platform user's name. It does when its display
   * name, normalised ({@link EmailSecurityUtils#normaliseName}) and of at least two
   * words, is the full name of a platform user found in the directory, and:
   * <ul>
   * <li>the sender's address is not a platform user's (the reader shows those with
   * their profile: {@code sender.profileUrl});</li>
   * <li>nor the matched user's own address;</li>
   * <li>and its domain is none of the organisation's: the reader's mailbox domain, the
   * matched user's, and {@link #ORGANISATION_DOMAINS_PROPERTY}; a free-mail domain is
   * never counted as the organisation's, so "Jane Doe" at gmail.com warns even when
   * Jane's platform address is at gmail.com too.</li>
   * </ul>
   *
   * @param email the message
   * @param senderAddress its sender's address, normalised, or null
   * @param namesSeen the directory answers already obtained for this request, by name
   * @return the warning, or null
   */
  EmailSecurityWarning impersonation(Email email, String senderAddress, Map<String, Optional<Profile>> namesSeen) {
    if (senderAddress == null || email.getSender() == null || StringUtils.isNotBlank(email.getSender().getProfileUrl())) {
      return null;
    }
    String name = EmailSecurityUtils.normaliseName(email.getSender().getName());
    if (name.indexOf(' ') < 0 || name.contains("@")) {
      return null;
    }
    String senderDomain = EmailContactUtils.domainOf(senderAddress);
    // Decided before the directory is asked: a sender on the organisation's own domains
    // is never an outsider, whoever they are named after.
    if (organisationDomains(email, null).contains(senderDomain)) {
      return null;
    }
    Profile user = namesSeen.computeIfAbsent(name, key -> findUserNamed(email.getSender().getName(), key)).orElse(null);
    if (user == null) {
      return null;
    }
    String userAddress = normaliseOrNull(user.getEmail());
    if (senderAddress.equals(userAddress) || organisationDomains(email, userAddress).contains(senderDomain)) {
      return null;
    }
    return new EmailSecurityWarning(EmailSecurityWarningType.IMPERSONATION, user.getFullName(), senderAddress);
  }

  /**
   * The platform user whose full name is this one, looked up in the directory by name.
   * A failing directory answers nobody: a missing warning is better than a reader that
   * fails to open a message.
   *
   * @param displayName the sender's display name as written
   * @param normalisedName the same, normalised
   * @return the user's profile, or empty when none has that name
   */
  private Optional<Profile> findUserNamed(String displayName, String normalisedName) {
    try {
      ProfileFilter filter = new ProfileFilter();
      filter.setName(displayName.trim());
      List<Identity> identities = identityManager.getIdentitiesByProfileFilter(OrganizationIdentityProvider.NAME,
                                                                               filter,
                                                                               0,
                                                                               NAME_MATCH_LIMIT);
      if (identities == null) {
        return Optional.empty();
      }
      return identities.stream()
                       .filter(identity -> identity != null && !identity.isDeleted() && identity.getProfile() != null)
                       .map(Identity::getProfile)
                       .filter(profile -> normalisedName.equals(EmailSecurityUtils.normaliseName(profile.getFullName())))
                       .findFirst();
    } catch (Exception e) {
      LOG.debug("Could not look up the platform users named {}", displayName, e);
      return Optional.empty();
    }
  }

  /**
   * The domains a sender may write from without being taken for an impersonator: the
   * configured ones, the reader's mailbox domain and the matched user's own, free-mail
   * domains excepted.
   *
   * @param email the message, for the reader's mailbox address
   * @param userAddress the matched user's address, normalised, or null
   * @return the domains, lower-cased
   */
  private Set<String> organisationDomains(Email email, String userAddress) {
    Set<String> domains = new LinkedHashSet<>();
    for (String domain : StringUtils.split(System.getProperty(ORGANISATION_DOMAINS_PROPERTY, ""), ", ")) {
      domains.add(StringUtils.removeStart(domain.trim().toLowerCase(Locale.ROOT), "@"));
    }
    for (String address : new String[] { normaliseOrNull(email.getUserEmail()), userAddress }) {
      String domain = EmailContactUtils.domainOf(address);
      if (domain != null && !EmailContactUtils.isFreemailDomain(domain)) {
        domains.add(domain);
      }
    }
    return domains;
  }

  /**
   * Stores the choices.
   *
   * @param username the user
   * @param settings the choices
   * @return the choices as they now stand
   */
  private RemoteContentSettings store(String username, RemoteContentSettings settings) {
    settingService.set(Context.USER.id(username),
                       UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                       SETTINGS_KEY,
                       SettingValue.create(JsonUtils.toJsonString(settings)));
    return getSettings(username);
  }

  /**
   * The sender's address of a message, normalised.
   *
   * @param email the message
   * @return the address, or null when it has none
   */
  private String senderAddress(Email email) {
    return email.getSender() == null ? null : normaliseOrNull(email.getSender().getAddress());
  }

  /**
   * A mail address in its comparable form: trimmed and lower-cased.
   *
   * @param address an address
   * @return the normalised address, or null when blank
   */
  private static String normaliseOrNull(String address) {
    return StringUtils.isBlank(address) ? null : address.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * A sender address given by the user, checked and normalised.
   *
   * @param address the address
   * @return the normalised address
   * @throws IllegalArgumentException {@link #INVALID_SENDER} when it is not an address
   */
  private static String normaliseAddress(String address) {
    String normalised = normaliseOrNull(address);
    if (normalised == null || normalised.length() > 320 || normalised.indexOf('@') <= 0 || normalised.endsWith("@")
        || normalised.chars().anyMatch(c -> c <= 0x20 || c == '<' || c == '>' || c == '"' || c == ',')) {
      throw new IllegalArgumentException(INVALID_SENDER);
    }
    return normalised;
  }
}
