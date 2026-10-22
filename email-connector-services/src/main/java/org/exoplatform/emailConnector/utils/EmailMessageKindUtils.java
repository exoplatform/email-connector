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
package org.exoplatform.emailConnector.utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.internet.AddressException;
import javax.mail.internet.ContentType;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.InternetHeaders;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.emailConnector.constant.MessageKind;
import org.exoplatform.emailConnector.model.MessageClassification;

/**
 * Tells a bounce and an automatic reply from a message a person typed (EXO-91995),
 * from what the message declares about itself -- its headers and its MIME structure
 * -- never from its text, which depends on the language and the mail server.
 * <p>
 * The rules, in order:
 * <ol>
 * <li>A delivery status report (RFC 3464 {@code multipart/report;
 * report-type=delivery-status}, or its RFC 6533 {@code global-delivery-status} form)
 * is a {@link MessageKind#BOUNCE} when one of its recipients says
 * {@code Action: failed}; the failed addresses are their {@code Final-Recipient}, or
 * {@code Original-Recipient} when that one is missing. A report that failed nobody
 * (delayed, delivered, relayed) is an {@link MessageKind#AUTO_REPLY}.</li>
 * <li>Any other message carrying {@code X-Failed-Recipients} (Exim, Gmail) is a
 * {@link MessageKind#BOUNCE}, of the addresses that header names.</li>
 * <li>A read receipt (RFC 8098 {@code disposition-notification}), {@code Auto-Submitted}
 * other than {@code no} (RFC 3834), {@code X-Autoreply}, {@code X-Autorespond} or
 * {@code Precedence: auto_reply} make an {@link MessageKind#AUTO_REPLY}.</li>
 * <li>Anything else is {@link MessageKind#NORMAL}. {@code Precedence: list|bulk|junk}
 * and the {@code List-*} headers are deliberately not read: mailing lists stamp them on
 * the human messages they relay.</li>
 * </ol>
 * Only the report part of a delivery status report is ever downloaded, so classifying
 * a sync costs one extra fetch per such report and nothing for any other message --
 * provided the headers above are in the sync's batched fetch ({@link #HEADERS}).
 */
public final class EmailMessageKindUtils {

  /** RFC 3834: how a message was generated; {@code no} means a person wrote it. */
  public static final String       HEADER_AUTO_SUBMITTED       = "Auto-Submitted";

  /** The legacy precedence header; only its {@code auto_reply} value is read here. */
  public static final String       HEADER_PRECEDENCE           = "Precedence";

  /** The addresses an Exim or Gmail bounce could not reach. */
  public static final String       HEADER_X_FAILED_RECIPIENTS  = "X-Failed-Recipients";

  /** Set by several vacation responders. */
  public static final String       HEADER_X_AUTOREPLY          = "X-Autoreply";

  /** Set by several vacation responders. */
  public static final String       HEADER_X_AUTORESPOND        = "X-Autorespond";

  /** Every header {@link #classify} reads, to prefetch with the message. */
  public static final List<String> HEADERS                     = List.of(HEADER_AUTO_SUBMITTED,
                                                                         HEADER_PRECEDENCE,
                                                                         HEADER_X_FAILED_RECIPIENTS,
                                                                         HEADER_X_AUTOREPLY,
                                                                         HEADER_X_AUTORESPOND);

  /** The separator of the failed addresses as stored in one column. */
  public static final String       RECIPIENTS_SEPARATOR        = ", ";

  /**
   * The most read from one report part: a few hundred bytes per recipient are
   * expected, whatever the sender put in it.
   */
  private static final int         MAX_REPORT_BYTES            = 64 * 1024;

  /** The {@code report-type} values of a delivery status report, RFC 3464 and RFC 6533. */
  private static final Set<String> DELIVERY_STATUS_REPORTS     = Set.of("delivery-status", "global-delivery-status");

  /** The content types of a report's machine-readable status part. */
  private static final Set<String> DELIVERY_STATUS_PART_TYPES  = Set.of("message/delivery-status",
                                                                        "message/global-delivery-status");

  /** What separates the blocks of a status part. */
  private static final Pattern     BLANK_LINE                  = Pattern.compile("\\r?\\n[ \\t]*\\r?\\n");

  /** A utility class, never instantiated. */
  private EmailMessageKindUtils() {
  }

