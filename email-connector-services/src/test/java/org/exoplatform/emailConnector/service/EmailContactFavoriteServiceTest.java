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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.metadata.favorite.FavoriteService;
import org.exoplatform.social.metadata.favorite.model.Favorite;
import org.exoplatform.social.metadata.model.MetadataItem;

@ExtendWith(MockitoExtension.class)
@SpringBootTest(classes = { EmailContactFavoriteService.class })
public class EmailContactFavoriteServiceTest {

  private static final String         USERNAME    = "john";

  private static final long           IDENTITY_ID = 42L;

  @MockBean
  private FavoriteService             favoriteService;

  @MockBean
  private IdentityManager             identityManager;

  @Autowired
  private EmailContactFavoriteService emailContactFavoriteService;

  @Test
  public void getFavoriteContactIdsReadsTheStoreOnce() {
    givenUserIdentity();
    givenFavoritedContactIds("7", "12");

    Set<Long> ids = emailContactFavoriteService.getFavoriteContactIds(USERNAME);

    assertEquals(Set.of(7L, 12L), ids);
    verify(favoriteService).getFavoriteItemsByCreatorAndType(EmailContactFavoriteService.OBJECT_TYPE, IDENTITY_ID, 0, 500);
  }

  @Test
  public void getFavoriteContactIdsSkipsGarbageObjectIds() {
    // The favorites store is written through a REST anybody can call, so an
    // object id that is not a row id is noise the set simply drops.
    givenUserIdentity();
    givenFavoritedContactIds("7", "not-a-number", "", null, "99999999999999999999");

    assertEquals(Set.of(7L), emailContactFavoriteService.getFavoriteContactIds(USERNAME));
  }

  @Test
  public void getFavoriteContactIdsAnswersEmptyWithoutAUser() {
    assertTrue(emailContactFavoriteService.getFavoriteContactIds(null).isEmpty());
    assertTrue(emailContactFavoriteService.getFavoriteContactIds("  ").isEmpty());
    verify(favoriteService, never()).getFavoriteItemsByCreatorAndType(anyString(), anyLong(), anyLong(), anyLong());
  }

  @Test
  public void getFavoriteContactIdsSurvivesAFailingFavoriteStore() {
    // An unreadable favorites store costs the stars, never the contact list.
    givenUserIdentity();
    when(favoriteService.getFavoriteItemsByCreatorAndType(anyString(), anyLong(), anyLong(), anyLong()))
                                                                                                        .thenThrow(new RuntimeException("favorites unavailable"));

    assertTrue(assertDoesNotThrow(() -> emailContactFavoriteService.getFavoriteContactIds(USERNAME)).isEmpty());
  }

  @Test
  public void isFavoriteAsksTheStoreForTheOneRow() {
    givenUserIdentity();
    when(favoriteService.isFavorite(any())).thenReturn(true);

    assertTrue(emailContactFavoriteService.isFavorite(7L, USERNAME));

    ArgumentCaptor<Favorite> favorite = ArgumentCaptor.forClass(Favorite.class);
    verify(favoriteService).isFavorite(favorite.capture());
    assertEquals("7", favorite.getValue().getObjectId());
    assertEquals(EmailContactFavoriteService.OBJECT_TYPE, favorite.getValue().getObjectType());
    assertEquals(IDENTITY_ID, favorite.getValue().getUserIdentityId());
  }

  @Test
  public void isFavoriteAnswersFalseWithoutAnIdentity() {
    when(identityManager.getOrCreateUserIdentity(USERNAME)).thenReturn(null);
    assertFalse(emailContactFavoriteService.isFavorite(7L, USERNAME));
    verify(favoriteService, never()).isFavorite(any());
  }

  @Test
  public void removeFavoriteDeletesTheStoredFavorite() throws Exception {
    givenUserIdentity();

    emailContactFavoriteService.removeFavorite(7L, USERNAME);

    ArgumentCaptor<Favorite> favorite = ArgumentCaptor.forClass(Favorite.class);
    verify(favoriteService).deleteFavorite(favorite.capture());
    assertEquals("7", favorite.getValue().getObjectId());
    assertEquals(EmailContactFavoriteService.OBJECT_TYPE, favorite.getValue().getObjectType());
    assertEquals(IDENTITY_ID, favorite.getValue().getUserIdentityId());
  }

  @Test
  public void removeFavoriteToleratesAFailingFavoriteStore() throws Exception {
    // The cleanup of a dying contact must never fail the deletion that called it:
    // a favorite left behind is a stale drawer entry, a failed delete is a contact
    // the user asked to remove and still has.
    givenUserIdentity();
    doThrow(new RuntimeException("favorites unavailable")).when(favoriteService).deleteFavorite(any());

    assertDoesNotThrow(() -> emailContactFavoriteService.removeFavorite(7L, USERNAME));
  }

  @Test
  public void removeFavoriteDoesNothingWithoutAnIdentity() throws Exception {
    when(identityManager.getOrCreateUserIdentity(USERNAME)).thenReturn(null);
    emailContactFavoriteService.removeFavorite(7L, USERNAME);
    verify(favoriteService, never()).deleteFavorite(any());
  }

  /**
   * Mocks the social identity the favorites are keyed by.
   */
  private void givenUserIdentity() {
    Identity identity = new Identity(String.valueOf(IDENTITY_ID));
    when(identityManager.getOrCreateUserIdentity(USERNAME)).thenReturn(identity);
  }

  /**
   * Mocks the favorites already stored for the user.
   *
   * @param contactIds the object ids the stored favorites point at
   */
  private void givenFavoritedContactIds(String... contactIds) {
    List<MetadataItem> items = Arrays.stream(contactIds).map(id -> {
      MetadataItem item = new MetadataItem();
      item.setObjectId(id);
      return item;
    }).toList();
    when(favoriteService.getFavoriteItemsByCreatorAndType(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(items);
  }
}
