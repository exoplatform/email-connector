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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.dao.EmailConnectorDAO;
import org.exoplatform.emailConnector.entity.EmailConnectorEntity;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.upload.UploadService;

@SpringBootTest(classes = { EmailConnectorStorage.class })
@ExtendWith(MockitoExtension.class)
public class EmailConnectorStorageTest {

  private static final Long     ID = 2l;

  @MockitoBean
  private EmailConnectorDAO     emailConnectorDAO;

  @MockitoBean
  private UploadService         uploadService;

  @MockitoBean
  private FileService           fileService;

  @Autowired
  private EmailConnectorStorage emailConnectorStorage;

  @BeforeEach
  void setup() {
    when(emailConnectorDAO.save(any())).thenAnswer(invocation -> {
      EmailConnectorEntity entity = invocation.getArgument(0);
      if (entity.getId() == null) {
        entity.setId(ID);
      }
      when(emailConnectorDAO.findById(ID)).thenReturn(Optional.of(entity));
      when(emailConnectorDAO.findAll()).thenReturn(Optional.of(entity).stream().toList());
      when(emailConnectorDAO.findActiveEmailConnectors()).thenReturn(Optional.of(entity)
                                                                             .stream()
                                                                             .filter(EmailConnectorEntity::isActive)
                                                                             .toList());
      return entity;
    });
    doAnswer(invocation -> {
      EmailConnectorEntity entity = invocation.getArgument(0);
      when(emailConnectorDAO.findById(entity.getId())).thenReturn(Optional.empty());
      return null;
    }).when(emailConnectorDAO).delete(any());
  }

  @Test
  void createEmailConnector() {
    assertThrows(IllegalArgumentException.class, () -> emailConnectorStorage.createEmailConnector(null));
    EmailConnector emailConnector = emailConnector();
    EmailConnector storedEmailConnector = emailConnectorStorage.createEmailConnector(emailConnector);
    assertNotNull(storedEmailConnector);
    assertNotNull(storedEmailConnector.getId());
    assertTrue(storedEmailConnector.getId() > 0);
  }

  @Test
  void updateEmailConnector() {
    EmailConnector emailConnector = emailConnector();
    EmailConnector storedEmailConnector = emailConnectorStorage.createEmailConnector(emailConnector);
    storedEmailConnector.setName("testNameUpdated");
    storedEmailConnector.setImapUrl("testImapUrlUpdated");
    storedEmailConnector.setImapPort("testImapPortUpdated");
    storedEmailConnector.setSmtpUrl("testSmtpUrlUpdated");
    storedEmailConnector.setSmtpPort("testSmtpPortUpdated");
    storedEmailConnector.setSmtpSecurityType("testSmtpSecurityTypeUpdated");
    emailConnectorStorage.updateEmailConnector(storedEmailConnector);
    EmailConnector retrievedEmailConnector = emailConnectorStorage.getEmailConnector(storedEmailConnector.getId());
    assertNotNull(retrievedEmailConnector);
    assertNotNull(retrievedEmailConnector.getId());
    assertEquals(storedEmailConnector.getId(), retrievedEmailConnector.getId());
    assertEquals("testNameUpdated", retrievedEmailConnector.getName());
    assertEquals("testImapUrlUpdated", retrievedEmailConnector.getImapUrl());
    assertEquals("testImapPortUpdated", retrievedEmailConnector.getImapPort());
    assertEquals("testSmtpUrlUpdated", retrievedEmailConnector.getSmtpUrl());
    assertEquals("testSmtpPortUpdated", retrievedEmailConnector.getSmtpPort());
    assertEquals("testSmtpSecurityTypeUpdated", retrievedEmailConnector.getSmtpSecurityType());
  }

  @Test
  void activateEmailConnector() {
    EmailConnector emailConnector = emailConnector();
    EmailConnector storedEmailConnector = emailConnectorStorage.createEmailConnector(emailConnector);
    assertNotNull(storedEmailConnector);
    assertNotNull(storedEmailConnector.getId());
    assertEquals(false, storedEmailConnector.isActive());
    emailConnectorStorage.activateEmailConnector(storedEmailConnector.getId(), true);
    EmailConnector retrievedEmailConnector = emailConnectorStorage.getEmailConnector(storedEmailConnector.getId());
    assertNotNull(retrievedEmailConnector);
    assertEquals(true, retrievedEmailConnector.isActive());
  }

