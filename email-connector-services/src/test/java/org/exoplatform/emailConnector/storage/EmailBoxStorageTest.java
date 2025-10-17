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
package org.exoplatform.emailConnector.storage;

import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.Email;

@SpringBootTest(classes = { EmailBoxStorage.class })
@ExtendWith(MockitoExtension.class)
public class EmailBoxStorageTest {

  private static final Long ID = 2l;

  @MockBean
  private EmailBoxDAO       emailBoxDAO;

  @Autowired
  private EmailBoxStorage   emailBoxStorage;

  @BeforeEach
  void setup() {

    when(emailBoxDAO.save(any())).thenAnswer(invocation -> {
      EmailBoxEntity entity = invocation.getArgument(0);
      if (entity.getId() == null) {
        entity.setId(ID);
      }
      when(emailBoxDAO.findByUserIdOrderBySentDateDesc("root")).thenReturn(Optional.of(entity)
                                                                                   .stream()
                                                                                   .filter(email -> email.getUserId()
                                                                                                         .equals("root"))
                                                                                   .collect(Collectors.toList()));
      when(emailBoxDAO.findByUserIdAndMailRemoteId("root", 1212l)).thenReturn(entity);
      return entity;
    });

    doAnswer(invocation -> {
      when(emailBoxDAO.findByUserIdOrderBySentDateDesc("root")).thenReturn(Collections.emptyList());
      return null;
    }).when(emailBoxDAO).deleteByUserId(any());

    doAnswer(invocation -> {
      when(emailBoxDAO.findByUserIdOrderBySentDateDesc("root")).thenReturn(Collections.emptyList());
      return null;
    }).when(emailBoxDAO).deleteEmailsByIds(any());
  }

  @Test
  void createEmail() {
    assertThrows(IllegalArgumentException.class, () -> emailBoxStorage.createEmail(null));
    Email email = email("root");
    Email storedEmail = emailBoxStorage.createEmail(email);
    assertNotNull(storedEmail);
    assertNotNull(storedEmail.getId());
    assertTrue(storedEmail.getId() > 0);
  }

  @Test
  void getEmailByMailRemoteIdAndUserId() {
    Email retrievedEmail = emailBoxStorage.getEmailByMailRemoteIdAndUserId("root", 1L);
    assertNull(retrievedEmail);
    Email email1 = email("root");
    emailBoxStorage.createEmail(email1);
    retrievedEmail = emailBoxStorage.getEmailByMailRemoteIdAndUserId("root", 1212l);
    assertNotNull(retrievedEmail);
  }

  @Test
  void getEmails() {
    List<Email> retrievedEmailEntities = emailBoxStorage.getEmails("root");
    assertEquals(0, retrievedEmailEntities.size());
    Email email1 = email("root");
    emailBoxStorage.createEmail(email1);
    retrievedEmailEntities = emailBoxStorage.getEmails("root");
    assertNotNull(retrievedEmailEntities);
    assertEquals(1, retrievedEmailEntities.size());
    assertNotNull(retrievedEmailEntities.get(0));
    assertEquals(2l, retrievedEmailEntities.get(0).getId());
    assertEquals(1212l, retrievedEmailEntities.get(0).getMailRemoteId());
    assertEquals("subject", retrievedEmailEntities.get(0).getSubject());
    assertEquals("excerpt", retrievedEmailEntities.get(0).getExcerpt());
    assertEquals("sender", retrievedEmailEntities.get(0).getSender());
  }

  @Test
  void deletetUserEmails() {
    Email email1 = email("root");
    emailBoxStorage.createEmail(email1);
    List<Email> retrievedEmailEntities = emailBoxStorage.getEmails("root");
    assertNotNull(retrievedEmailEntities);
    assertEquals(1, retrievedEmailEntities.size());
    emailBoxStorage.deleteUserEmails("root");
    retrievedEmailEntities = emailBoxStorage.getEmails("root");
    assertEquals(0, retrievedEmailEntities.size());
  }

  @Test
  void deleteEmails() {
    Email email1 = email("root");
    Email storedEmail = emailBoxStorage.createEmail(email1);
    List<Email> retrievedEmailEntities = emailBoxStorage.getEmails("root");
    assertNotNull(retrievedEmailEntities);
    assertEquals(1, retrievedEmailEntities.size());
    List<Long> emailsIds = List.of(storedEmail.getId());
    emailBoxStorage.deleteEmails(emailsIds);
    retrievedEmailEntities = emailBoxStorage.getEmails("root");
    assertEquals(0, retrievedEmailEntities.size());
  }

  private Email email(String username) {
    return new Email(null, 1212l, username, "subject", "excerpt", "sender", new Date());
  }
}
