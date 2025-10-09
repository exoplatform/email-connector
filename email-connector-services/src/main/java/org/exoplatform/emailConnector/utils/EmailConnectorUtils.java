package org.exoplatform.emailConnector.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Part;
import javax.mail.internet.MimeMultipart;

import org.jsoup.Jsoup;

import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import lombok.SneakyThrows;

public class EmailConnectorUtils {

  public static final String EMAIL_BOX_SYNC_JOB_NAME = "EmailBoxSyncJob";

  public static final int    maxEmails               =
                                       Integer.parseInt(System.getProperty("email.connector.sync.emails.number", "100"));

  public static final String EMAIL_FEATURE           = "email";

  private static final Log   LOG                     = ExoLogger.getLogger(EmailConnectorUtils.class);

  @SneakyThrows
  public static String getMessageContent(Message message, boolean excerpt) {
    String content = "";

    try {
      if (message.isMimeType("text/*")) {
        content = safeGetContent(message);
      } else if (message.isMimeType("multipart/*") || message.getContent() instanceof MimeMultipart) {
        content = getTextFromMimeMultipartSafe((MimeMultipart) message.getContent());
      }
    } catch (Exception e) {
      LOG.warn("Error extracting content from message: From={}, Subject={}", message.getFrom()[0], message.getSubject(), e);
    }

    if (excerpt) {
      content = Jsoup.parse(content).text().trim();
      return content.length() > 50 ? content.substring(0, 50) + "..." : content;
    }
    return content;
  }

  public static int getEmailBoxUserSyncPeriod(UserEmailSetting userEmailSetting) {
    return (userEmailSetting.getEmailBoxUserSyncPeriod() != null ? userEmailSetting.getEmailBoxUserSyncPeriod()
                                                                 : Integer.parseInt(System.getProperty("email.connector.sync.user.minute.period",
                                                                                                       "10")));
  }

  private static String safeGetContent(Part part) {
    try {
      Object content = part.getContent();
      if (content instanceof String)
        return (String) content;
      if (content instanceof InputStream) {
        return new String(((InputStream) content).readAllBytes(), StandardCharsets.UTF_8);
      }
      return "";
    } catch (IOException e) {
      if (e.getMessage().contains("Unknown encoding")) {
        try (InputStream is = part.getInputStream()) {
          return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
          return "";
        }
      }
      return "";
    } catch (MessagingException e) {
      return "";
    }
  }

  private static String getTextFromMimeMultipartSafe(MimeMultipart mimeMultipart) throws Exception {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < mimeMultipart.getCount(); i++) {
      BodyPart bodyPart = mimeMultipart.getBodyPart(i);
      if (bodyPart.isMimeType("text/*")) {
        result.append(safeGetContent(bodyPart));
      } else if (bodyPart.isMimeType("multipart/*") || bodyPart.getContent() instanceof MimeMultipart) {
        result.append(getTextFromMimeMultipartSafe((MimeMultipart) bodyPart.getContent()));
      }
    }
    return result.toString();
  }

}
