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
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.activation.DataHandler;
import javax.imageio.ImageIO;
import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Part;
import javax.mail.internet.ContentType;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.portal.application.PortalRequestContext;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.portal.webui.util.Util;
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

  public static final String   EMAIL_BOX_SYNC_JOB_NAME = "EmailBoxSyncJob";

  // Chosen from measurements on a real mailbox at 500, 1000 and 5000 messages. Every size
  // works and the cost is linear, so the number is a trade rather than a limit: at a
  // thousand a full reset takes about five minutes, a sync that finds nothing new costs two
  // or three seconds, and the cached bodies come to some sixty megabytes per user. Five
  // thousand is supported and stays available to administrators, but it multiplies all
  // three -- and the routine sync cost is paid by every user on every period, so it is the
  // one that adds up on a busy server rather than the reset anybody actually notices.
  public static final int      DEFAULT_EMAIL_BOX_CACHE_SIZE =
                                          Integer.parseInt(System.getProperty("email.connector.sync.emails.number", "1000"));

  public static final String   OPEN_EMAIL              = "exo.email.openEmail";

  public static final String   SEND_EMAIL              = "exo.email.sendEmail";

  public static final String   ACCESS_WEBMAIL          = "exo.email.accessWebmail";

  // Broadcast after a sync when new emails were fetched: source = username,
  // data = the new emails' IMAP UIDs (mailRemoteIds). Lets other add-ons react
  // to freshly-arrived mail (e.g. the enterprise AI auto-categorization).
  // A large sync broadcasts this SEVERAL times, one group of messages at a time,
  // so a consumer can start working while the rest of the mailbox is still
  // downloading; NEW_EMAILS_SYNC_COMPLETED marks the end of the run.
  public static final String   NEW_EMAILS_SYNCED       = "exo.email.newEmailsSynced";

  // Broadcast once per inbox sync, after the last NEW_EMAILS_SYNCED group: source =
  // username, data = ALL the IMAP UIDs this sync cached. This is the "no more groups
  // are coming" signal a consumer needs for whole-run work -- per-message events can
  // never tell it when a conversation split across groups is finally complete.
  public static final String   NEW_EMAILS_SYNC_COMPLETED = "exo.email.newEmailsSyncCompleted";

  // Broadcast once the WHOLE run is over -- inbox, Sent and Archive -- rather than at
  // the end of the inbox alone: source = username, data = the UIDs the inbox cached.
  //
  // The distinction is not academic. Work that reads more than the inbox must not
  // start when NEW_EMAILS_SYNC_COMPLETED fires, because Sent is cached seconds later:
  // the contact backfill did exactly that, read an empty Sent folder on a first
  // connection, collected nobody, and marked itself permanently done. Not broadcast
  // for an inbox-only sync, which never caches Sent at all.
  public static final String   MAILBOX_SYNC_COMPLETED    = "exo.email.mailboxSyncCompleted";

  public static final String   EMAIL_FEATURE           = "email";

  private static final int     DEFAULT_AVATAR_WIDTH    = 350;

  private static final int     DEFAULT_AVATAR_HEIGHT   = 350;

  private static final Pattern MOJIBAKE_PATTERN        = Pattern.compile("[ÃÂâ][\u0080-\u00BF]");

  private static final Log     LOG                     = ExoLogger.getLogger(EmailConnectorUtils.class);

  /**
   * Extracts a message's displayable body and attachment descriptors, without
   * measuring anything — the historical entry point, kept for callers (and tests)
   * that do not care about the fetch count.
   *
   * @param messageUid the message's IMAP UID, stamped on attachment descriptors
   * @param message the live message
   * @return the extracted content, never null
   */
  public static EmailContent getMessageContent(long messageUid, Message message) {
    return getMessageContent(messageUid, message, null);
  }

  /**
   * Extracts a message's displayable body and attachment descriptors, counting the
   * MIME part bodies actually pulled from the server as it goes. Each leaf part read
   * here is its own {@code FETCH BODY[n]} round trip — the structure comes free with
   * the batched CONTENT_INFO fetch, but every text body and every inline {@code cid:}
   * image does not. The counter exists to answer one sizing question with data
   * instead of suspicion: whether deferring inline-image download to message-open is
   * worth building, which is exactly the ratio of parts fetched to messages fetched.
   *
   * @param messageUid the message's IMAP UID, stamped on attachment descriptors
   * @param message the live message
   * @param fetchedParts incremented once per part body pulled from the server; null
   *          when nobody is measuring
   * @return the extracted content, never null
   */
  @SneakyThrows
  public static EmailContent getMessageContent(long messageUid, Message message, LongAdder fetchedParts) {
    EmailContent content = new EmailContent("");
    try {
      if (message.isMimeType("text/*")) {
        countPartFetch(fetchedParts);
        content = safeGetContent(message);
      } else if (message.getContent() instanceof MimeMultipart) {
        content = getHtmlFromMimeMultipart(messageUid, (MimeMultipart) message.getContent(), null, fetchedParts);
      }
    } catch (Exception e) {
      LOG.warn("Error extracting content from message: From={}, Subject={}", message.getFrom()[0], message.getSubject(), e);
    }
    String bodyText = content.getBody() != null ? content.getBody().trim() : "";
    content.setBody(bodyText);
    return content;
  }

  /**
   * Records one MIME part body pulled from the server, when a measurement is running.
   * A {@link LongAdder} because the body prefetch workers extract content on their own
   * threads — the counter is shared memory, never a database touch, so worker purity
   * holds.
   *
   * @param fetchedParts the sync's counter, or null when nobody is measuring
   */
  private static void countPartFetch(LongAdder fetchedParts) {
    if (fetchedParts != null) {
      fetchedParts.increment();
    }
  }

  /** Nobody typed this: a newsletter, receipt, alert or automated report. */
  public static final String MAIL_TYPE_AUTOMATED = "automated";

  /** Relayed by a discussion list; usually written by a person, so judge the sender. */
  public static final String MAIL_TYPE_LIST      = "list";

  /** Mass distribution: marketing and newsletter blasts. */
  public static final String MAIL_TYPE_BULK      = "bulk";

  /** Written by a person straight to the recipient. */
  public static final String MAIL_TYPE_PERSONAL  = "personal";

  /**
   * How the message reached the mailbox, from the distribution headers captured at sync.
   * <p>
   * Order matters and is deliberate. {@code automated} is tested first, so a bot posting to a
   * mailing list is still machine mail. {@code list} requires a postable {@code List-Post}
   * alongside {@code List-Id}: discussion lists set both, marketing senders rarely set
   * List-Post, and some senders emit List-Id on plain marketing -- so List-Id alone cannot
   * separate a colleague writing to a group from a newsletter blast. Anything left carrying
   * only List-Unsubscribe is bulk distribution.
   * <p>
   * This is transport, not authorship: {@code list} means "a person probably wrote it, judge
   * the sender", NOT "this is personal mail". A newsletter relayed into a group is still
   * automated, which is exactly why {@code automated} outranks {@code list}.
   *
   * @param email the message to classify
   * @return one of {@link #MAIL_TYPE_AUTOMATED}, {@link #MAIL_TYPE_LIST},
   *         {@link #MAIL_TYPE_BULK} or {@link #MAIL_TYPE_PERSONAL}
   */
  public static String getMailType(Email email) {
    if (email == null) {
      return MAIL_TYPE_PERSONAL;
    }
    if (email.isAutoSubmitted()) {
      return MAIL_TYPE_AUTOMATED;
    }
    if (email.isHasListId() && email.isHasListPost()) {
      return MAIL_TYPE_LIST;
    }
    if (email.isHasListUnsubscribe() || email.isHasListId()) {
      return MAIL_TYPE_BULK;
    }
    return MAIL_TYPE_PERSONAL;
  }

  private static final Pattern FORWARD_SUBJECT = Pattern.compile("^\\s*(fw|fwd|tr|wg|rv)\\s*:", Pattern.CASE_INSENSITIVE);

  /**
   * Whether a person forwarded this message on rather than writing it themselves.
   * <p>
   * Worth knowing because a forward's visible content belongs to whoever wrote the original —
   * typically an automated receipt or booking confirmation — while the act that matters is a
   * person choosing to send it to the recipient. A consumer reading only the body sees the
   * machine text and misjudges the message.
   *
   * @param email the message to inspect
   * @return {@code true} when the subject carries a forward marker
   */
  public static boolean isForward(Email email) {
    return email != null && email.getSubject() != null && FORWARD_SUBJECT.matcher(email.getSubject()).find();
  }

  /**
   * The address of whoever actually wrote the message, which differs from the sender only when
   * a mailing list rewrote {@code From} to itself. Falls back to {@code Reply-To}, which lists
   * generally point at the author, and finally to nothing when the sender is already the
   * author.
   *
   * @param email the message to inspect
   * @return the original author's address, or {@code null} when the sender is the author
   */
  public static String getOriginalSender(Email email) {
    if (email == null) {
      return null;
    }
    if (StringUtils.isNotBlank(email.getOriginalSender())) {
      return email.getOriginalSender();
    }
    if (email.getReplyTo() != null && !email.getReplyTo().isEmpty() && email.getReplyTo().get(0) != null) {
      String replyTo = email.getReplyTo().get(0).getAddress();
      String sender = email.getSender() == null ? null : email.getSender().getAddress();
      if (StringUtils.isNotBlank(replyTo) && !StringUtils.equalsIgnoreCase(replyTo, sender)) {
        return replyTo;
      }
    }
    return null;
  }

  public static int getEmailBoxUserSyncPeriod(UserEmailSetting userEmailSetting) {
    return (userEmailSetting.getEmailBoxUserSyncPeriod() != null ? userEmailSetting.getEmailBoxUserSyncPeriod()
                                                                 : Integer.parseInt(System.getProperty("email.connector.sync.user.minute.period",
                                                                                                       "10")));
  }

  public static List<EmailRecipient> getEmailRecipients(Address[] messageRecipients, String username, boolean withProfile) {
    if (messageRecipients == null) {
      return Collections.emptyList();
    }
    return Arrays.stream(messageRecipients).filter(a -> a instanceof InternetAddress).map(a -> {
      InternetAddress ia = (InternetAddress) a;
      String profileUrl = null;
      String profileName = null;
      boolean isCurrentUser = false;
      if (username != null && withProfile) {
        Profile userProfile = getUserProfileByEmail(ia.getAddress());
        if (userProfile != null) {
          profileUrl = userProfile.getUrl();
          profileName = userProfile.getFullName();
          isCurrentUser = userProfile.getIdentity().getRemoteId().equals(username);
        }
      }
      return new EmailRecipient(displayNameOf(ia.getPersonal(), ia.getAddress(), profileName),
                                ia.getAddress(),
                                profileUrl,
                                isCurrentUser);
    }).collect(Collectors.toList());
  }

  /**
   * What to call the person on this address.
   * <p>
   * The name the sender wrote wins, because it is what they chose to be called.
   * When the header carries none, a mail client shows the raw address — and for
   * somebody the platform knows that is doubly poor: the address then appears
   * twice on the row, once as the link and once beside it, while their real name
   * is a lookup away and already resolved here.
   *
   * @param headerName the personal part of the address, often null
   * @param address the address itself
   * @param profileName the platform profile's full name, or null when the
   *          address belongs to nobody here
   * @return the name to show
   */
  private static String displayNameOf(String headerName, String address, String profileName) {
    if (StringUtils.isNotBlank(headerName)) {
      return decodeHeader(headerName);
    }
    return StringUtils.isNotBlank(profileName) ? profileName : address;
  }

  public static EmailSender getEmailSender(Address messageSenderAddress, boolean withProfile) {
    if (messageSenderAddress instanceof InternetAddress internetAddress) {
      String avatarUrl = null;
      String profileUrl = null;
      String senderName = internetAddress.getPersonal() != null ? decodeHeader(internetAddress.getPersonal())
                                                                : internetAddress.getAddress();
      if (withProfile) {
        Profile userProfile = getUserProfileByEmail(internetAddress.getAddress());
        if (userProfile != null) {
          avatarUrl = userProfile.getAvatarUrl();
          profileUrl = userProfile.getUrl();
          senderName = displayNameOf(internetAddress.getPersonal(), internetAddress.getAddress(), userProfile.getFullName());
        } else {
          avatarUrl = getSenderDefaultAvatar(senderName);
        }
      }
      return new EmailSender(senderName, internetAddress.getAddress(), avatarUrl, profileUrl);
    }
    return null;
  }

  public static String getEmailsLink(String username) {
    String defaultPortalOwner = "";
    PortalRequestContext pContext = null;
    try {
      pContext = Util.getPortalRequestContext();
    } catch (NullPointerException e) {
      pContext = null;
    }
    if (pContext != null) {
      defaultPortalOwner = pContext.getPortalOwner();
    } else {
      UserPortalConfigService portalConfig = CommonsUtils.getService(UserPortalConfigService.class);
      defaultPortalOwner = portalConfig == null ? null : portalConfig.getDefaultSite(username).getName();
    }
    return "/portal/" + defaultPortalOwner + "?openEmailBox=true";
  }

  public static Profile getUserProfileByEmail(String email) {
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

  private static EmailContent safeGetContent(Part part) {
    String emailBody = "";
    try {
      Object content = part.getContent();
      if (content instanceof String) {
        emailBody = (String) content;
      }
      if (content instanceof InputStream) {
        emailBody = new String(((InputStream) content).readAllBytes(), StandardCharsets.UTF_8);
      }
      EmailContent emailContent = new EmailContent(emailBody);
      emailContent.setHtml(part.isMimeType("text/html"));
      return emailContent;
    } catch (Exception e) {
      EmailContent emailContent = new EmailContent(emailBody);
      emailContent.setHtml(false);
      return emailContent;
    }
  }

  /**
   * Walks a multipart body, assembling the displayable HTML (or plain-text
   * fallback), inlining {@code cid:} images as data URLs and describing the
   * attachments. Navigating the multipart tree is free — the structure was fetched
   * with CONTENT_INFO — but each leaf body read (text parts, inline images) is its
   * own server round trip, which is why those reads and only those reads tick
   * {@code fetchedParts}.
   *
   * @param messageUid the message's IMAP UID, stamped on attachment descriptors
   * @param mimeMultipart the (sub-)tree to walk
   * @param parentPartNumber the IMAP section prefix of this subtree, null at the root
   * @param fetchedParts incremented once per part body pulled, null when not measuring
   * @return the assembled content, never null
   * @throws MessagingException if the structure cannot be read
   * @throws IOException if a part's content cannot be read
   */
  private static EmailContent getHtmlFromMimeMultipart(long messageUid,
                                                       MimeMultipart mimeMultipart,
                                                       String parentPartNumber,
                                                       LongAdder fetchedParts) throws MessagingException, IOException {
    EmailContent htmlContent = null;
    EmailContent plainContent = null;
    Map<String, String> cidImageMap = new HashMap<>();
    EmailContent finalContent = new EmailContent("");
    for (int i = 0; i < mimeMultipart.getCount(); i++) {
      BodyPart bodyPart = mimeMultipart.getBodyPart(i);
      String disposition = bodyPart.getDisposition();
      String partNumber = (parentPartNumber == null ? "" : parentPartNumber + ".") + (i + 1);
      if (bodyPart.isMimeType("text/html") && htmlContent == null) {
        countPartFetch(fetchedParts);
        htmlContent = safeGetContent(bodyPart);
      } else if (bodyPart.isMimeType("text/plain") && plainContent == null) {
        countPartFetch(fetchedParts);
        plainContent = safeGetContent(bodyPart);
      } else if (bodyPart.getContent() instanceof MimeMultipart) {
        EmailContent nested = getHtmlFromMimeMultipart(messageUid, (MimeMultipart) bodyPart.getContent(), partNumber, fetchedParts);
        if (nested != null && !nested.getBody().isEmpty()) {
          if (nested.isHtml()) {
            if (htmlContent == null) {
              htmlContent = new EmailContent("");
              htmlContent.setHtml(true);
            }
            htmlContent.setBody(htmlContent.getBody() + nested.getBody());
          } else {
            if (plainContent == null) {
              plainContent = new EmailContent("");
              plainContent.setHtml(false);
            }
            plainContent.setBody(plainContent.getBody() + nested.getBody());
          }
          if (nested.getAttachments() != null) {
            if (finalContent.getAttachments() == null) {
              finalContent.setAttachments(new ArrayList<>());
            }
            finalContent.getAttachments().addAll(nested.getAttachments());
          }
        }
      } else if (bodyPart.isMimeType("image/*") && Part.INLINE.equalsIgnoreCase(disposition)) {
        String cid = getCid(bodyPart);
        if (cid != null) {
          countPartFetch(fetchedParts);
          cidImageMap.put(cid, encodeToBase64DataUrl(bodyPart));
        }
      } else if (disposition == null || Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
        if (finalContent.getAttachments() == null) {
          finalContent.setAttachments(new ArrayList<>());
        }
        EmailAttachment emailAttachment = new EmailAttachment();
        emailAttachment.setName(bodyPart.getFileName());
        emailAttachment.setMimeType(new ContentType(bodyPart.getContentType()).getBaseType());
        emailAttachment.setAttachmentRemoteId(partNumber);
        emailAttachment.setMailRemoteId(messageUid);
        finalContent.getAttachments().add(emailAttachment);
      }
    }
    if (htmlContent != null) {
      finalContent.setBody(htmlContent.getBody());
    } else if (plainContent != null) {
      finalContent.setBody(plainContent.getBody());
    }
    for (Map.Entry<String, String> entry : cidImageMap.entrySet()) {
      finalContent.setBody(finalContent.getBody().replace("cid:" + entry.getKey(), entry.getValue()));
    }
    return finalContent;
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

  private static String decodeHeader(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      String decoded = MimeUtility.decodeText(raw);
      if (!MOJIBAKE_PATTERN.matcher(decoded).find()) {
        return decoded;
      }
      String reencoded = new String(decoded.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
      return reencoded.equals(decoded) ? decoded : reencoded;
    } catch (UnsupportedEncodingException e) {
      return raw;
    }
  }
}