  @Test
  void deleteEmailConnector() {
    EmailConnector emailConnector = emailConnector();
    EmailConnector storedEmailConnector = emailConnectorStorage.createEmailConnector(emailConnector);
    assertNotNull(storedEmailConnector);
    assertNotNull(storedEmailConnector.getId());
    emailConnectorStorage.deleteEmailConnector(storedEmailConnector.getId());
    EmailConnector retrievedEmailConnector = emailConnectorStorage.getEmailConnector(storedEmailConnector.getId());
    assertNull(retrievedEmailConnector);
  }

  @Test
  void getEmailConnector() {
    assertNull(emailConnectorStorage.getEmailConnector(1000l));
    EmailConnector emailConnector = emailConnector();
    EmailConnector storedEmailConnector = emailConnectorStorage.createEmailConnector(emailConnector);
    EmailConnector retrievedEmailConnector = emailConnectorStorage.getEmailConnector(storedEmailConnector.getId());
    assertNotNull(retrievedEmailConnector);
    assertEquals(storedEmailConnector.getId(), retrievedEmailConnector.getId());
    assertEquals("testName", retrievedEmailConnector.getName());
    assertEquals("testImapUrl", retrievedEmailConnector.getImapUrl());
    assertEquals("testImapPort", retrievedEmailConnector.getImapPort());
    assertEquals("testSmtpUrl", retrievedEmailConnector.getSmtpUrl());
    assertEquals("testSmtpPort", retrievedEmailConnector.getSmtpPort());
    assertEquals("testSmtpSecurityType", retrievedEmailConnector.getSmtpSecurityType());
  }

  @Test
  void getEmailConnectors() {
    List<EmailConnector> retrievedEmailConnectorEntities = emailConnectorStorage.getEmailConnectors();
    assertEquals(0, retrievedEmailConnectorEntities.size());
    EmailConnector emailConnector = emailConnector();
    EmailConnector storedEmailConnector = emailConnectorStorage.createEmailConnector(emailConnector);
    retrievedEmailConnectorEntities = emailConnectorStorage.getEmailConnectors();
    assertNotNull(retrievedEmailConnectorEntities);
    assertEquals(1, retrievedEmailConnectorEntities.size());
    assertNotNull(retrievedEmailConnectorEntities.get(0));
    assertEquals(storedEmailConnector.getId(), retrievedEmailConnectorEntities.get(0).getId());
    assertEquals("testName", retrievedEmailConnectorEntities.get(0).getName());
    assertEquals("testImapUrl", retrievedEmailConnectorEntities.get(0).getImapUrl());
    assertEquals("testImapPort", retrievedEmailConnectorEntities.get(0).getImapPort());
    assertEquals("testSmtpUrl", retrievedEmailConnectorEntities.get(0).getSmtpUrl());
    assertEquals("testSmtpPort", retrievedEmailConnectorEntities.get(0).getSmtpPort());
    assertEquals("testSmtpSecurityType", retrievedEmailConnectorEntities.get(0).getSmtpSecurityType());
  }

  @Test
  void getActiveEmailConnectors() {
    List<EmailConnector> retrievedActiveEmailConnectorEntities = emailConnectorStorage.getActiveEmailConnectors();
    assertEquals(0, retrievedActiveEmailConnectorEntities.size());
    EmailConnector emailConnector1 = emailConnector();
    emailConnectorStorage.createEmailConnector(emailConnector1);
    retrievedActiveEmailConnectorEntities = emailConnectorStorage.getActiveEmailConnectors();
    assertEquals(0, retrievedActiveEmailConnectorEntities.size());
    EmailConnector emailConnector2 = emailConnector();
    emailConnector2.setActive(true);
    EmailConnector storedEmailConnector2 = emailConnectorStorage.createEmailConnector(emailConnector2);
    retrievedActiveEmailConnectorEntities = emailConnectorStorage.getActiveEmailConnectors();
    assertEquals(1, retrievedActiveEmailConnectorEntities.size());
    assertNotNull(retrievedActiveEmailConnectorEntities.get(0));
    assertEquals(storedEmailConnector2.getId(), retrievedActiveEmailConnectorEntities.get(0).getId());
  }

  private EmailConnector emailConnector() {
    return new EmailConnector(null,
                              "testName",
                              null,
                              null,
                              null,
                              "testImapUrl",
                              "testImapPort",
                              "testSmtpUrl",
                              "testSmtpPort",
                              "testSmtpSecurityType",
                              false,
                              true,
                              false,
                              null,
                              "", null);
  }
}
