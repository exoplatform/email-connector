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
package org.exoplatform.emailConnector.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.documents.model.AbstractNode;
import org.exoplatform.documents.model.FileNode;
import org.exoplatform.documents.service.DocumentFileService;
import org.exoplatform.emailConnector.constant.DocumentRefusalReason;
import org.exoplatform.emailConnector.exception.DocumentRefusedException;
import org.exoplatform.emailConnector.model.EmailDocumentContent;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.portal.thumbnail.model.FileContent;

/**
 * Reads a user's document through the Documents add-on's own service, with that user's
 * rights. Documents reads in a session of the calling thread's current user
 * ({@code ConversationState.getCurrent()}), whatever user name it is given -- that name
 * only feeds the document's computed permissions -- so the reader reads only on a thread
 * whose current user is the user named, and refuses otherwise. A document that user
 * cannot open is not found for them: Documents answers it as it answers an unknown id.
 * <p>
 * Present only when the Documents API is on the classpath, which is the case wherever
 * the Documents add-on is deployed; the condition is read from bytecode metadata, so
 * the class is never loaded when it is absent. The service itself is looked up at each
 * read rather than injected, so this bean starts whatever order the add-ons start in,
 * and a classpath carrying the API without the add-on refuses documents as unavailable
 * rather than failing to start.
 */
@Component
@ConditionalOnClass(DocumentFileService.class)
public class DocumentsEmailDocumentReader implements EmailDocumentReader {

  /** The reader's logger. */
  private static final Log                          LOG               = ExoLogger.getLogger(DocumentsEmailDocumentReader.class);

  /** The content type a document without one is attached under. */
  private static final String                       DEFAULT_MIME_TYPE = "application/octet-stream";

  /** The chunk the content is read by. */
  private static final int                          BUFFER_SIZE       = 8192;

  /** The Documents service, looked up at each read. */
  private final ObjectProvider<DocumentFileService> documentFileService;

  /**
   * The reader over the Documents service.
   *
   * @param documentFileService the Documents service, resolved at each read
   */
  public DocumentsEmailDocumentReader(ObjectProvider<DocumentFileService> documentFileService) {
    this.documentFileService = documentFileService;
  }

  /**
   * {@inheritDoc}
   * <p>
   * The thread's current user must be {@code username}: Documents reads as that current
   * user, never as the name it is given. The document is resolved first, which is the
   * access check -- a document the user cannot open is not found -- and gives
   * its name and declared size: a folder, or a file declared larger than
   * {@code maxSize}, is refused without its content being read. The content is then
   * read as the user too, and never beyond {@code maxSize + 1} bytes, so a file whose
   * declared size is wrong -- Documents declares 0 when it cannot read the size -- is
   * still refused before it fills memory. A failure of Documents itself while reading is
   * refused as unreadable, which keeps it this document's: the next one is still read.
   */
  @Override
  public EmailDocumentContent readDocument(String documentId,
                                           String username,
                                           long maxSize) throws DocumentRefusedException {
    ConversationState current = ConversationState.getCurrent();
    if (current == null || current.getIdentity() == null || !StringUtils.equals(current.getIdentity().getUserId(), username)) {
      LOG.warn("Document {} not read for user {}: the thread's current user is another one", documentId, username);
      throw new DocumentRefusedException(DocumentRefusalReason.UNREADABLE, null);
    }
    DocumentFileService service;
    try {
      service = documentFileService.getIfAvailable();
    } catch (RuntimeException e) {
      LOG.warn("The Documents service could not be resolved to read document {} for user {}", documentId, username, e);
      throw new DocumentRefusedException(DocumentRefusalReason.UNREADABLE, null);
    }
    if (service == null) {
      throw new DocumentRefusedException(DocumentRefusalReason.DOCUMENTS_UNAVAILABLE, null);
    }
    AbstractNode node = resolve(service, documentId, username);
    String name = node.getName();
    if (!(node instanceof FileNode file)) {
      throw new DocumentRefusedException(DocumentRefusalReason.NOT_A_FILE, name);
    }
    if (file.getSize() > maxSize) {
      throw new DocumentRefusedException(DocumentRefusalReason.TOO_LARGE, name);
    }
    FileContent content = content(service, documentId, username, name);
    name = StringUtils.defaultIfBlank(content.getName(), name);
    String mimeType = StringUtils.firstNonBlank(content.getMimeType(), file.getMimeType(), DEFAULT_MIME_TYPE);
    try (InputStream stream = content.getContent()) {
      return new EmailDocumentContent(name, mimeType, readAtMost(stream, maxSize, name));
    } catch (IOException | RuntimeException e) {
      LOG.warn("The document {} of user {} could not be read to be attached to a draft", documentId, username, e);
      throw new DocumentRefusedException(DocumentRefusalReason.UNREADABLE, name);
    }
  }

