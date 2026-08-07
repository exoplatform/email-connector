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
package org.exoplatform.emailConnector.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.plugin.EmailContactAclPlugin;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.metadata.favorite.FavoriteService;
import org.exoplatform.social.metadata.favorite.model.Favorite;
import org.exoplatform.social.metadata.model.MetadataItem;

/**
 * The contact side of the platform's favorites: which of the user's contacts
 * are starred, so they surface in the global Favorites drawer and in the
 * Contacts drawer's own filter.
 * <p>
 * Unlike {@link EmailFavoriteService}, there is nothing to reconcile against:
 * a contact carries no external flag the way a mail carries the server's
 * {@code \Flagged}, so the platform's favorites store IS the single source of
 * truth. Favorites are written by the shared favorite button (through the
 * social REST), and this service only reads them back — plus the one write it
 * owns, the cleanup of a favorite whose contact is gone.
 */
@Service
public class EmailContactFavoriteService {

  private static final Log   LOG           = ExoLogger.getLogger(EmailContactFavoriteService.class);

  /** The favorites object type of a contact, shared with the ACL plugins and the front-end. */
  public static final String OBJECT_TYPE   = EmailContactAclPlugin.OBJECT_TYPE;

  /**
   * A ceiling on the favorites read back per call. Favorites are a deliberate,
   * hand-picked set; a user with more starred contacts than this has stopped
   * using the star as a shortlist, and reading the whole tail on every page
   * would cost more than it tells us.
   */
  private static final int   MAX_FAVORITES = 500;

  @Autowired
  private FavoriteService    favoriteService;

  @Autowired
  private IdentityManager    identityManager;

  /**
   * The ids of the contacts this user has favorited, in one read of the
   * platform's favorites store — the shape both enrichments need: marking a
   * page of contacts, and restricting the list to the starred ones.
   *
   * @param username the store owner
   * @return the favorited contacts' row ids, never null
   */
  public Set<Long> getFavoriteContactIds(String username) {
    long userIdentityId = getUserIdentityId(username);
    if (userIdentityId <= 0) {
      return new HashSet<>();
    }
    try {
      List<MetadataItem> favoriteItems = favoriteService.getFavoriteItemsByCreatorAndType(OBJECT_TYPE,
                                                                                          userIdentityId,
                                                                                          0,
                                                                                          MAX_FAVORITES);
      Set<Long> contactIds = new HashSet<>();
      if (favoriteItems != null) {
        for (MetadataItem item : favoriteItems) {
          addObjectId(contactIds, item);
        }
      }
      return contactIds;
    } catch (Exception e) {
      // An unreadable favorites store costs the stars, never the contact list
      // that asked for them.
      LOG.warn("Error when reading the contact favorites of user {}", username, e);
      return new HashSet<>();
    }
  }

  /**
   * Whether the given contact is currently one of the user's favorites, so a
   * single-row read can be marked without paying for the whole set.
   *
   * @param contactId the contact's row id
   * @param username the store owner
   * @return {@code true} when a favorite exists for that contact and user
   */
  public boolean isFavorite(long contactId, String username) {
    long userIdentityId = getUserIdentityId(username);
    return userIdentityId > 0 && favoriteService.isFavorite(new Favorite(OBJECT_TYPE, String.valueOf(contactId), null, userIdentityId));
  }

  /**
   * Removes the favorite pointing at a contact that is being deleted or
   * suppressed — the orphan cleanup, called from every site where a contact
   * stops being visible. Tolerant by design: a favorite that could not be
   * removed is a stale drawer entry the drawer itself knows how to drop, never
   * a reason to fail the deletion that called this.
   *
   * @param contactId the dying contact's row id
   * @param username the store owner
   */
  public void removeFavorite(long contactId, String username) {
    try {
      long userIdentityId = getUserIdentityId(username);
      if (userIdentityId <= 0) {
        return;
      }
      favoriteService.deleteFavorite(new Favorite(OBJECT_TYPE, String.valueOf(contactId), null, userIdentityId));
    } catch (Exception e) {
      // Not a favorite, or the store is unreachable: either way the delete goes on.
      LOG.debug("Contact {} could not be removed from the favorites of user {}", contactId, username, e);
    }
  }

  /**
   * One metadata item's object id parsed into the id set, garbage skipped: the
   * favorites store is written through a REST anybody can call, so an object id
   * that is not a contact row id is noise, not an error.
   *
   * @param contactIds the set being built, mutated in place
   * @param item the stored favorite
   */
  private void addObjectId(Set<Long> contactIds, MetadataItem item) {
    if (item == null || StringUtils.isBlank(item.getObjectId())) {
      return;
    }
    try {
      contactIds.add(Long.parseLong(item.getObjectId()));
    } catch (NumberFormatException e) {
      LOG.debug("A contact favorite carries the non-numeric object id {}", item.getObjectId());
    }
  }

  /**
   * The social identity id behind a username, which is what favorites are keyed
   * by.
   *
   * @param username the store owner
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
