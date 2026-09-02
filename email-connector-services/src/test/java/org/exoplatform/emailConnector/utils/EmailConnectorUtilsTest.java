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
package org.exoplatform.emailConnector.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Properties;

import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.model.EmailContent;

import lombok.SneakyThrows;

@ExtendWith(MockitoExtension.class)
public class EmailConnectorUtilsTest {

  @Test
  @SneakyThrows
  public void getMessageContent() {
    Message message = mock(Message.class);
    when(message.isMimeType("text/*")).thenReturn(true);
    when(message.getContent()).thenReturn("test message");
    EmailContent emailContent = EmailConnectorUtils.getMessageContent(1L, message);
    assertEquals("test message", emailContent.getBody());
    assertFalse(emailContent.isHtml());
    when(message.isMimeType("text/*")).thenReturn(false);
    MimeMultipart mimeMultipart = mock(MimeMultipart.class);
    when(message.getContent()).thenReturn(mimeMultipart);
    when(mimeMultipart.getCount()).thenReturn(1);
    BodyPart bodyPart =  mock(BodyPart.class);
    when(mimeMultipart.getBodyPart(0)).thenReturn(bodyPart);
    when(bodyPart.isMimeType("text/html")).thenReturn(true);
    when(bodyPart.getContent()).thenReturn("<span>test message</span>");
    emailContent = EmailConnectorUtils.getMessageContent(1L, message);
    assertEquals("<span>test message</span>", emailContent.getBody());
    when(bodyPart.isMimeType("text/html")).thenReturn(false);
    when(bodyPart.isMimeType("text/plain")).thenReturn(true);
    when(bodyPart.getContent()).thenReturn("test message");
    emailContent = EmailConnectorUtils.getMessageContent(1L, message);
    assertEquals("test message", emailContent.getBody());
  }

  /**
   * A single-part text/plain message: the body is the text, and it says so.
   *
   * @throws Exception when the message cannot be assembled
   */
  @Test
  public void plainTextMessageIsNotHtml() throws Exception {
    MimeMessage message = textMessage("Hi,\n\nSee you Monday.\n\n-- \nBob", "plain");
    EmailContent content = EmailConnectorUtils.getMessageContent(1L, message);
    assertEquals("Hi,\n\nSee you Monday.\n\n-- \nBob", content.getBody());
    assertFalse(content.isHtml());
  }

  /**
   * A single-part text/html message.
   *
   * @throws Exception when the message cannot be assembled
   */
  @Test
  public void htmlMessageIsHtml() throws Exception {
    MimeMessage message = textMessage("<div dir=\"ltr\">Hello</div>", "html");
    EmailContent content = EmailConnectorUtils.getMessageContent(1L, message);
    assertEquals("<div dir=\"ltr\">Hello</div>", content.getBody());
    assertTrue(content.isHtml());
  }

  /**
   * The everyday multipart/alternative: both alternatives are offered, the HTML one is
   * the one to show, and nothing of the plain-text one leaks into the body.
   *
   * @throws Exception when the message cannot be assembled
   */
  @Test
  public void alternativeKeepsTheHtmlPartAndSaysSo() throws Exception {
    MimeMultipart alternative = multipart("alternative", part("plain version", "plain"), part("<p>html version</p>", "html"));
    EmailContent content = EmailConnectorUtils.getMessageContent(1L, message(alternative));
    assertEquals("<p>html version</p>", content.getBody());
    assertTrue(content.isHtml());
  }

  /**
   * The same alternative one level down, under a multipart/mixed — what a message with
   * an attachment looks like. The recursion used to return the assembled body unflagged,
   * so the caller was told this was plain text.
   *
   * @throws Exception when the message cannot be assembled
   */
  @Test
  public void nestedAlternativeUnderMixedIsStillHtml() throws Exception {
    MimeBodyPart alternativePart = new MimeBodyPart();
    alternativePart.setContent(multipart("alternative", part("plain version", "plain"), part("<p>html version</p>", "html")));
    EmailContent content = EmailConnectorUtils.getMessageContent(1L, message(multipart("mixed", alternativePart)));
    assertEquals("<p>html version</p>", content.getBody());
    assertTrue(content.isHtml());
  }

