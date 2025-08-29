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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.plugin.EmailConnectorTranslationPlugin;
import org.exoplatform.emailConnector.storage.EmailConnectorStorage;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.security.Identity;
import org.exoplatform.services.security.IdentityConstants;

import io.meeds.social.translation.service.TranslationService;
import lombok.SneakyThrows;

/**
 * A Service to access and store email connectors
 */
@Service
public class EmailConnectorService {

  private static final String   EMAIL_CONNECTOR_IS_MANDATORY_MESSAGE = "Email connector is mandatory";

  private static final String   USER_NOT_ALLOWED_MESSAGE             = "User %s is not allowed to save email connector : %s";

  private static final String   EMAIL_CONNECTOR_NOT_FOUND_MESSAGE    = "Email connector with id %s doesn't exist";

  @Autowired
  private UserACL               userAcl;

  @Autowired
  private EmailConnectorStorage emailConnectorStorage;

  @Autowired
  private FileService           fileService;

  @Autowired
  private TranslationService    translationService;

  /**
   * Create new email connector that will be available for all users.
   *
   * @param emailConnector emailConnector to create
   * @param username user making the operation
   * @return stored {@link EmailConnector} in datasource
   * @throws IllegalAccessException if user is not allowed to create an email
   *           connector
   */
  public EmailConnector createEmailConnector(EmailConnector emailConnector, String username) throws IllegalAccessException {
    if (emailConnector == null) {
      throw new IllegalArgumentException(EMAIL_CONNECTOR_IS_MANDATORY_MESSAGE);
    }
    
    if (!canEdit(username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_MESSAGE, username, emailConnector.getName()));
    }
    return emailConnectorStorage.createEmailConnector(emailConnector);
  }

  /**
   * Get an email connector by id
   *
   * @param emailConnectorId email connector to find
   * @return stored {@link EmailConnector} in datasource
   */
  public EmailConnector getEmailConnector(long emailConnectorId) {
    return emailConnectorStorage.getEmailConnector(emailConnectorId);
  }

  /**
   * Get email connectors that will be available for all users.
   *
   * @param locale used language to retrieve email connector name
   * @return list of stored {@link EmailConnector} in datasource
   */
  public List<EmailConnector> getEmailConnectors(Locale locale) {
    List<EmailConnector> emailConnectors = emailConnectorStorage.getEmailConnectors();
    emailConnectors = emailConnectors.stream().map(emailConnector -> {
      String translatedName =
                            translationService.getTranslationLabelOrDefault(EmailConnectorTranslationPlugin.EMAIL_CONNECTOR_OBJECT_TYPE,
                                                                            emailConnector.getId(),
                                                                            "name",
                                                                            locale);
      emailConnector.setName(translatedName);
      return emailConnector;
    }).toList();
    return emailConnectors;
  }

  /**
   * Return the {@link EmailConnector} illustration {@link InputStream}, if not
   * found, the default image {@link InputStream} will be retrieved
   *
   * @param emailConnectorId technical id of email connector
   * @return {@link InputStream} of email connector illustration
   * @throws IllegalAccessException if email connector wasn't found
   */
  @SneakyThrows
  public InputStream getEmailConnectorImageInputStream(long emailConnectorId) throws IllegalAccessException {
    EmailConnector emailConnector = emailConnectorStorage.getEmailConnector(emailConnectorId);
    if (emailConnector == null) {
      throw new IllegalAccessException(String.format(EMAIL_CONNECTOR_NOT_FOUND_MESSAGE, emailConnectorId));
    } else if (emailConnector.getImageFileId() != null) {
      return getApplicationImageInputStream(emailConnector.getImageFileId());
    } else {
      return null;
    }
  }

  public boolean canEdit(String username) {
    return StringUtils.isBlank(username) || userAcl.isAdministrator(getUserIdentity(username));
  }

  @SneakyThrows
  private Identity getUserIdentity(String username) {
    if (StringUtils.isBlank(username)) {
      return new Identity(IdentityConstants.ANONIM);
    } else {
      return userAcl.getUserIdentity(username);
    }
  }

  @SneakyThrows
  private InputStream getApplicationImageInputStream(long fileId) {
    FileItem fileItem = fileService.getFile(fileId);
    if (fileItem != null && fileItem.getAsByte() != null) {
      return new ByteArrayInputStream(fileItem.getAsByte());
    }
    return null;
  }
}