  /**
   * Classifies a received message by the rules of this class.
   *
   * @param message the message, with the {@link #HEADERS} and its content type
   *          preferably prefetched
   * @return the classification, or null when the message cannot be read: a kind
   *         nobody could decide is left unsaid rather than guessed
   */
  public static MessageClassification classify(Message message) {
    try {
      String failedHeader = firstHeader(message, HEADER_X_FAILED_RECIPIENTS);
      if (isReportOf(message, DELIVERY_STATUS_REPORTS)) {
        List<String> failed = failedRecipientsOfReport(message);
        if (failed != null) {
          return new MessageClassification(MessageKind.BOUNCE, failed);
        }
        return StringUtils.isBlank(failedHeader) ? MessageClassification.of(MessageKind.AUTO_REPLY)
                                                 : new MessageClassification(MessageKind.BOUNCE, addressesOf(failedHeader));
      }
      if (StringUtils.isNotBlank(failedHeader)) {
        return new MessageClassification(MessageKind.BOUNCE, addressesOf(failedHeader));
      }
      if (isReportOf(message, Set.of("disposition-notification")) || isAutomatic(message)) {
        return MessageClassification.of(MessageKind.AUTO_REPLY);
      }
      return MessageClassification.of(MessageKind.NORMAL);
    } catch (MessagingException | IOException | RuntimeException e) {
      return null;
    }
  }

  /**
   * The failed addresses of a bounce in the one-column form they are stored in, the
   * last ones left out whole when they do not all fit.
   *
   * @param recipients the addresses, possibly null
   * @param maxLength the column's width
   * @return the joined addresses, null when there is none
   */
  public static String joinRecipients(List<String> recipients, int maxLength) {
    if (recipients == null || recipients.isEmpty()) {
      return null;
    }
    StringBuilder joined = new StringBuilder();
    for (String recipient : recipients) {
      int length = joined.length() + (joined.length() == 0 ? 0 : RECIPIENTS_SEPARATOR.length()) + recipient.length();
      if (length > maxLength) {
        break;
      }
      if (joined.length() > 0) {
        joined.append(RECIPIENTS_SEPARATOR);
      }
      joined.append(recipient);
    }
    return joined.length() == 0 ? null : joined.toString();
  }

  /**
   * The failed addresses of a bounce, read back from their stored form.
   *
   * @param stored the column's value, possibly null
   * @return the addresses, empty when none is stored
   */
  public static List<String> splitRecipients(String stored) {
    if (StringUtils.isBlank(stored)) {
      return List.of();
    }
    return Arrays.stream(stored.split(",")).map(String::trim).filter(StringUtils::isNotEmpty).toList();
  }

  /**
   * Whether the message is a {@code multipart/report} of one of the given report types.
   *
   * @param message the message
   * @param reportTypes the accepted {@code report-type} values, lower case
   * @return true when it is
   * @throws MessagingException when its content type cannot be read
   */
  private static boolean isReportOf(Message message, Set<String> reportTypes) throws MessagingException {
    String contentType = message.getContentType();
    if (contentType == null) {
      return false;
    }
    ContentType type = new ContentType(contentType);
    String reportType = type.getParameter("report-type");
    return type.match("multipart/report") && reportType != null && reportTypes.contains(reportType.trim().toLowerCase(Locale.ROOT));
  }

  /**
   * Whether the headers say nobody typed the message: RFC 3834 {@code Auto-Submitted}
   * other than {@code no}, {@code X-Autoreply} or {@code X-Autorespond} other than
   * {@code no}, or {@code Precedence: auto_reply}.
   *
   * @param message the message
   * @return true for an automatic message
   * @throws MessagingException when the headers cannot be read
   */
  private static boolean isAutomatic(Message message) throws MessagingException {
    for (String header : List.of(HEADER_AUTO_SUBMITTED, HEADER_X_AUTOREPLY, HEADER_X_AUTORESPOND)) {
      String value = firstHeader(message, header);
      // Auto-Submitted may carry parameters: "auto-replied; owner-email=..."
      if (StringUtils.isNotBlank(value) && !"no".equalsIgnoreCase(StringUtils.substringBefore(value, ";").trim())) {
        return true;
      }
    }
    String precedence = firstHeader(message, HEADER_PRECEDENCE);
    return precedence != null && "auto_reply".equalsIgnoreCase(precedence.trim());
  }

