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
package org.exoplatform.emailConnector.service.rules.sieve;

import static org.exoplatform.emailConnector.model.ServerRuleCapabilities.ElementSupport.SUPPORTED;
import static org.exoplatform.emailConnector.model.ServerRuleCapabilities.ElementSupport.unsupported;

import java.util.LinkedHashMap;
import java.util.Map;

import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities.ElementSupport;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities.VocabularySource;

/**
 * What a Sieve server can do, derived from the capabilities it advertised <b>after</b>
 * {@code STARTTLS} — the {@code SIEVE} line for the vocabulary, the {@code SASL} line for
 * whether eXo can authenticate at all. Never from an IMAP capability, which describes
 * another protocol.
 * <p>
 * The floor is RFC 5228 (address, header, size, {@code allof}/{@code anyof},
 * {@code stop}); everything above it is read per extension: {@code vacation}, with
 * {@code date} and {@code relational} for a date window; {@code fileinto} for a move;
 * {@code imap4flags} for a flag. A body condition is not offered in this phase, even
 * where {@code body} is advertised: the rules generator writes none. What Sieve cannot
 * express at all (attachment tests without extensions) is answered unsupported; what
 * only eXo can do (its categories, notifications, agents) is not an element of this
 * record. Account-level facts — whether another script is active — are the probe's to
 * add.
 */
public final class SieveCapabilityDerivation {

  /** Prefix of every reason key this derivation answers. */
  static final String REASON_PREFIX        = "emailConnector.rules.unsupported.";

  /** No SASL PLAIN after TLS: eXo cannot authenticate. */
  static final String NO_PLAIN             = REASON_PREFIX + "saslPlain";

  /** An extension the element needs is not advertised. */
  static final String MISSING_EXTENSION    = REASON_PREFIX + "sieveExtension.";

  /** Not offered in this phase, whatever the server supports. */
  static final String NOT_IN_PHASE_1       = REASON_PREFIX + "notInPhase1";

  /** A foreign script is opaque: detected, never read as a model. */
  static final String FOREIGN_OPAQUE       = REASON_PREFIX + "foreignScriptOpaque";

  /** Sieve has no attachment test without extensions eXo does not generate. */
  static final String NO_ATTACHMENT_TEST   = REASON_PREFIX + "noAttachmentTest";

  /**
   * Utility class.
   */
  private SieveCapabilityDerivation() {
  }

  /**
   * Derives the capabilities of a Sieve server.
   *
   * @param capabilities what the server advertised after TLS
   * @return the capabilities, with {@code publishConflict} false until the probe reads
   *         the account
   */
  public static ServerRuleCapabilities derive(ManageSieveCapabilities capabilities) {
    if (!capabilities.supportsSasl(ManageSieveClient.SASL_PLAIN)) {
      return ServerRuleCapabilities.unsupported(NO_PLAIN, VocabularySource.DYNAMIC);
    }
    Map<String, ElementSupport> elements = new LinkedHashMap<>();
    ElementSupport vacation = needs(capabilities, "vacation");
    elements.put(ServerRuleCapabilities.VACATION, vacation);
    elements.put(ServerRuleCapabilities.VACATION_DATE_WINDOW,
                 vacation.supported() ? both(needs(capabilities, "date"), needs(capabilities, "relational")) : vacation);
    elements.put(ServerRuleCapabilities.VACATION_HTML, unsupported(NOT_IN_PHASE_1));
    elements.put(ServerRuleCapabilities.FORWARDING_READ, SUPPORTED);
    elements.put(ServerRuleCapabilities.FORWARDING_WRITE, unsupported(NOT_IN_PHASE_1));
    elements.put(ServerRuleCapabilities.READS_FOREIGN_VACATION, unsupported(FOREIGN_OPAQUE));
    for (String core : new String[] { ServerRuleCapabilities.FROM, ServerRuleCapabilities.TO, ServerRuleCapabilities.CC,
        ServerRuleCapabilities.ANY_RECIPIENT, ServerRuleCapabilities.SUBJECT, ServerRuleCapabilities.HEADER,
        ServerRuleCapabilities.MESSAGE_SIZE, ServerRuleCapabilities.IS_LIST, ServerRuleCapabilities.IS_AUTOMATED }) {
      elements.put(core, SUPPORTED);
    }
    // The generator writes no body test in this phase, whatever the server advertises:
    // offering the condition would let the form save what the engine cannot publish.
    elements.put(ServerRuleCapabilities.BODY, unsupported(NOT_IN_PHASE_1));
    elements.put(ServerRuleCapabilities.SUBJECT_OR_BODY, unsupported(NOT_IN_PHASE_1));
    elements.put(ServerRuleCapabilities.HAS_ATTACHMENT, unsupported(NO_ATTACHMENT_TEST));
    elements.put(ServerRuleCapabilities.ATTACHMENT_NAME, unsupported(NO_ATTACHMENT_TEST));
    elements.put(ServerRuleCapabilities.ATTACHMENT_SIZE, unsupported(NO_ATTACHMENT_TEST));
    ElementSupport fileinto = needs(capabilities, "fileinto");
    elements.put(ServerRuleCapabilities.MOVE_TO_FOLDER, fileinto);
    elements.put(ServerRuleCapabilities.MARK_JUNK, fileinto);
    elements.put(ServerRuleCapabilities.DELETE, fileinto);
    ElementSupport flags = needs(capabilities, "imap4flags");
    elements.put(ServerRuleCapabilities.MARK_READ, flags);
    elements.put(ServerRuleCapabilities.STAR, flags);
    elements.put(ServerRuleCapabilities.TAG, flags);
    return new ServerRuleCapabilities(true, null, false, false, VocabularySource.DYNAMIC, elements);
  }

  /**
   * The answer for an element that needs one extension.
   *
   * @param capabilities the server's capabilities
   * @param extension the extension
   * @return supported when advertised, else unsupported naming the extension
   */
  private static ElementSupport needs(ManageSieveCapabilities capabilities, String extension) {
    return capabilities.hasExtension(extension) ? SUPPORTED : unsupported(MISSING_EXTENSION + extension);
  }

  /**
   * The answer for an element that needs two things: the first missing one wins.
   *
   * @param first the first answer
   * @param second the second answer
   * @return supported when both are
   */
  private static ElementSupport both(ElementSupport first, ElementSupport second) {
    return first.supported() ? second : first;
  }
}
