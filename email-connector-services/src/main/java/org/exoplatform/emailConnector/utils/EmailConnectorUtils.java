package org.exoplatform.emailConnector.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.Part;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.jsoup.Jsoup;

import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.Query;
import org.exoplatform.services.organization.User;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;

import lombok.SneakyThrows;

public class EmailConnectorUtils {

  public static final String EMAIL_BOX_SYNC_JOB_NAME = "EmailBoxSyncJob";

  public static final int    MAX_EMAILS              =
                                        Integer.parseInt(System.getProperty("email.connector.sync.emails.number", "100"));

  public static final String EMAIL_FEATURE           = "email";

  private static final Log   LOG                     = ExoLogger.getLogger(EmailConnectorUtils.class);

  @SneakyThrows
  public static String getMessageContent(MimeMessage message, boolean excerpt) {
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

  public static List<EmailRecipient> getEmailRecipients(Address[] messageRecipients, String username) {
    if (messageRecipients == null) {
      return Collections.emptyList();
    }
    return Arrays.stream(messageRecipients).filter(a -> a instanceof InternetAddress).map(a -> {
      InternetAddress ia = (InternetAddress) a;
      Profile userProfile = getUserProfileByEmail(ia.getAddress());
      String profileUrl = null;
      boolean isCurrentUser = false;
      if (userProfile != null) {
        profileUrl = userProfile.getUrl();
        isCurrentUser = userProfile.getIdentity().getRemoteId().equals(username);
      }
      return new EmailRecipient(ia.getPersonal() != null ? ia.getPersonal() : ia.getAddress(),
                                ia.getAddress(),
                                profileUrl,
                                isCurrentUser);
    }).collect(Collectors.toList());
  }

  public static EmailSender getEmailSender(Address[] messageSender) {
    if (messageSender == null || messageSender.length == 0) {
      return null;
    }
    Address a = messageSender[0];
    if (a instanceof InternetAddress ia) {
      Profile userProfile = getUserProfileByEmail(ia.getAddress());
      String avatarUrl = null;
      String profileUrl = null;
      if (userProfile != null) {
        avatarUrl = userProfile.getAvatarUrl();
        profileUrl = userProfile.getUrl();
      }
      return new EmailSender(ia.getPersonal() != null ? ia.getPersonal() : ia.getAddress(),
                             ia.getAddress(),
                             avatarUrl,
                             profileUrl);
    }
    return null;
  }

  private static String safeGetContent(Part part) {
    try {
      Object content = part.getContent();
      if (content instanceof String) {
        return (String) content;
      }
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

  private static Profile getUserProfileByEmail(String email) {
    if (email == null) {
      return null;
    }
    RequestLifeCycle.begin(ExoContainerContext.getCurrentContainer());
    try {
      Query query = new Query();
      query.setEmail(email);
      OrganizationService organizationService = CommonsUtils.getOrganizationService();
      User[] users = organizationService.getUserHandler().findUsersByQuery(query).load(0, 10);
      if (users.length > 0) {
        String userName = users[0].getUserName();
        if (userName != null) {
          IdentityManager identityManager = CommonsUtils.getService(IdentityManager.class);
          Identity userIdentity = identityManager.getOrCreateUserIdentity(userName);
          return userIdentity.getProfile();
        }
      }
      return null;
    } catch (Exception e) {
      return null;
    } finally {
      RequestLifeCycle.end();
    }
  }
}