  /**
   * The failed addresses a delivery status report names, read from its
   * {@code message/delivery-status} part.
   *
   * @param message the report
   * @return the failed addresses, possibly empty when a recipient failed with no
   *         readable address; null when no recipient failed
   * @throws MessagingException when the report cannot be read
   * @throws IOException when its status part cannot be downloaded
   */
  private static List<String> failedRecipientsOfReport(Message message) throws MessagingException, IOException {
    Object content = message.getContent();
    if (!(content instanceof Multipart multipart)) {
      return null;
    }
    for (int i = 0; i < multipart.getCount(); i++) {
      Part part = multipart.getBodyPart(i);
      if (isDeliveryStatusPart(part)) {
        try (InputStream in = part.getInputStream()) {
          return failedRecipientsOfStatus(new String(in.readNBytes(MAX_REPORT_BYTES), StandardCharsets.UTF_8));
        }
      }
    }
    return null;
  }

  /**
   * Whether a part of a report is its machine-readable delivery status.
   *
   * @param part the part
   * @return true for a {@code message/delivery-status} or
   *         {@code message/global-delivery-status} part
   * @throws MessagingException when its type cannot be read
   */
  private static boolean isDeliveryStatusPart(Part part) throws MessagingException {
    for (String type : DELIVERY_STATUS_PART_TYPES) {
      if (part.isMimeType(type)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The failed addresses of an RFC 3464 status: its per-message block, then one block
   * per recipient, blocks separated by a blank line.
   *
   * @param status the status part's text
   * @return the failed addresses, possibly empty; null when no recipient failed
   * @throws MessagingException when a block cannot be parsed as headers
   */
  static List<String> failedRecipientsOfStatus(String status) throws MessagingException {
    Set<String> failed = new LinkedHashSet<>();
    boolean anyFailed = false;
    String[] blocks = BLANK_LINE.split(status.trim());
    // The first block describes the message as a whole, never one recipient.
    for (int i = 1; i < blocks.length; i++) {
      InternetHeaders fields = new InternetHeaders(new ByteArrayInputStream((blocks[i].trim() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8)));
      String action = fields.getHeader("Action", null);
      if (action == null || !"failed".equalsIgnoreCase(StringUtils.substringBefore(action, "(").trim())) {
        continue;
      }
      anyFailed = true;
      String recipient = typedAddress(fields.getHeader("Final-Recipient", null));
      if (recipient == null) {
        recipient = typedAddress(fields.getHeader("Original-Recipient", null));
      }
      if (recipient != null) {
        failed.add(recipient);
      }
    }
    return anyFailed ? new ArrayList<>(failed) : null;
  }

  /**
   * The address of an RFC 3464 recipient field, {@code address-type ; address}.
   *
   * @param field the field's value, possibly null
   * @return the bare address, null when it holds no single address
   */
  private static String typedAddress(String field) {
    if (StringUtils.isBlank(field)) {
      return null;
    }
    String value = field.contains(";") ? StringUtils.substringAfter(field, ";") : field;
    List<String> addresses = addressesOf(value);
    return addresses.size() == 1 ? addresses.get(0) : null;
  }

  /**
   * The individual addresses of an address list, groups left out: a bounce reports
   * mailboxes, and a group in a header names nobody it could have failed to reach.
   *
   * @param value the header's value
   * @return the bare addresses, in order, without duplicates
   */
  private static List<String> addressesOf(String value) {
    Set<String> addresses = new LinkedHashSet<>();
    try {
      for (InternetAddress address : InternetAddress.parseHeader(value.trim(), false)) {
        if (!address.isGroup() && StringUtils.isNotBlank(address.getAddress()) && address.getAddress().contains("@")) {
          addresses.add(address.getAddress().trim());
        }
      }
    } catch (AddressException e) {
      // An unreadable list names no address the campaign could act on.
    }
    return new ArrayList<>(addresses);
  }

  /**
   * The first value of a header.
   *
   * @param message the message
   * @param name the header
   * @return its first value, null when absent
   * @throws MessagingException when the headers cannot be read
   */
  private static String firstHeader(Message message, String name) throws MessagingException {
    String[] values = message.getHeader(name);
    return values != null && values.length > 0 ? values[0] : null;
  }
}
