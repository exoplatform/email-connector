/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a mail server's rules engine can do for one connector, as its engine answers
 * {@code probe}: one record shared by the automatic reply and the server rules, so that
 * one form component greys out, per element, what this server cannot do and says why.
 * <p>
 * Two levels. The <b>engine level</b> says whether the engine can publish at all
 * ({@code supported}, with a {@code reasonCode} when not), whether it can list rules
 * another client wrote ({@code readsForeignRules}), whether a publish could run into
 * another client's state ({@code publishConflict}), and where the vocabulary comes from.
 * The <b>element level</b> answers, per form element, {@code supported} or
 * {@code unsupported(reasonKey)}; an element the engine does not list is unsupported.
 *
 * @param supported whether the engine can publish on this connector at all
 * @param reasonCode why not, a message code; null when supported
 * @param readsForeignRules whether rules another client wrote can be listed
 * @param publishConflict whether a publish could meet another client's active state
 * @param vocabularySource where the element answers come from
 * @param elements the answer per form element, keyed by the constants of this class
 */
public record ServerRuleCapabilities(boolean supported,
                                     String reasonCode,
                                     boolean readsForeignRules,
                                     boolean publishConflict,
                                     VocabularySource vocabularySource,
                                     Map<String, ElementSupport> elements) {

  /** The automatic reply itself. */
  public static final String       VACATION               = "vacation";

  /** A first and a last day for the automatic reply. */
  public static final String       VACATION_DATE_WINDOW   = "vacationDateWindow";

  /** An HTML automatic reply. */
  public static final String       VACATION_HTML          = "vacationHtml";

  /** Reading an existing forward. */
  public static final String       FORWARDING_READ        = "forwardingRead";

  /** Setting a forward. */
  public static final String       FORWARDING_WRITE       = "forwardingWrite";

  /** Reading an automatic reply another client set, as a structured value. */
  public static final String       READS_FOREIGN_VACATION = "readsForeignVacation";

  /** Rule condition on the sender. */
  public static final String       FROM                   = "FROM";

  /** Rule condition on the To recipients. */
  public static final String       TO                     = "TO";

  /** Rule condition on the Cc recipients. */
  public static final String       CC                     = "CC";

  /** Rule condition on any recipient. */
  public static final String       ANY_RECIPIENT          = "ANY_RECIPIENT";

  /** Rule condition on the subject. */
  public static final String       SUBJECT                = "SUBJECT";

  /** Rule condition on the body. */
  public static final String       BODY                   = "BODY";

  /** Rule condition on the subject or the body. */
  public static final String       SUBJECT_OR_BODY        = "SUBJECT_OR_BODY";

  /** Rule condition on any header. */
  public static final String       HEADER                 = "HEADER";

  /** Rule condition on the message size. */
  public static final String       MESSAGE_SIZE           = "MESSAGE_SIZE";

  /** Rule condition: the message comes from a mailing list. */
  public static final String       IS_LIST                = "IS_LIST";

  /** Rule condition: the message was sent by an automated sender. */
  public static final String       IS_AUTOMATED           = "IS_AUTOMATED";

  /** Rule condition: the message has an attachment. */
  public static final String       HAS_ATTACHMENT         = "HAS_ATTACHMENT";

  /** Rule condition on an attachment's name. */
  public static final String       ATTACHMENT_NAME        = "ATTACHMENT_NAME";

  /** Rule condition on an attachment's size. */
  public static final String       ATTACHMENT_SIZE        = "ATTACHMENT_SIZE";

  /** Rule action: move to a folder. */
  public static final String       MOVE_TO_FOLDER         = "MOVE_TO_FOLDER";

  /** Rule action: mark as read. */
  public static final String       MARK_READ              = "MARK_READ";

  /** Rule action: star. */
  public static final String       STAR                   = "STAR";

  /** Rule action: move to Junk. */
  public static final String       MARK_JUNK              = "MARK_JUNK";

  /** Rule action: move to Trash. */
  public static final String       DELETE                 = "DELETE";

  /** Rule action: set the keyword eXo's own rules pick up at sync. */
  public static final String       TAG                    = "TAG";

  /** Every element this record knows, in the form's order. */
  public static final List<String> ELEMENTS               = List.of(VACATION,
                                                                    VACATION_DATE_WINDOW,
                                                                    VACATION_HTML,
                                                                    FORWARDING_READ,
                                                                    FORWARDING_WRITE,
                                                                    READS_FOREIGN_VACATION,
                                                                    FROM,
                                                                    TO,
                                                                    CC,
                                                                    ANY_RECIPIENT,
                                                                    SUBJECT,
                                                                    BODY,
                                                                    SUBJECT_OR_BODY,
                                                                    HEADER,
                                                                    MESSAGE_SIZE,
                                                                    IS_LIST,
                                                                    IS_AUTOMATED,
                                                                    HAS_ATTACHMENT,
                                                                    ATTACHMENT_NAME,
                                                                    ATTACHMENT_SIZE,
                                                                    MOVE_TO_FOLDER,
                                                                    MARK_READ,
                                                                    STAR,
                                                                    MARK_JUNK,
                                                                    DELETE,
                                                                    TAG);

  /** Where an engine's element answers come from. */
  public enum VocabularySource {
    /** A fixed model, the same on every server of that family. */
    FIXED,
    /** Read from the server per connector, e.g. the Sieve {@code SIEVE} capability line. */
    DYNAMIC,
    /** No engine: nothing is supported. */
    NONE
  }

  /**
   * One element's answer.
   *
   * @param supported whether the element can be used
   * @param reasonKey why not, a message key; null when supported
   */
  public record ElementSupport(boolean supported, String reasonKey) {

    /** The answer for a supported element. */
    public static final ElementSupport SUPPORTED = new ElementSupport(true, null);

    /**
     * The answer for an unsupported element.
     *
     * @param reasonKey the message key explaining why
     * @return the answer
     */
    public static ElementSupport unsupported(String reasonKey) {
      return new ElementSupport(false, reasonKey);
    }
  }

  /**
   * Keeps the element map unmodifiable and in insertion order.
   *
   * @param supported whether the engine can publish
   * @param reasonCode why not
   * @param readsForeignRules whether foreign rules can be listed
   * @param publishConflict whether a publish could meet foreign state
   * @param vocabularySource where the answers come from
   * @param elements the answers per element
   */
  public ServerRuleCapabilities {
    elements = elements == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(elements));
  }

  /**
   * An engine that can do nothing on this connector, every element unsupported for the
   * same reason.
   *
   * @param reasonCode the message code explaining why
   * @param vocabularySource where the answer comes from
   * @return the capabilities
   */
  public static ServerRuleCapabilities unsupported(String reasonCode, VocabularySource vocabularySource) {
    Map<String, ElementSupport> elements = new LinkedHashMap<>();
    ELEMENTS.forEach(element -> elements.put(element, ElementSupport.unsupported(reasonCode)));
    return new ServerRuleCapabilities(false, reasonCode, false, false, vocabularySource, elements);
  }

  /**
   * Whether an element can be used.
   *
   * @param element one of the element constants
   * @return true when the engine lists it as supported
   */
  public boolean isSupported(String element) {
    ElementSupport support = elements.get(element);
    return supported && support != null && support.supported();
  }

  /**
   * The same capabilities with the account-level conflict flag set, which a probe learns
   * from the account's scripts rather than from the server's advertisement.
   *
   * @param conflict whether a publish could meet another client's active state
   * @return the new capabilities
   */
  public ServerRuleCapabilities withPublishConflict(boolean conflict) {
    return new ServerRuleCapabilities(supported, reasonCode, readsForeignRules, conflict, vocabularySource, elements);
  }
}
