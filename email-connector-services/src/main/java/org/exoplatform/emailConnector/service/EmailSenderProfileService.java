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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
import org.exoplatform.emailConnector.model.ViewerAddress;
import org.exoplatform.emailConnector.model.ViewerContactPhoto;
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
 * <p>
 * The picture shown for an address is, in this order (EXO-90908): the platform user's
 * own photo; the photo of the viewing user's own contact at that address -- an
 * address book synced over CardDAV, or a photo they set by hand
 * ({@link EmailContactService#getContactPhotoUrls(String, Collection)}); the platform's
 * generated picture of a user with no photo of their own; and nothing, the client then
 * drawing the coloured initials. Contacts are per user, so the contact step is kept per
 * viewer ({@link #CONTACT_PHOTO_TTL_MS}), while the platform step is kept per address
 * for everybody.
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

  /**
   * How long the picture a viewer's own contact carries for an address, or that none
   * does, is kept, in ms: short, since the viewer edits their contacts and the sync
   * rewrites them, and a contact gone answers its photo URL with a 404.
   */
  static final long           CONTACT_PHOTO_TTL_MS  = 60L * 1000;

  /** How many viewer and address pairs are kept at most; past it the cache starts over. */
  static final int            CONTACT_PHOTO_CACHE_MAX = 10000;

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

  @Autowired
  private EmailContactService     emailContactService;

  // The owner found for each normalized address.
  private final Map<String, SenderAddressOwner> owners = new ConcurrentHashMap<>();

  // What each viewer's own contacts answer for each normalized address: the viewer is
  // part of the key, contacts being per user.
  private final Map<ViewerAddress, ViewerContactPhoto> contactPhotos = new ConcurrentHashMap<>();

  private final Object             mailboxOwnersLock = new Object();

  // The connected mailboxes' owners as last walked, with when: one immutable snapshot.
  private final AtomicReference<ConnectedMailboxOwners> mailboxOwners = new AtomicReference<>();

  private volatile long               lastFailureLoggedAt;

  private volatile long               lastContactFailureLoggedAt;

  /**
   * Has the reader's sender resolution go through this service, its matching and its
   * caches: the platform user, then the reading user's own contact.
   */
  @PostConstruct
  public void register() {
    EmailConnectorUtils.setSenderProfileResolver(this::getSenderProfile);
    EmailConnectorUtils.setContactPhotoResolver(this::getContactPhotoUrl);
  }

  /**
   * Hands the reader's sender resolution back to the plain account lookup, with no
   * contact picture.
   */
  @PreDestroy
  public void unregister() {
    EmailConnectorUtils.setSenderProfileResolver(null);
    EmailConnectorUtils.setContactPhotoResolver(null);
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
   * The picture of a viewing user's own contact at an address, for a message they read
   * whose sender has no platform photo (EXO-90908).
   *
   * @param viewer the reading user, whose contacts alone are read
   * @param address the address, as the mail names it
   * @return the URL of the contact's picture, or null when none of the viewer's
   *         contacts there has one
   */
  @ContainerTransactional
  public String getContactPhotoUrl(String viewer, String address) {
    return resolveContactPhotoUrl(viewer, address);
  }

  /**
   * {@link #getContactPhotoUrl(String, String)}'s work, with the container its caller set.
   *
   * @param viewer the reading user, whose contacts alone are read
   * @param address the address, as the mail names it
   * @return the URL of the contact's picture, or null
   */
  String resolveContactPhotoUrl(String viewer, String address) {
    String key = EmailContactUtils.normalizeAddress(address);
    return key == null ? null : contactPhotosOf(viewer, List.of(key)).get(key);
  }

  /**
   * The pictures of the senders of the mail list's rows on screen, in one request: the
   * platform user's own photo, else the asking user's own contact's, else the platform's
   * generated picture of a user with none.
   * <p>
   * An address with none of these is left out: the list draws its coloured initials
   * itself, as the server draws them for the reader. A connected mailbox's address
   * names its owner only when the asking user's mailbox holds mail from it. The
   * contacts read are the asking user's alone, in one query for the addresses the
   * platform answered no photo for.
   *
   * @param addresses the addresses, at most {@link #AVATARS_MAX_ADDRESSES}; blank,
   *          malformed and repeated ones are skipped
   * @param username the asking user
   * @return the picture's URL by normalized address, for the addresses that have one;
   *         empty for none
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
   * @return the picture's URL by normalized address, for the addresses that have one
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
    // The addresses with no platform photo, with the platform's generated picture of
    // their user, null for no user: the next steps are asked for these only.
    Map<String, String> withoutPhoto = new LinkedHashMap<>();
    for (String key : keys) {
      SenderAddressOwner owner = ownerOf(key);
      Profile profile = null;
      if (owner.username() != null && (!owner.mailbox() || emailBoxStorage.hasMailFrom(username, key))) {
        profile = profileOf(owner.username());
      }
      String platformPicture = profile == null ? null : StringUtils.trimToNull(profile.getAvatarUrl());
      if (platformPicture != null && !profile.isDefaultAvatar()) {
        avatars.put(key, platformPicture);
      } else {
        withoutPhoto.put(key, platformPicture);
      }
    }
    if (!withoutPhoto.isEmpty()) {
      Map<String, String> contactPictures = contactPhotosOf(username, withoutPhoto.keySet());
      withoutPhoto.forEach((key, platformPicture) -> {
        String picture = contactPictures.get(key);
        // A sender's brand logo (EXO-90893) belongs here: after the viewer's contact,
        // before the platform's generated picture and the client's initials.
        if (picture == null) {
          picture = platformPicture;
        }
        if (picture != null) {
          avatars.put(key, picture);
        }
      });
    }
    return avatars;
  }

  /**
   * The pictures a viewer's own contacts carry for a set of addresses, from the
   * viewer's cache while fresh, the others read in one query. A store that could not
   * be read is not remembered as holding nothing.
   *
   * @param viewer the viewing user, whose contacts alone are read
   * @param keys the normalized addresses
   * @return the picture's URL by normalized address, for the addresses one of the
   *         viewer's contacts with a picture holds
   */
  private Map<String, String> contactPhotosOf(String viewer, Collection<String> keys) {
    if (StringUtils.isBlank(viewer) || keys.isEmpty()) {
      return Map.of();
    }
    long now = System.currentTimeMillis();
    Map<String, String> pictures = new HashMap<>();
    List<String> unknown = new ArrayList<>();
    for (String key : keys) {
      ViewerContactPhoto cached = contactPhotos.get(new ViewerAddress(viewer, key));
      if (cached != null && cached.isFresh(now, CONTACT_PHOTO_TTL_MS)) {
        if (cached.photoUrl() != null) {
          pictures.put(key, cached.photoUrl());
        }
      } else {
        unknown.add(key);
      }
    }
    if (unknown.isEmpty()) {
      return pictures;
    }
    Map<String, String> read;
    try {
      read = emailContactService.getContactPhotoUrls(viewer, unknown);
    } catch (RuntimeException e) {
      if (now - lastContactFailureLoggedAt >= FAILURE_LOG_INTERVAL_MS) {
        lastContactFailureLoggedAt = now;
        LOG.warn("Cannot read the contacts' pictures of a sender address; shown without them this time", e);
      }
      return pictures;
    }
    if (contactPhotos.size() + unknown.size() > CONTACT_PHOTO_CACHE_MAX) {
      contactPhotos.clear();
    }
    for (String key : unknown) {
      String url = read.get(key);
      contactPhotos.put(new ViewerAddress(viewer, key), new ViewerContactPhoto(url, now));
      if (url != null) {
        pictures.put(key, url);
      }
    }
    return pictures;
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
