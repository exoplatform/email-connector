/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.emailConnector.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.exoplatform.emailConnector.model.ConnectedMailboxOwners;
import org.exoplatform.emailConnector.model.SenderAddressOwner;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.emailConnector.utils.EmailContactUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.Query;
import org.exoplatform.services.organization.User;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;
import io.meeds.common.ContainerTransactional;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Who on the platform a mail address belongs to, for the picture and the name the
 * mailbox shows beside a sender (EXO-90891): the mail list's rows, asked for in batch
 * ({@link #getAvatars(List, String)}), and the reader, through
 * {@code EmailConnectorUtils#getEmailSender} and {@code #getEmailRecipients} with
 * their profiles.
 * <p>
 * An address belongs to a user when it is their platform account's address, whatever
 * its case, or the address of the mailbox they connected here. The answer, a user or
 * nobody, is kept per address for {@link #OWNER_TTL_MS}, so a list scrolled again, or
 * the reader opened on a mail of it, asks the directory nothing more. Only an enabled,
 * undeleted user is answered: the platform's people directory shows no other.
 * <p>
 * A connected mailbox's address lives in its owner's private settings, so it names
 * them only to a user holding mail from it: the reader's message, or, in a batch, a
 * message of the asking user's mailbox. An account address names its user to anybody,
 * as {@code /contacts/suggest} does.
 */
@Service
public class EmailSenderProfileService {

  /**
   * The most addresses one avatar lookup takes: each address not known yet costs up to
   * three directory queries. The mail list's batch, {@code MAX_AVATAR_BATCH} in the
   * webapp's {@code EmailConnectorSenderAvatars.js}, is the same number.
   */
  public static final int     AVATARS_MAX_ADDRESSES = 50;

  /** Message code answered as a 400 for an avatar lookup past {@link #AVATARS_MAX_ADDRESSES}. */
  public static final String  AVATARS_TOO_MANY      = "emailConnector.contacts.avatars.tooMany";

  /** How long the owner found for an address, or that it has none, is kept, in ms. */
  static final long           OWNER_TTL_MS          = 10L * 60 * 1000;

  /** How many addresses are kept at most; past it the cache starts over. */
  static final int            OWNER_CACHE_MAX       = 10000;

  /** How long the connected mailboxes' owners are kept before they are walked again, in ms. */
  static final long           MAILBOX_OWNERS_TTL_MS = 10L * 60 * 1000;

  /** How often a directory that cannot be read is logged at most, in ms: once, not per address. */
  static final long           FAILURE_LOG_INTERVAL_MS = 60L * 1000;

  private static final Log    LOG                   = ExoLogger.getLogger(EmailSenderProfileService.class);

  @Autowired
  private OrganizationService     organizationService;

  @Autowired
  private IdentityManager         identityManager;

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  @Autowired
  private EmailBoxStorage         emailBoxStorage;

  // The owner found for each normalized address.
  private final Map<String, SenderAddressOwner> owners = new ConcurrentHashMap<>();

  private final Object             mailboxOwnersLock = new Object();

  // The connected mailboxes' owners as last walked, with when: one immutable snapshot.
  private final AtomicReference<ConnectedMailboxOwners> mailboxOwners = new AtomicReference<>();

  private volatile long               lastFailureLoggedAt;

  /**
   * Has the reader's sender resolution go through this service, its matching and its
   * cache.
   */
  @PostConstruct
  public void register() {
    EmailConnectorUtils.setSenderProfileResolver(this::getSenderProfile);
  }

  /**
   * Hands the reader's sender resolution back to the plain account lookup.
   */
  @PreDestroy
  public void unregister() {
    EmailConnectorUtils.setSenderProfileResolver(null);
  }

  /**
   * The profile of the platform user a mail address belongs to, for a message the
   * caller holds.
   *
   * @param address the address, as the mail names it
   * @return the profile, or null when the address belongs to no enabled user
   */
  @ContainerTransactional
  public Profile getSenderProfile(String address) {
    return resolveSenderProfile(address);
  }

  /**
   * {@link #getSenderProfile(String)}'s work, with the container its caller set.
   *
   * @param address the address, as the mail names it
   * @return the profile, or null when the address belongs to no enabled user
   */
  Profile resolveSenderProfile(String address) {
    String key = EmailContactUtils.normalizeAddress(address);
    if (key == null) {
      return null;
    }
    SenderAddressOwner owner = ownerOf(key);
    return owner.username() == null ? null : profileOf(owner.username());
  }

  /**
   * The profile pictures of the platform users behind a set of addresses: the senders
   * of the mail list's rows on screen, in one request.
   * <p>
   * An address no platform user holds is left out: the list draws its coloured
   * initials itself, as the server draws them for the reader. A connected mailbox's
   * address is answered only when the asking user's mailbox holds mail from it.
   *
   * @param addresses the addresses, at most {@link #AVATARS_MAX_ADDRESSES}; blank,
   *          malformed and repeated ones are skipped
   * @param username the asking user
   * @return the picture's URL by normalized address, for the addresses a platform user
   *         holds; empty for none
   * @throws IllegalArgumentException {@link #AVATARS_TOO_MANY} past the cap
   */
  @ContainerTransactional
  public Map<String, String> getAvatars(List<String> addresses, String username) {
    return resolveAvatars(addresses, username);
  }

  /**
   * {@link #getAvatars(List, String)}'s work, with the container its caller set.
   *
   * @param addresses the addresses, at most {@link #AVATARS_MAX_ADDRESSES}
   * @param username the asking user
   * @return the picture's URL by normalized address, for the addresses a platform user holds
   * @throws IllegalArgumentException {@link #AVATARS_TOO_MANY} past the cap
   */
  Map<String, String> resolveAvatars(List<String> addresses, String username) {
    if (addresses == null || addresses.isEmpty()) {
      return Map.of();
    }
    if (addresses.size() > AVATARS_MAX_ADDRESSES) {
      throw new IllegalArgumentException(AVATARS_TOO_MANY);
    }
    Set<String> keys = new LinkedHashSet<>();
    for (String address : addresses) {
      String key = EmailContactUtils.normalizeAddress(address);
      if (key != null && EmailContactUtils.isCompleteAddress(key)) {
        keys.add(key);
      }
    }
    Map<String, String> avatars = new LinkedHashMap<>();
    for (String key : keys) {
      SenderAddressOwner owner = ownerOf(key);
      if (owner.username() == null || owner.mailbox() && !emailBoxStorage.hasMailFrom(username, key)) {
        continue;
      }
      Profile profile = profileOf(owner.username());
      if (profile != null && StringUtils.isNotBlank(profile.getAvatarUrl())) {
        avatars.put(key, profile.getAvatarUrl());
      }
    }
    return avatars;
  }

  /**
   * The owner of an address, from the cache while fresh.
   * <p>
   * Cheapest first: the account address by equality, the connected mailboxes (in
   * memory), and only then the account address whatever its case. Asked again with a
   * trailing wildcard, the directory compares lower-cased values, a scan; the
   * candidates it answers -- any address starting the same way -- are narrowed to the
   * one equal to it but for case. An address alone is compared by equality on the
   * stored value, so whether {@code Bob@Example.org} finds {@code bob@example.org}
   * there is left to the database's collation. A directory that could not be read is
   * not remembered as nobody.
   * <p>
   * So an address that is one user's connected mailbox and, but for case, another's
   * account address on a case-sensitive collation names the mailbox's owner: a
   * collision two users' own settings would have to create.
   *
   * @param key the normalized address
   * @return the owner, its username null for nobody
   */
  private SenderAddressOwner ownerOf(String key) {
    long now = System.currentTimeMillis();
    SenderAddressOwner cached = owners.get(key);
    if (cached != null && now - cached.readAt() < OWNER_TTL_MS) {
      return cached;
    }
    SenderAddressOwner owner;
    try {
      String username = sameAddress(findUsers(key), key);
      String mailboxOwner = username == null ? connectedMailboxOwners().get(key) : null;
      if (username != null || mailboxOwner != null) {
        owner = new SenderAddressOwner(username != null ? username : mailboxOwner, username == null, now);
      } else {
        owner = new SenderAddressOwner(sameAddress(findUsers(key + "*"), key), false, now);
      }
    } catch (Exception e) {
      if (now - lastFailureLoggedAt >= FAILURE_LOG_INTERVAL_MS) {
        lastFailureLoggedAt = now;
        LOG.warn("Cannot look up the platform user of a sender address; shown as nobody this time", e);
      }
      return new SenderAddressOwner(null, false, now);
    }
    if (owners.size() >= OWNER_CACHE_MAX) {
      owners.clear();
    }
    owners.put(key, owner);
    return owner;
  }

  /**
   * The enabled users the directory answers for an email query.
   *
   * @param email the queried email, a trailing * making it a case-insensitive prefix
   * @return up to ten users
   * @throws Exception when the directory cannot be read
   */
  private User[] findUsers(String email) throws Exception { // NOSONAR the directory's own signature
    Query query = new Query();
    query.setEmail(email);
    return organizationService.getUserHandler().findUsersByQuery(query).load(0, 10);
  }

  /**
   * The user whose address is the one looked up, but for case.
   *
   * @param users the candidates
   * @param key the normalized address
   * @return the eXo login, or null when none carries it
   */
  private String sameAddress(User[] users, String key) {
    if (users == null) {
      return null;
    }
    for (User user : users) {
      if (user != null && key.equalsIgnoreCase(StringUtils.trim(user.getEmail()))) {
        return user.getUserName();
      }
    }
    return null;
  }

  /**
   * The connected mailboxes' owners, walked again once older than
   * {@link #MAILBOX_OWNERS_TTL_MS}: by one thread, the others waiting for its walk
   * rather than walking too.
   *
   * @return the eXo login by normalized mailbox address
   */
  private Map<String, String> connectedMailboxOwners() {
    ConnectedMailboxOwners known = mailboxOwners.get();
    if (known != null && known.isFresh(System.currentTimeMillis(), MAILBOX_OWNERS_TTL_MS)) {
      return known.owners();
    }
    synchronized (mailboxOwnersLock) {
      long now = System.currentTimeMillis();
      known = mailboxOwners.get();
      if (known != null && known.isFresh(now, MAILBOX_OWNERS_TTL_MS)) {
        return known.owners();
      }
      Map<String, String> owners;
      try {
        owners = Map.copyOf(userEmailSettingService.getConnectedMailboxOwners());
      } catch (RuntimeException e) {
        LOG.warn("Cannot read the connected mailboxes; senders are matched on their account address only", e);
        owners = Map.of();
      }
      mailboxOwners.set(new ConnectedMailboxOwners(owners, now));
      return owners;
    }
  }

  /**
   * The profile of an enabled, undeleted user.
   *
   * @param username the eXo login
   * @return the profile, or null
   */
  private Profile profileOf(String username) {
    Identity identity = identityManager.getOrCreateUserIdentity(username);
    if (identity == null || identity.isDeleted() || !identity.isEnable()) {
      return null;
    }
    return identity.getProfile();
  }
}