  /**
   * The shape that made the reader show the message twice: a multipart/alternative whose
   * rich branch is a multipart/related (the wrapper a mail with inline images arrives in).
   * The nested branch came back unflagged, so it was filed as a second plain-text
   * alternative and appended to the first — the reader was handed the plain-text version
   * immediately followed by the HTML one.
   *
   * @throws Exception when the message cannot be assembled
   */
  @Test
  public void relatedBranchOfAnAlternativeDoesNotGetAppendedToThePlainText() throws Exception {
    MimeBodyPart relatedPart = new MimeBodyPart();
    relatedPart.setContent(multipart("related", part("<p>html version</p>", "html")));
    EmailContent content = EmailConnectorUtils.getMessageContent(1L,
                                                                message(multipart("alternative",
                                                                                  part("plain version", "plain"),
                                                                                  relatedPart)));
    assertEquals("<p>html version</p>", content.getBody());
    assertTrue(content.isHtml());
  }

  /**
   * A message offering only the plain-text alternative keeps saying it is plain text.
   *
   * @throws Exception when the message cannot be assembled
   */
  @Test
  public void alternativeWithoutAnHtmlPartStaysPlainText() throws Exception {
    EmailContent content = EmailConnectorUtils.getMessageContent(1L, message(multipart("alternative", part("only text", "plain"))));
    assertEquals("only text", content.getBody());
    assertFalse(content.isHtml());
  }

  /**
   * The legacy fallback, over the cases the browser used to decide in front of the
   * reader. It has to answer them the same way, since it is what rows cached before the
   * column existed are rendered from.
   */
  @Test
  public void looksLikeHtmlAnswersTheSniffCasesTheBrowserUsedTo() {
    // Plain text, whatever angle brackets it happens to contain.
    assertFalse(EmailConnectorUtils.looksLikeHtml("Hello,\n\nLine two\n  indented\n\n-- \nBob"));
    assertFalse(EmailConnectorUtils.looksLikeHtml("if a < b then stop\nand c > d"));
    assertFalse(EmailConnectorUtils.looksLikeHtml("Write to me <bob@example.com>\nthanks"));
    assertFalse(EmailConnectorUtils.looksLikeHtml("Yes.\n\n> On Monday you wrote:\n> the old text\n> more"));
    // A tag no HTML vocabulary knows is not markup either -- Word's <o:p> among them.
    assertFalse(EmailConnectorUtils.looksLikeHtml("<o:p></o:p>"));
    assertFalse(EmailConnectorUtils.looksLikeHtml(""));
    assertFalse(EmailConnectorUtils.looksLikeHtml(null));
    // Markup, down to a single tag.
    assertTrue(EmailConnectorUtils.looksLikeHtml("<p>Hello</p><p>World</p>"));
    assertTrue(EmailConnectorUtils.looksLikeHtml("Hello<br>World"));
    assertTrue(EmailConnectorUtils.looksLikeHtml("<div dir=\"ltr\">Hi there</div>"));
    // Somebody typing a tag name into a plain-text mail reads as HTML, here as in every
    // mail client: the safe direction to be wrong in, since it renders as it always did.
    assertTrue(EmailConnectorUtils.looksLikeHtml("The tag <script>alert(1)</script> is text here"));
  }

  /**
   * Builds a real single-part text MIME message.
   *
   * @param body the message's text
   * @param subtype the text subtype (plain or html)
   * @return a saved message ready to be read back
   * @throws Exception when the message cannot be assembled
   */
  private MimeMessage textMessage(String body, String subtype) throws Exception {
    MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
    message.setText(body, "UTF-8", subtype);
    message.saveChanges();
    return message;
  }

  /**
   * Builds a real multipart MIME message.
   *
   * @param multipart the message's content
   * @return a saved message ready to be read back
   * @throws Exception when the message cannot be assembled
   */
  private MimeMessage message(MimeMultipart multipart) throws Exception {
    MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
    message.setContent(multipart);
    message.saveChanges();
    return message;
  }

  /**
   * Builds a real multipart of the given subtype.
   *
   * @param subtype the multipart subtype (alternative, mixed, related)
   * @param parts the parts, in order
   * @return the assembled multipart
   * @throws Exception when a part cannot be added
   */
  private MimeMultipart multipart(String subtype, MimeBodyPart... parts) throws Exception {
    MimeMultipart multipart = new MimeMultipart(subtype);
    for (MimeBodyPart part : parts) {
      multipart.addBodyPart(part);
    }
    return multipart;
  }

  /**
   * Builds a real text body part.
   *
   * @param body the part's text
   * @param subtype the text subtype (plain or html)
   * @return the assembled part
   * @throws Exception when the part cannot be written
   */
  private MimeBodyPart part(String body, String subtype) throws Exception {
    MimeBodyPart part = new MimeBodyPart();
    part.setText(body, "UTF-8", subtype);
    return part;
  }

}
