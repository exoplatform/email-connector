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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.metadata.favorite.FavoriteService;
import org.exoplatform.social.metadata.favorite.model.Favorite;
import org.exoplatform.social.metadata.model.MetadataItem;

@ExtendWith(MockitoExtension.class)
@SpringBootTest(classes = { EmailFavoriteService.class })
public class EmailFavoriteServiceTest {

  private static final String  USERNAME    = "john";

  private static final long    IDENTITY_ID = 42L;

  @MockBean
  private EmailBoxStorage      emailBoxStorage;

  @MockBean
  private FavoriteService      favoriteService;

  @MockBean
  private IdentityManager      identityManager;

  @Autowired
  private EmailFavoriteService emailFavoriteService;

  @Test
  public void reconcileFavoritesAddsTheNewlyFlaggedMails() throws Exception {
    givenUserIdentity();
    givenFlaggedEmails(11L, 12L);
    givenFavoritedEmailIds("11");

    emailFavoriteService.reconcileFavorites(USERNAME);

    ArgumentCaptor<Favorite> created = ArgumentCaptor.forClass(Favorite.class);
    verify(favoriteService, times(1)).createFavorite(created.capture());
    assertEquals("12", created.getValue().getObjectId());
    assertEquals(EmailFavoriteService.OBJECT_TYPE, created.getValue().getObjectType());
    assertEquals(IDENTITY_ID, created.getValue().getUserIdentityId());
  }

  @Test
  public void reconcileFavoritesDropsTheMailsNoLongerFlagged() throws Exception {
    givenUserIdentity();
    givenFlaggedEmails(11L);
    givenFavoritedEmailIds("11", "12");

    emailFavoriteService.reconcileFavorites(USERNAME);

    ArgumentCaptor<Favorite> deleted = ArgumentCaptor.forClass(Favorite.class);
    verify(favoriteService, times(1)).deleteFavorite(deleted.capture());
    assertEquals("12", deleted.getValue().getObjectId());
    verify(favoriteService, never()).createFavorite(any());
  }

  @Test
  public void reconcileFavoritesLeavesAnAlreadyAlignedDrawerAlone() throws Exception {
    givenUserIdentity();
    givenFlaggedEmails(11L, 12L);
    givenFavoritedEmailIds("11", "12");

    emailFavoriteService.reconcileFavorites(USERNAME);

    verify(favoriteService, never()).createFavorite(any());
    verify(favoriteService, never()).deleteFavorite(any());
  }

  @Test
  public void reconcileFavoritesSurvivesAFailingFavoriteStore() throws Exception {
    // A favorite that cannot be written is a stale drawer entry, never a reason to
    // fail the star toggle or the synchronization that called this.
    givenUserIdentity();
    givenFlaggedEmails(11L);
    givenFavoritedEmailIds();
    doThrow(new RuntimeException("favorites unavailable")).when(favoriteService).createFavorite(any());

    emailFavoriteService.reconcileFavorites(USERNAME);

    verify(favoriteService, times(1)).createFavorite(any());
  }

  @Test
  public void reconcileFavoritesDoesNothingWithoutAUser() {
    emailFavoriteService.reconcileFavorites(null);

    verify(identityManager, never()).getOrCreateUserIdentity(anyString());
    verify(favoriteService, never()).getFavoriteItemsByCreatorAndType(anyString(), anyLong(), anyLong(), anyLong());
  }

  /**
   * Mocks the social identity the favorites are keyed by.
   */
  private void givenUserIdentity() {
    Identity identity = new Identity(String.valueOf(IDENTITY_ID));
    when(identityManager.getOrCreateUserIdentity(USERNAME)).thenReturn(identity);
  }

  /**
   * Mocks the mails currently carrying the server's flag.
   *
   * @param emailIds the technical ids of the flagged mails
   */
  private void givenFlaggedEmails(Long... emailIds) {
    List<Email> emails = java.util.Arrays.stream(emailIds).map(id -> {
      Email email = new Email();
      email.setId(id);
      email.setStarred(true);
      return email;
    }).toList();
    when(emailBoxStorage.getStarredEmails(anyString(), anyString())).thenReturn(emails);
  }

  /**
   * Mocks the favorites already stored for the user.
   *
   * @param emailIds the technical ids the stored favorites point at
   */
  private void givenFavoritedEmailIds(String... emailIds) {
    List<MetadataItem> items = java.util.Arrays.stream(emailIds).map(id -> {
      MetadataItem item = new MetadataItem();
      item.setObjectId(id);
      return item;
    }).toList();
    when(favoriteService.getFavoriteItemsByCreatorAndType(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(items);
  }
}
