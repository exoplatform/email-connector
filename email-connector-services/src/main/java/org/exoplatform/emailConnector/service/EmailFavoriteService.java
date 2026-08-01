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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.metadata.favorite.FavoriteService;
import org.exoplatform.social.metadata.favorite.model.Favorite;
import org.exoplatform.social.metadata.model.MetadataItem;

/**
 * Keeps the platform's favorites in step with the mail server's own
 * {@code \Flagged} flag, so that a mail favorited in the mailbox appears in the
 * global Favorites drawer next to the user's favorite notes, tasks and
 * documents.
 * <p>
 * The flag on the server is the single source of truth, and the favorite is
 * only a mirror of it. That direction matters: the flag is what the user sees
 * in Gmail and on their phone, it is what another mail client can change behind
 * our back, and it is what survives this add-on's local cache being wiped. The
 * favorites are therefore never edited in isolation — they are recomputed from
 * the flagged mails by {@link #reconcileFavorites(String)}, which is idempotent
 * and is the only writer.
 */
@Service
public class EmailFavoriteService {

  private static final Log      LOG                 = ExoLogger.getLogger(EmailFavoriteService.class);

  /** The favorites object type of an email, shared with the ACL plugins and the front-end. */
  public static final String    OBJECT_TYPE         = EmailConnectorUtils.EMAIL_FEATURE;

  /**
   * A ceiling on the favorites read back per reconcile. Favorites are a
   * deliberate, hand-picked set; a user with more flagged mails than this has
   * stopped using the flag as a shortlist, and reading the whole tail every
   * sync would cost more than it tells us.
   */
  private static final int      MAX_FAVORITES       = 500;

  @Autowired
  private EmailBoxStorage       emailBoxStorage;

  @Autowired
  private FavoriteService       favoriteService;

  @Autowired
  private IdentityManager       identityManager;

  /**
   * Aligns the user's email favorites with the mails currently flagged on the
   * server, creating the ones that are missing and removing the ones that no
   * longer are.
   * <p>
   * Reconciling wholesale rather than tracking each toggle is what makes this
   * correct in the three cases that actually happen: a star pushed from another
   * mail client (the flag arrives with the sync, and no toggle ever ran here), a
   * star this add-on tried to push and the server refused (the local row was
   * reverted, so the favorite must go back too), and a mailbox reset (every
   * cached row is deleted and recreated under a new id, orphaning every favorite
   * that pointed at the old ones).
   *
   * @param username the user whose favorites are realigned, on their own mailbox
   */
  public void reconcileFavorites(String username) {
    if (StringUtils.isBlank(username)) {
      return;
    }
    long userIdentityId = getUserIdentityId(username);
    if (userIdentityId <= 0) {
      return;
    }
    try {
      Set<String> flagged = getFlaggedEmailIds(username);
      Set<String> favorited = getFavoritedEmailIds(userIdentityId);
      flagged.stream().filter(emailId -> !favorited.contains(emailId)).forEach(emailId -> createFavorite(emailId, userIdentityId));
      favorited.stream().filter(emailId -> !flagged.contains(emailId)).forEach(emailId -> deleteFavorite(emailId, userIdentityId));
    } catch (Exception e) {
      // A favorite that could not be realigned is a stale drawer entry, never a
      // reason to fail the star toggle or the synchronization that called this.
      LOG.warn("Error when reconciling email favorites for user {}", username, e);
    }
  }

  /**
   * Whether the given email is currently one of the user's favorites, so a
   * caller can tell without re-reading the whole drawer.
   *
   * @param emailId the cached email's technical id
   * @param username the user the favorite would belong to
   * @return {@code true} when a favorite exists for that email and user
   */
  public boolean isFavorite(long emailId, String username) {
    long userIdentityId = getUserIdentityId(username);
    return userIdentityId > 0 && favoriteService.isFavorite(new Favorite(OBJECT_TYPE, String.valueOf(emailId), null, userIdentityId));
  }

  /**
   * The ids of the mails this user has flagged, as favorites object ids.
   * <p>
   * Only the INBOX is read, because that is the only folder whose flag this
   * add-on pushes: a Sent or Archive copy shows its flag read-only, and
   * favoriting it would offer the user a star they cannot take back.
   *
   * @param username the mailbox owner
   * @return the flagged mails' ids, as strings
   */
  private Set<String> getFlaggedEmailIds(String username) {
    List<Email> starredEmails = emailBoxStorage.getStarredEmails(username, MailFolder.INBOX);
    return starredEmails == null ? new HashSet<>()
                                 : starredEmails.stream()
                                                .map(email -> String.valueOf(email.getId()))
                                                .collect(Collectors.toCollection(HashSet::new));
  }

  /**
   * The email ids the user has already favorited, read back from the platform's
   * generic favorites store.
   *
   * @param userIdentityId the social identity id of the mailbox owner
   * @return the favorited mails' ids, as strings
   */
  private Set<String> getFavoritedEmailIds(long userIdentityId) {
    List<MetadataItem> favoriteItems = favoriteService.getFavoriteItemsByCreatorAndType(OBJECT_TYPE, userIdentityId, 0, MAX_FAVORITES);
    return favoriteItems == null ? new HashSet<>()
                                 : favoriteItems.stream()
                                                .map(MetadataItem::getObjectId)
                                                .filter(StringUtils::isNotBlank)
                                                .collect(Collectors.toCollection(HashSet::new));
  }

  /**
   * Adds one mail to the user's favorites, tolerating the one already there.
   *
   * @param emailId the cached email's technical id
   * @param userIdentityId the social identity id of the mailbox owner
   */
  private void createFavorite(String emailId, long userIdentityId) {
    try {
      favoriteService.createFavorite(new Favorite(OBJECT_TYPE, emailId, null, userIdentityId));
    } catch (Exception e) {
      // Already a favorite: two syncs overlapping is normal, not an incident.
      LOG.debug("Email {} could not be added to the favorites of identity {}", emailId, userIdentityId, e);
    }
  }

  /**
   * Removes one mail from the user's favorites, tolerating the one already gone.
   *
   * @param emailId the cached email's technical id
   * @param userIdentityId the social identity id of the mailbox owner
   */
  private void deleteFavorite(String emailId, long userIdentityId) {
    try {
      favoriteService.deleteFavorite(new Favorite(OBJECT_TYPE, emailId, null, userIdentityId));
    } catch (Exception e) {
      LOG.debug("Email {} could not be removed from the favorites of identity {}", emailId, userIdentityId, e);
    }
  }

  /**
   * The social identity id behind a username, which is what favorites are keyed
   * by.
   *
   * @param username the mailbox owner
   * @return the identity id, or {@code 0} when the user has no identity yet
   */
  private long getUserIdentityId(String username) {
    if (StringUtils.isBlank(username)) {
      return 0;
    }
    try {
      Identity identity = identityManager.getOrCreateUserIdentity(username);
      return identity == null || StringUtils.isBlank(identity.getId()) ? 0 : Long.parseLong(identity.getId());
    } catch (Exception e) {
      LOG.warn("Error when resolving the identity of user {}", username, e);
      return 0;
    }
  }
}
