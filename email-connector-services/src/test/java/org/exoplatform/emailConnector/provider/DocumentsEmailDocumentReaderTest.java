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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.Date;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.documents.model.FileNode;
import org.exoplatform.documents.model.FolderNode;
import org.exoplatform.documents.service.DocumentFileService;
import org.exoplatform.emailConnector.constant.DocumentRefusalReason;
import org.exoplatform.emailConnector.exception.DocumentRefusedException;
import org.exoplatform.emailConnector.model.EmailDocumentContent;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;

import io.meeds.portal.thumbnail.model.FileContent;

/**
 * The Documents-backed reader: what it refuses, with which reason, and that it reads a
 * document only as the user, never past the size it is given.
 */
class DocumentsEmailDocumentReaderTest {

  private static final String         USERNAME    = "testuser1";

  private static final String         DOCUMENT_ID = "doc-1";

  private DocumentFileService         documentFileService;

  private ObjectProvider<DocumentFileService> provider;

  private DocumentsEmailDocumentReader reader;

  /** A reader over a mocked Documents service. */
  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    documentFileService = mock(DocumentFileService.class);
    provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(documentFileService);
    reader = new DocumentsEmailDocumentReader(provider);
    ConversationState.setCurrent(new ConversationState(new Identity(USERNAME)));
  }

  /** No current user is left on the thread for the next test. */
  @AfterEach
  void tearDown() {
    ConversationState.setCurrent(null);
  }

  /**
   * A file node as Documents resolves it.
   *
   * @param name its name
   * @param size its declared size
   * @param mimeType its content type
   * @return the node
   */
  private static FileNode file(String name, long size, String mimeType) {
    FileNode node = new FileNode();
    node.setId(DOCUMENT_ID);
    node.setName(name);
    node.setSize(size);
    node.setMimeType(mimeType);
    return node;
  }

  /**
   * A document's content as Documents reads it.
   *
   * @param title its title
   * @param mimeType its content type
   * @param bytes its bytes
   * @return the content
   */
  private static FileContent content(String title, String mimeType, byte[] bytes) {
    return new FileContent(DOCUMENT_ID, title, mimeType, new ByteArrayInputStream(bytes), new Date());
  }

  /**
   * The reason a read is refused with.
   *
   * @param maxSize the size given to the read
   * @return the refusal
   */
  private DocumentRefusedException refusal(long maxSize) {
    return assertThrows(DocumentRefusedException.class, () -> reader.readDocument(DOCUMENT_ID, USERNAME, maxSize));
  }

  /**
   * A document is resolved and read AS THE USER, and its title, content type and bytes
   * are what is attached: the ACL check is Documents' own, made for this user.
   *
   * @throws Exception never
   */
  @Test
  void aDocumentIsReadAsTheUserWithItsTitleTypeAndBytes() throws Exception {
    byte[] bytes = "the brochure".getBytes();
    when(documentFileService.getDocumentById(DOCUMENT_ID, USERNAME)).thenReturn(file("brochure", bytes.length, "application/pdf"));
    when(documentFileService.getDocumentContent(DOCUMENT_ID, USERNAME)).thenReturn(content("brochure.pdf", "application/pdf", bytes));

    EmailDocumentContent document = reader.readDocument(DOCUMENT_ID, USERNAME, 1024);

    assertEquals("brochure.pdf", document.getName());
    assertEquals("application/pdf", document.getMimeType());
    assertArrayEquals(bytes, document.getContent());
    verify(documentFileService).getDocumentById(DOCUMENT_ID, USERNAME);
    verify(documentFileService).getDocumentContent(DOCUMENT_ID, USERNAME);
  }

  /**
   * A document without a content type is attached as an octet stream, and one without
   * a title under its node name.
   *
   * @throws Exception never
   */
  @Test
  void aDocumentWithoutTitleOrTypeFallsBackToItsNodeNameAndAnOctetStream() throws Exception {
    when(documentFileService.getDocumentById(DOCUMENT_ID, USERNAME)).thenReturn(file("notes.bin", 3, null));
    when(documentFileService.getDocumentContent(DOCUMENT_ID, USERNAME)).thenReturn(content(null, "", "abc".getBytes()));

    EmailDocumentContent document = reader.readDocument(DOCUMENT_ID, USERNAME, 1024);

    assertEquals("notes.bin", document.getName());
    assertEquals("application/octet-stream", document.getMimeType());
  }

  /**
   * A document the user may not open is refused as such, and its content is never read.
   *
   * @throws Exception never
   */
  @Test
  void aDocumentTheUserCannotOpenIsRefusedWithoutBeingRead() throws Exception {
    when(documentFileService.getDocumentById(DOCUMENT_ID, USERNAME)).thenThrow(new IllegalAccessException("no"));

    assertEquals(DocumentRefusalReason.NO_ACCESS, refusal(1024).getReason());
    verify(documentFileService, never()).getDocumentContent(anyString(), anyString());
  }

  /**
   * An unknown id is refused as not found, whether Documents throws or answers nothing.
   *
   * @throws Exception never
   */
  @Test
  void anUnknownDocumentIsRefusedAsNotFound() throws Exception {
    when(documentFileService.getDocumentById(DOCUMENT_ID, USERNAME)).thenThrow(new ObjectNotFoundException("gone"));
    assertEquals(DocumentRefusalReason.NOT_FOUND, refusal(1024).getReason());

    doReturn(null).when(documentFileService).getDocumentById(DOCUMENT_ID, USERNAME);
    assertEquals(DocumentRefusalReason.NOT_FOUND, refusal(1024).getReason());
  }

  /**
   * A folder is refused, named, and nothing of it is read.
   *
   * @throws Exception never
   */
  @Test
  void aFolderIsRefusedAsNotAFile() throws Exception {
    FolderNode folder = new FolderNode();
    folder.setName("Campaign");
    when(documentFileService.getDocumentById(DOCUMENT_ID, USERNAME)).thenReturn(folder);

    DocumentRefusedException refusal = refusal(1024);

    assertEquals(DocumentRefusalReason.NOT_A_FILE, refusal.getReason());
    assertEquals("Campaign", refusal.getDocumentName());
    verify(documentFileService, never()).getDocumentContent(anyString(), anyString());
  }

  /**
   * A file declared larger than the room left is refused before its content is read;
   * one declared exactly that size is read.
   *
   * @throws Exception never
   */
  @Test
  void aFileDeclaredLargerThanTheRoomLeftIsRefusedBeforeItIsRead() throws Exception {
    when(documentFileService.getDocumentById(DOCUMENT_ID, USERNAME)).thenReturn(file("video.mp4", 11, "video/mp4"));

    DocumentRefusedException refusal = refusal(10);

    assertEquals(DocumentRefusalReason.TOO_LARGE, refusal.getReason());
    assertEquals("video.mp4", refusal.getDocumentName());
    verify(documentFileService, never()).getDocumentContent(anyString(), anyString());

    when(documentFileService.getDocumentContent(DOCUMENT_ID, USERNAME)).thenReturn(content("video.mp4", "video/mp4", new byte[11]));
    assertEquals(11, reader.readDocument(DOCUMENT_ID, USERNAME, 11).getContent().length);
  }

  /**
   * A file whose declared size says nothing -- a link to another file declares none --
   * is still refused as soon as its content passes the room left.
   *
   * @throws Exception never
   */
  @Test
  void aFileWhoseContentPassesTheRoomLeftIsRefusedWhateverItDeclares() throws Exception {
    when(documentFileService.getDocumentById(DOCUMENT_ID, USERNAME)).thenReturn(file("link.pdf", 0, "application/pdf"));
    when(documentFileService.getDocumentContent(DOCUMENT_ID, USERNAME)).thenReturn(content("link.pdf",
                                                                                           "application/pdf",
                                                                                           new byte[20_000]));

    assertEquals(DocumentRefusalReason.TOO_LARGE, refusal(19_999).getReason());
  }

  /**
   * A content Documents cannot read is refused as unreadable, named.
   *
   * @throws Exception never
   */
  @Test
  void aContentDocumentsCannotReadIsRefusedAsUnreadable() throws Exception {
    when(documentFileService.getDocumentById(DOCUMENT_ID, USERNAME)).thenReturn(file("deck.pptx", 5, null));
    when(documentFileService.getDocumentContent(DOCUMENT_ID, USERNAME)).thenThrow(new IllegalStateException("jcr"));

    DocumentRefusedException refusal = refusal(1024);

    assertEquals(DocumentRefusalReason.UNREADABLE, refusal.getReason());
    assertEquals("deck.pptx", refusal.getDocumentName());
  }

  /**
   * A failure of Documents while the content is read is this document's: refused as
   * unreadable, never thrown, so the documents after it are still read.
   *
   * @throws Exception never
   */
  @Test
  void aFailureWhileReadingTheContentIsRefusedAsUnreadable() throws Exception {
    when(documentFileService.getDocumentById(DOCUMENT_ID, USERNAME)).thenReturn(file("deck.pptx", 5, null));
    java.io.InputStream failing = new java.io.InputStream() {
      @Override
      public int read() {
        throw new IllegalStateException("jcr stream");
      }
    };
    when(documentFileService.getDocumentContent(DOCUMENT_ID, USERNAME)).thenReturn(new FileContent(DOCUMENT_ID, "deck.pptx", null,
                                                                                                    failing, new Date()));

    assertEquals(DocumentRefusalReason.UNREADABLE, refusal(1024).getReason());

    when(provider.getIfAvailable()).thenThrow(new IllegalStateException("context closing"));
    assertEquals(DocumentRefusalReason.UNREADABLE, refusal(1024).getReason());
  }

  /**
   * Documents reads as the thread's current user, never as the name it is given, so a
   * read for a user who is not the thread's current one -- or on a thread with none -- is
   * refused before Documents is asked anything.
   *
   * @throws Exception never
   */
  @Test
  void aReadForAnotherUserThanTheThreadsIsRefusedBeforeDocumentsIsAsked() throws Exception {
    ConversationState.setCurrent(new ConversationState(new Identity("someone-else")));
    assertEquals(DocumentRefusalReason.UNREADABLE, refusal(1024).getReason());

    ConversationState.setCurrent(null);
    assertEquals(DocumentRefusalReason.UNREADABLE, refusal(1024).getReason());
    verify(documentFileService, never()).getDocumentById(anyString(), anyString());
  }

  /**
   * Without the Documents service, every document is refused as unavailable.
   */
  @Test
  void withoutTheDocumentsServiceEveryDocumentIsUnavailable() {
    when(provider.getIfAvailable()).thenReturn(null);

    DocumentRefusedException refusal = refusal(1024);

    assertEquals(DocumentRefusalReason.DOCUMENTS_UNAVAILABLE, refusal.getReason());
    assertNull(refusal.getDocumentName());
  }

  /**
   * The bean exists only where the Documents API does: its condition is the class, so
   * the Email app starts without the Documents add-on. Pins the annotation-removed
   * mutant, which no call of the reader can catch.
   */
  @Test
  void theReaderIsConditionalOnTheDocumentsApi() {
    ConditionalOnClass condition = DocumentsEmailDocumentReader.class.getAnnotation(ConditionalOnClass.class);
    assertTrue(condition != null && java.util.Arrays.asList(condition.value()).contains(DocumentFileService.class));
  }
}
