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

import java.io.ByteArrayInputStream;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.commons.utils.IOUtil;
import org.exoplatform.emailConnector.dao.EmailConnectorDAO;
import org.exoplatform.emailConnector.entity.EmailConnectorEntity;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

import lombok.SneakyThrows;

/**
 * Storage service to access / load and save email connectors. This service will
 * be used, as well, to convert from JPA entity to DTO.
 */
@Component
public class EmailConnectorStorage {

  public static final String NAME_SPACE = "emailConnector";

  @Autowired
  private EmailConnectorDAO  emailConnectorDAO;

  @Autowired
  private UploadService      uploadService;

  @Autowired
  private FileService        fileService;

  public EmailConnector createEmailConnector(EmailConnector emailConnector) {
    if (emailConnector == null) {
      throw new IllegalArgumentException("emailConnector is mandatory");
    }
    EmailConnectorEntity emailConnectorEntity = toEntity(emailConnector);
    if (StringUtils.isNotBlank(emailConnector.getImageUploadId())) {
      Long imageFileId = saveImageFileItem(null, emailConnector.getImageUploadId());
      emailConnectorEntity.setImageFileId(imageFileId);
    }
    emailConnectorEntity = emailConnectorDAO.save(emailConnectorEntity);
    return fromEntity(emailConnectorEntity);
  }

  public void updateEmailConnector(EmailConnector emailConnector) {
    Long emailConnectorId = emailConnector.getId();
    EmailConnectorEntity storedEmailConnectorEntity = emailConnectorDAO.findById(emailConnectorId).orElseThrow();
    Long oldImageFileId = storedEmailConnectorEntity.getImageFileId();

    boolean imageRemoved = (emailConnector.getImageFileId() == null || emailConnector.getImageFileId() == 0)
        && oldImageFileId != null && oldImageFileId > 0;
    emailConnector.setImageFileId(oldImageFileId);
    if (imageRemoved) {
      emailConnector.setImageFileId(null);
      // Cleanup old useless image
      fileService.deleteFile(oldImageFileId);
    }
    if (StringUtils.isNotBlank(emailConnector.getImageUploadId())) {
      Long imageFileId = saveImageFileItem(oldImageFileId, emailConnector.getImageUploadId());
      emailConnector.setImageFileId(imageFileId);
    }

    EmailConnectorEntity emailConnectorEntity = toEntity(emailConnector);
    emailConnectorDAO.save(emailConnectorEntity);
  }

  public EmailConnector getEmailConnector(long emailConnectorId) {
    EmailConnectorEntity emailConnectorEntity = emailConnectorDAO.findById(emailConnectorId).orElse(null);
    return fromEntity(emailConnectorEntity);
  }

  public List<EmailConnector> getEmailConnectors() {
    List<EmailConnectorEntity> emailConnectorEntities = emailConnectorDAO.findAll();
    return emailConnectorEntities.stream()
                                 .map(emailConnectorEntity -> fromEntity(emailConnectorEntity))
                                 .collect(Collectors.toList());
  }

  private EmailConnectorEntity toEntity(EmailConnector emailConnector) {
    if (emailConnector == null) {
      return null;
    } else {
      return new EmailConnectorEntity(emailConnector.getId(),
                                      emailConnector.getName(),
                                      emailConnector.getImageFileId(),
                                      emailConnector.getIcon(),
                                      emailConnector.getImapUrl(),
                                      emailConnector.getPort(),
                                      emailConnector.isActive());
    }
  }

  private EmailConnector fromEntity(EmailConnectorEntity emailConnectorEntity) {
    if (emailConnectorEntity == null) {
      return null;
    } else {
      EmailConnector emailConnector = new EmailConnector(emailConnectorEntity.getId(),
                                                         emailConnectorEntity.getName(),
                                                         getImageUrl(emailConnectorEntity.getImageFileId(),
                                                                     emailConnectorEntity.getId()),
                                                         emailConnectorEntity.getImageFileId(),
                                                         emailConnectorEntity.getIcon(),
                                                         emailConnectorEntity.getImapUrl(),
                                                         emailConnectorEntity.getPort(),
                                                         emailConnectorEntity.isActive(),
                                                         null);
      return emailConnector;
    }
  }

  @SneakyThrows
  private Long saveImageFileItem(Long imageFileId, String uploadId) {
    UploadResource uploadResource = uploadService.getUploadResource(uploadId);
    byte[] bytesContent = IOUtil.getFileContentAsBytes(uploadResource.getStoreLocation());
    FileItem fileItem = new FileItem(imageFileId,
                                     "emailConnectorIllustration",
                                     "image/png",
                                     NAME_SPACE,
                                     bytesContent.length,
                                     new Date(),
                                     null,
                                     false,
                                     new ByteArrayInputStream(bytesContent));
    if (imageFileId != null && imageFileId > 0) {
      fileItem = fileService.updateFile(fileItem);
    } else {
      fileItem = fileService.writeFile(fileItem);
    }
    return fileItem == null || fileItem.getFileInfo() == null ? null : fileItem.getFileInfo().getId();
  }

  private String getImageUrl(Long imageFileId, Long id) {
    if (imageFileId == null || imageFileId.longValue() == 0) {
      return null;
    } else {
      return String.format("/email-connector/rest/emailConnector/illustration/%s", id);
    }
  }
}
