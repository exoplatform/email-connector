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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

  @Test
  public void reconcileFavoritesDoesNothingWithoutAnIdentity() throws Exception {
    // A user with no social identity yet has nothing favorites can be keyed by.
    when(identityManager.getOrCreateUserIdentity(USERNAME)).thenReturn(null);

    emailFavoriteService.reconcileFavorites(USERNAME);

    verify(favoriteService, never()).getFavoriteItemsByCreatorAndType(anyString(), anyLong(), anyLong(), anyLong());
    verify(favoriteService, never()).createFavorite(any());
  }

  @Test
  public void reconcileFavoritesSurvivesAFailingIdentityManager() throws Exception {
    doThrow(new RuntimeException("identity store unavailable")).when(identityManager).getOrCreateUserIdentity(USERNAME);

    emailFavoriteService.reconcileFavorites(USERNAME);

    verify(favoriteService, never()).createFavorite(any());
  }

  @Test
  public void reconcileFavoritesSurvivesAFailingMailboxRead() throws Exception {
    // The read of the flagged mails is inside the guarded block: a failure there
    // leaves the drawer as it was instead of failing the sync that called this.
    givenUserIdentity();
    doThrow(new RuntimeException("storage unavailable")).when(emailBoxStorage).getStarredEmails(anyString(), anyString());

    emailFavoriteService.reconcileFavorites(USERNAME);

    verify(favoriteService, never()).createFavorite(any());
    verify(favoriteService, never()).deleteFavorite(any());
  }

  @Test
  public void reconcileFavoritesSurvivesAFavoriteThatCannotBeRemoved() throws Exception {
    givenUserIdentity();
    givenFlaggedEmails();
    givenFavoritedEmailIds("12");
    doThrow(new RuntimeException("favorite already gone")).when(favoriteService).deleteFavorite(any());

    emailFavoriteService.reconcileFavorites(USERNAME);

    verify(favoriteService, times(1)).deleteFavorite(any());
  }

  @Test
  public void isFavorite() throws Exception {
    givenUserIdentity();
    when(favoriteService.isFavorite(any(Favorite.class))).thenReturn(true);

    assertTrue(emailFavoriteService.isFavorite(11L, USERNAME));

    ArgumentCaptor<Favorite> asked = ArgumentCaptor.forClass(Favorite.class);
    verify(favoriteService).isFavorite(asked.capture());
    assertEquals("11", asked.getValue().getObjectId());
    assertEquals(EmailFavoriteService.OBJECT_TYPE, asked.getValue().getObjectType());
    assertEquals(IDENTITY_ID, asked.getValue().getUserIdentityId());
  }

  @Test
  public void isFavoriteForAMailThatIsNot() throws Exception {
    givenUserIdentity();
    when(favoriteService.isFavorite(any(Favorite.class))).thenReturn(false);

    assertFalse(emailFavoriteService.isFavorite(11L, USERNAME));
  }

  @Test
  public void isFavoriteWithoutAResolvableUser() throws Exception {
    // No username, and a username whose identity carries no id: both answer false
    // without asking the favorites store, which would otherwise be queried for
    // identity 0.
    assertFalse(emailFavoriteService.isFavorite(11L, null));
    when(identityManager.getOrCreateUserIdentity(USERNAME)).thenReturn(new Identity(null));
    assertFalse(emailFavoriteService.isFavorite(11L, USERNAME));

    verify(identityManager, never()).getOrCreateUserIdentity(null);
    verify(favoriteService, never()).isFavorite(any(Favorite.class));
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
