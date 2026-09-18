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
package org.exoplatform.emailConnector.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.emailConnector.dao.EmailContactDAO;
import org.exoplatform.emailConnector.entity.EmailContactEntity;

@ExtendWith(MockitoExtension.class)
public class ContactSortNameBackfillTest {

  @Mock
  private EmailContactDAO        emailContactDAO;

  @Mock
  private SettingService         settingService;

  @InjectMocks
  private ContactSortNameBackfill backfill;

  @Test
  void refilesOnlyTheContactsWithoutStructuredNamesWhoseKeyChanges() {
    EmailContactEntity structured = contact(1L, "John", "Doe", "Doe John", "DOE JOHN", 3);
    EmailContactEntity synced = contact(2L, null, null, "Alexandre Ducreux", "ALEXANDRE DUCREUX", 0);
    EmailContactEntity alreadyRight = contact(3L, null, null, "exo-support", "EXO-SUPPORT", 4);
    EmailContactEntity address = contact(4L, null, null, "ci@exoplatform.com", "CI@EXOPLATFORM.COM", 2);
    EmailContactEntity nameless = contact(5L, null, null, null, "JANE.DOE", 9);

    int rewritten = backfill.recomputeSortNames(List.of(structured, synced, alreadyRight, address, nameless));

    assertEquals(1, rewritten);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<EmailContactEntity>> saved = ArgumentCaptor.forClass(List.class);
    verify(emailContactDAO).saveAll(saved.capture());
    assertEquals(List.of(synced), saved.getValue());
    assertEquals("DUCREUX ALEXANDRE", synced.getSortName());
    assertEquals(3, synced.getSortBucket());
    assertEquals("DOE JOHN", structured.getSortName());
    assertEquals("JANE.DOE", nameless.getSortName());
  }

  @Test
  void nothingIsSavedWhenNoKeyChanges() {
    EmailContactEntity structured = contact(1L, "John", "Doe", "Doe John", "DOE JOHN", 3);

    assertEquals(0, backfill.recomputeSortNames(List.of(structured)));
    verify(emailContactDAO, never()).saveAll(anyList());
  }

  @Test
  void recomputeWalksEveryPageAndMarksTheRunDone() {
    EmailContactEntity synced = contact(2L, null, null, "John Doe", "JOHN DOE", 9);
    when(emailContactDAO.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(synced)));

    backfill.backfill();

    assertEquals("DOE JOHN", synced.getSortName());
    verify(settingService).set(eq(Context.GLOBAL), any(Scope.class), eq("emailContactSortNameBackfillDone"), any(SettingValue.class));
  }

  @Test
  void aCompletedRunIsNotRepeated() {
    doReturn(SettingValue.create("true")).when(settingService).get(eq(Context.GLOBAL), any(Scope.class), eq("emailContactSortNameBackfillDone"));

    backfill.backfill();

    verify(emailContactDAO, never()).findAll(any(Pageable.class));
  }

  private static EmailContactEntity contact(long id, String given, String family, String displayName, String sortName, int bucket) {
    EmailContactEntity entity = new EmailContactEntity();
    entity.setId(id);
    entity.setUserId("john");
    entity.setPrimaryEmail("jane.doe@example.com");
    entity.setGivenName(given);
    entity.setFamilyName(family);
    entity.setDisplayName(displayName);
    entity.setSortName(sortName);
    entity.setSortBucket(bucket);
    return entity;
  }
}
