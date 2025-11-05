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

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.activation.DataHandler;
import javax.imageio.ImageIO;
import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.Part;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.apache.commons.lang3.StringUtils;
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

  public static final String OPEN_EMAIL              = "exo.email.openEmail";

  public static final String EMAIL_FEATURE           = "email";

  private static final int   DEFAULT_AVATAR_WIDTH    = 350;

  private static final int   DEFAULT_AVATAR_HEIGHT   = 350;

  private static final Log   LOG                     = ExoLogger.getLogger(EmailConnectorUtils.class);

  @SneakyThrows
  public static String getMessageContent(MimeMessage message, boolean excerpt) {
    String content = "";
    try {
      if (message.isMimeType("text/*")) {
        content = safeGetContent(message);
      } else if (message.isMimeType("multipart/*") || message.getContent() instanceof MimeMultipart) {
        content = getHtmlFromMimeMultipart((MimeMultipart) message.getContent());
      }
    } catch (Exception e) {
      LOG.warn("Error extracting content from message: From={}, Subject={}", message.getFrom()[0], message.getSubject(), e);
    }

    if (excerpt) {
      content = Jsoup.parse(content).text().trim();
      return content.length() > 50 ? content.substring(0, 50) + "..." : content;
    } else
      return content.trim();
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
      String senderName = ia.getPersonal() != null ? ia.getPersonal() : ia.getAddress();
      if (userProfile != null) {
        avatarUrl = userProfile.getAvatarUrl();
        profileUrl = userProfile.getUrl();
      } else {
        avatarUrl = getSenderDefaultAvatar(senderName);
      }
      return new EmailSender(senderName, ia.getAddress(), avatarUrl, profileUrl);
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

  private static String getHtmlFromMimeMultipart(MimeMultipart mimeMultipart) throws MessagingException, IOException {
    StringBuilder htmlContent = new StringBuilder();
    Map<String, String> cidImageMap = new HashMap<>();
    for (int i = 0; i < mimeMultipart.getCount(); i++) {
      BodyPart bodyPart = mimeMultipart.getBodyPart(i);
      if (bodyPart.isMimeType("text/html")) {
        htmlContent.append(safeGetContent(bodyPart));
      } else if (bodyPart.isMimeType("multipart/alternative") || bodyPart.getContent() instanceof MimeMultipart) {
        htmlContent.append(getHtmlFromMimeMultipart((MimeMultipart) bodyPart.getContent()));
      } else if (Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition()) || bodyPart.isMimeType("image/*")) {
        String cid = getCid(bodyPart);
        if (cid != null) {
          cidImageMap.put(cid, encodeToBase64DataUrl(bodyPart));
        }
      }
    }
    String finalHtml = htmlContent.toString();
    for (Map.Entry<String, String> entry : cidImageMap.entrySet()) {
      finalHtml = finalHtml.replace("cid:" + entry.getKey(), entry.getValue());
    }

    return finalHtml;
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

  private static String getSenderDefaultAvatar(String senderName) {
    String senderNameAbbreviation = Arrays.stream(senderName.split(" "))
                                          .filter(StringUtils::isNotBlank)
                                          .map(word -> word.substring(0, 1).toUpperCase())
                                          .limit(2)
                                          .collect(Collectors.joining());
    List<Color> colorList = List.of(new Color(239, 83, 80),
                                    new Color(25, 118, 210),
                                    new Color(171, 71, 188),
                                    new Color(0, 137, 123),
                                    new Color(158, 157, 36),
                                    new Color(251, 192, 45),
                                    new Color(0, 191, 165),
                                    new Color(117, 117, 117),
                                    new Color(244, 67, 54),
                                    new Color(33, 150, 243),
                                    new Color(124, 179, 66),
                                    new Color(48, 63, 159),
                                    new Color(69, 39, 160),
                                    new Color(141, 110, 99),
                                    new Color(255, 111, 0));
    BufferedImage image = new BufferedImage(DEFAULT_AVATAR_WIDTH, DEFAULT_AVATAR_HEIGHT, BufferedImage.TYPE_INT_RGB);

    Graphics2D graphics = image.createGraphics();
    graphics.setColor(colorList.get(senderName.length() % colorList.size()));
    graphics.fillRect(0, 0, DEFAULT_AVATAR_WIDTH, DEFAULT_AVATAR_HEIGHT);
    graphics.setColor(Color.WHITE);

    graphics.setFont(new Font("Arial", Font.BOLD, 150));
    FontMetrics fm = graphics.getFontMetrics();

    int x = (DEFAULT_AVATAR_WIDTH - fm.stringWidth(senderNameAbbreviation)) / 2;
    int y = (fm.getAscent() + (DEFAULT_AVATAR_HEIGHT - (fm.getAscent() + fm.getDescent())) / 2);

    graphics.drawString(senderNameAbbreviation, x, y);
    graphics.drawImage(image, 0, 0, DEFAULT_AVATAR_WIDTH, DEFAULT_AVATAR_HEIGHT, null);
    graphics.dispose();

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, "png", outputStream);
      String base64 = Base64.getEncoder().encodeToString(outputStream.toByteArray());
      return "data:image/png;base64," + base64;
    } catch (IOException e) {
      return null;
    }
  }

  private static String getCid(BodyPart bodyPart) throws MessagingException {
    String[] cids = bodyPart.getHeader("Content-ID");
    if (cids != null && cids.length > 0) {
      return cids[0].replaceAll("[<>]", "");
    }
    return null;
  }

  private static String encodeToBase64DataUrl(BodyPart bodyPart) {
    try {
        DataHandler handler = bodyPart.getDataHandler();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        handler.writeTo(os);
        byte[] bytes = os.toByteArray();
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String mimeType = bodyPart.getContentType().split(";")[0].trim();
        return "data:" + mimeType + ";base64," + base64;
    } catch (Exception e) {
        return "";
    }
}
}