  /**
   * The document as the user sees it in Documents.
   *
   * @param service the Documents service
   * @param documentId the document's id
   * @param username the user
   * @return the document, never null
   * @throws DocumentRefusedException when it is not found, not open to the user, or
   *           cannot be resolved at all
   */
  private AbstractNode resolve(DocumentFileService service,
                               String documentId,
                               String username) throws DocumentRefusedException {
    try {
      AbstractNode node = service.getDocumentById(documentId, username);
      if (node == null) {
        throw new DocumentRefusedException(DocumentRefusalReason.NOT_FOUND, null);
      }
      return node;
    } catch (ObjectNotFoundException e) {
      throw new DocumentRefusedException(DocumentRefusalReason.NOT_FOUND, null);
    } catch (IllegalAccessException e) {
      throw new DocumentRefusedException(DocumentRefusalReason.NO_ACCESS, null);
    } catch (RuntimeException e) {
      LOG.warn("The document {} could not be resolved for user {} to be attached to a draft", documentId, username, e);
      throw new DocumentRefusedException(DocumentRefusalReason.UNREADABLE, null);
    }
  }

  /**
   * The document's content, read with the user's rights.
   *
   * @param service the Documents service
   * @param documentId the document's id
   * @param username the user
   * @param name the document's name, for the refusal
   * @return the content, never null
   * @throws DocumentRefusedException when it is gone or cannot be read
   */
  private FileContent content(DocumentFileService service,
                              String documentId,
                              String username,
                              String name) throws DocumentRefusedException {
    try {
      FileContent content = service.getDocumentContent(documentId, username);
      if (content == null || content.getContent() == null) {
        throw new DocumentRefusedException(DocumentRefusalReason.NOT_FOUND, name);
      }
      return content;
    } catch (ObjectNotFoundException e) {
      throw new DocumentRefusedException(DocumentRefusalReason.NOT_FOUND, name);
    } catch (RuntimeException e) {
      LOG.warn("The content of document {} could not be read for user {} to be attached to a draft", documentId, username, e);
      throw new DocumentRefusedException(DocumentRefusalReason.UNREADABLE, name);
    }
  }

  /**
   * Reads a stream to its end, refusing it as soon as it passes {@code maxSize} bytes.
   *
   * @param stream the content
   * @param maxSize the most bytes it may hold
   * @param name the document's name, for the refusal
   * @return the bytes
   * @throws IOException when the stream fails
   * @throws DocumentRefusedException when the content is larger than {@code maxSize}
   */
  private byte[] readAtMost(InputStream stream, long maxSize, String name) throws IOException, DocumentRefusedException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[BUFFER_SIZE];
    long total = 0;
    int read;
    while ((read = stream.read(buffer)) != -1) {
      total += read;
      if (total > maxSize) {
        throw new DocumentRefusedException(DocumentRefusalReason.TOO_LARGE, name);
      }
      bytes.write(buffer, 0, read);
    }
    return bytes.toByteArray();
  }
}
