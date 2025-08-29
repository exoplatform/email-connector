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
import java.util.stream.Collectors;

import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.dao.EmailConnectorDAO;
import org.exoplatform.emailConnector.entity.EmailConnectorEntity;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.upload.UploadService;

@SpringBootTest(classes = { EmailConnectorStorage.class })
@ExtendWith(MockitoExtension.class)
public class EmailConnectorStorageTest {

  private static final Long     ID = 2l;

  @MockBean
  private EmailConnectorDAO     emailConnectorDAO;

  @MockBean
  private UploadService         uploadService;

  @MockBean
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
      when(emailConnectorDAO.findAll()).thenReturn(Optional.of(entity).stream().collect(Collectors.toList()));
      return entity;
    });
  }

  @Test
  void createEmailConnector() {
    assertThrows(IllegalArgumentException.class, () -> emailConnectorStorage.createEmailConnector(null));
    EmailConnector emailConnector = emailConnector(null);
    EmailConnector storedEmailConnector = emailConnectorStorage.createEmailConnector(emailConnector);
    assertNotNull(storedEmailConnector);
    assertNotNull(storedEmailConnector.getId());
    assertTrue(storedEmailConnector.getId() > 0);
  }

  @Test
  void getEmailConnector() {
    assertNull(emailConnectorStorage.getEmailConnector(1000l));
    EmailConnector emailConnector = emailConnector(null);
    EmailConnector storedEmailConnector = emailConnectorStorage.createEmailConnector(emailConnector);
    EmailConnector retrievedEmailConnector = emailConnectorStorage.getEmailConnector(storedEmailConnector.getId());
    assertNotNull(retrievedEmailConnector);
    assertEquals(storedEmailConnector.getId(), retrievedEmailConnector.getId());
    assertEquals("testName", retrievedEmailConnector.getName());
    assertEquals("testImapUrl", retrievedEmailConnector.getImapUrl());
    assertEquals("testPort", retrievedEmailConnector.getPort());
  }

  @Test
  void getEmailConnectors() {
    List<EmailConnector> retrievedEmailConnectorEntities = emailConnectorStorage.getEmailConnectors();
    assertEquals(0, retrievedEmailConnectorEntities.size());
    EmailConnector emailConnector = emailConnector(null);
    EmailConnector storedEmailConnector = emailConnectorStorage.createEmailConnector(emailConnector);
    retrievedEmailConnectorEntities = emailConnectorStorage.getEmailConnectors();
    assertNotNull(retrievedEmailConnectorEntities);
    assertEquals(1, retrievedEmailConnectorEntities.size());
    assertNotNull(retrievedEmailConnectorEntities.get(0));
    assertEquals(storedEmailConnector.getId(), retrievedEmailConnectorEntities.get(0).getId());
    assertEquals("testName", retrievedEmailConnectorEntities.get(0).getName());
    assertEquals("testImapUrl", retrievedEmailConnectorEntities.get(0).getImapUrl());
    assertEquals("testPort", retrievedEmailConnectorEntities.get(0).getPort());
  }

  private EmailConnector emailConnector(Long id) {
    return new EmailConnector(id, "testName", null, null, null, "testImapUrl", "testPort", false, null);
  }
}
