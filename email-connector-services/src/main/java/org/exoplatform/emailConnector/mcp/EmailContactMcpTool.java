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
package org.exoplatform.emailConnector.mcp;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.mcp.model.ContactHitModel;
import org.exoplatform.emailConnector.mcp.model.ContactModel;
import org.exoplatform.emailConnector.mcp.model.ContactSearchResultsModel;
import org.exoplatform.emailConnector.mcp.model.RecipientSuggestionModel;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactPage;
import org.exoplatform.emailConnector.service.EmailContactService;

import io.meeds.mcp.server.plugin.McpToolPlugin;

/**
 * MCP tools exposing the user's personal contact store to the AI agent (EVA) —
 * what lets the assistant turn "write to Marie at Acme" into an address that
 * {@code send_email} can use, for external people the platform directory does
 * not know. Every method acts as the current user: the acting username comes
 * from the MCP session, is passed into every service call, and is never a tool
 * argument, so the caller only ever touches their own store.
 * <p>
 * The store holds real people's personal data, so the shapes are deliberately
 * stingy: list results never carry phone numbers or photos, the single-contact
 * read adds phones but still no photo (no bytes, no URL), page sizes are
 * hard-capped, and there is no list-everything tool — a search always filters.
 * The one write ({@code create_contact}) is approval-gated.
 */
@Service
@Profile("mcp-server")
public class EmailContactMcpTool implements McpToolPlugin {

  /** Hits returned when the caller names no limit: a readable page, not a dump. */
  private static final int                 DEFAULT_SEARCH_LIMIT = 20;

  /** The hard cap on one search page, whatever the caller asks for. */
  private static final int                 MAX_SEARCH_LIMIT     = 50;

  /**
   * The service reports refusals with message codes, the right currency for
   * REST and useless to a model. These are the same refusals in words a model
   * can act on, since it is the one that has to correct the call.
   */
  private static final Map<String, String> CONTACT_MESSAGES     =
                                                            Map.of(EmailContactService.CONTACT_INVALID_SOURCE,
                                                                   "source must be one of collected, manual or addressBook, or omitted to search the whole store.",
                                                                   EmailContactService.CONTACT_INVALID_EMAIL,
                                                                   "A usable email address is required to create a contact.",
                                                                   EmailContactService.CONTACT_ALREADY_EXISTS,
                                                                   "A contact with this email address already exists in the user's store - search_contacts finds it.");

  private final EmailContactService        emailContactService;

  /**
   * Builds the plugin over the one service every contact read and write of the
   * add-on already goes through, so validation, normalization and per-user
   * scoping are shared with the REST layer rather than reimplemented.
   *
   * @param emailContactService the contact store service
   */
  @Autowired
  public EmailContactMcpTool(EmailContactService emailContactService) {
    this.emailContactService = emailContactService;
  }

  // ---------------------------------------------------------------------------
  // Read tools (no approval)
  // ---------------------------------------------------------------------------

  /**
   * Search the current user's own contact store by name, address or
   * organization. Answers a hard-capped first page plus the total that matched,
   * each hit carrying the id (for get_contact) and the ready-to-use email
   * address (for send_email) — and never phone numbers or photos.
   *
   * @param query free text matched against names and addresses, null for a
   *          filter-only search
   * @param source collected, manual or addressBook; null or "all" for the whole
   *          store
   * @param favorites when true, only starred contacts match
   * @param limit how many hits to return, defaulted to
   *          {@value #DEFAULT_SEARCH_LIMIT} and capped at
   *          {@value #MAX_SEARCH_LIMIT}
   * @return the alphabetical first page and the filtered total
   */
  public ContactSearchResultsModel searchContacts(String query, String source, Boolean favorites, Integer limit) {
    try {
      EmailContactPage page = emailContactService.getContacts(getCurrentUserName(),
                                                              StringUtils.isBlank(source) ? null : List.of(source.trim()),
                                                              query,
                                                              Boolean.TRUE.equals(favorites),
                                                              0,
                                                              sanitizeSearchLimit(limit));
      List<ContactHitModel> hits = page == null || page.getContacts() == null ? List.of()
                                                                              : page.getContacts()
                                                                                    .stream()
                                                                                    .map(this::toContactHit)
                                                                                    .toList();
      return new ContactSearchResultsModel(page == null ? 0 : page.getSize(), hits);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(CONTACT_MESSAGES.getOrDefault(e.getMessage(), e.getMessage()));
    }
  }

  /**
   * Retrieve one contact of the current user's store in full — the only place
   * phone numbers are answered; photos never are. A contact that does not
   * exist, was removed, or belongs to another user answers not-found alike,
   * exactly as the REST does: an id must never reveal whether it exists in
   * somebody else's store.
   *
   * @param contactId the contact id, from search_contacts
   * @return the contact
   * @throws ObjectNotFoundException when there is nothing this user can see
   */
  public ContactModel getContact(long contactId) throws ObjectNotFoundException {
    EmailContact contact = emailContactService.getContact(contactId, getCurrentUserName());
    if (contact == null) {
      throw new ObjectNotFoundException("Contact with id %s not found");
    }
    return toContactModel(contact);
  }

  /**
   * Suggest recipients for a name or partial address, exactly as the compose
   * field's type-ahead does: the user's own contacts first, ranked by how much
   * they correspond with each, then platform colleagues from the people
   * directory — so the assistant addresses mail the way the UI would, including
   * colleagues the store has never seen. De-duplicated by address; the
   * service's own hard cap bounds the answer.
   *
   * @param query the name or partial address to match, blank for the user's top
   *          contacts
   * @param limit how many suggestions to return, the service defaulting and
   *          capping it
   * @return the ranked suggestions
   */
  public List<RecipientSuggestionModel> suggestRecipients(String query, Integer limit) {
    return emailContactService.suggestRecipients(getCurrentUserName(), query, limit == null ? 0 : limit)
                              .stream()
                              .map(suggestion -> new RecipientSuggestionModel(suggestion.getAddress(),
                                                                              suggestion.getDisplayName(),
                                                                              suggestion.isPlatformUser(),
                                                                              suggestion.getProfileUrl()))
                              .toList();
  }

  // ---------------------------------------------------------------------------
  // Write tool (approval-gated)
  // ---------------------------------------------------------------------------

  /**
   * Create a manual contact in the current user's store, through the same
   * service path the contact form uses, so address validation, normalization
   * and the revive-a-removed-contact behavior are shared rather than
   * reimplemented. Approval-gated: the user confirms before their store is
   * written.
   * <p>
   * It takes the origin-less create on purpose, so a contact made through an
   * agent never publishes itself to the user's address book. The approval
   * dialog says a contact will be created here; it does not say a card will be
   * written to a third-party server, and a tool call must not do more than what
   * was approved. Publishing it stays one click on the contact, exactly as for
   * a collected or imported row.
   *
   * @param email the contact's address, required
   * @param givenName the first name, optional
   * @param familyName the last name, optional
   * @param displayName the name to show when the given/family pair is not
   *          known, optional
   * @param organization the company or organization, optional
   * @param title the job title, optional
   * @param phones phone numbers, optional
   * @return a confirmation carrying the created contact's id
   */
  public String createContact(String email,
                              String givenName,
                              String familyName,
                              String displayName,
                              String organization,
                              String title,
                              List<String> phones) {
    EmailContact contact = new EmailContact();
    contact.setPrimaryEmail(email);
    contact.setGivenName(givenName);
    contact.setFamilyName(familyName);
    contact.setDisplayName(displayName);
    contact.setOrganization(organization);
    contact.setTitle(title);
    contact.setPhones(phones);
    try {
      EmailContact created = emailContactService.createContact(contact, getCurrentUserName());
      return String.format("Created contact %s <%s> (id %d).",
                           StringUtils.defaultIfBlank(created.getDisplayName(), created.getPrimaryEmail()),
                           created.getPrimaryEmail(),
                           created.getId());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(CONTACT_MESSAGES.getOrDefault(e.getMessage(), e.getMessage()));
    } catch (IllegalStateException e) {
      throw new IllegalStateException(CONTACT_MESSAGES.getOrDefault(e.getMessage(), e.getMessage()));
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Maps one enriched store row to a search hit: identity, address and
   * affiliation only — the deliberate exclusions (phones, photos, avatar URLs,
   * store bookkeeping) are the point of the mapping.
   *
   * @param contact the enriched store row
   * @return the hit
   */
  private ContactHitModel toContactHit(EmailContact contact) {
    return new ContactHitModel(contact.getId(),
                               contact.getDisplayName(),
                               contact.getPrimaryEmail(),
                               contact.getOrganization(),
                               contact.getTitle(),
                               contact.getSource(),
                               contact.isFavorite(),
                               StringUtils.isNotBlank(contact.getProfileUrl()),
                               contact.getProfileUrl());
  }

  /**
   * Maps one enriched store row to the full single-contact shape, phones
   * included, photos still excluded.
   *
   * @param contact the enriched store row
   * @return the model
   */
  private ContactModel toContactModel(EmailContact contact) {
    return new ContactModel(contact.getId(),
                            contact.getDisplayName(),
                            contact.getGivenName(),
                            contact.getFamilyName(),
                            contact.getPrimaryEmail(),
                            contact.getSecondaryEmails(),
                            contact.getPhones(),
                            contact.getOrganization(),
                            contact.getTitle(),
                            contact.getBirthday(),
                            contact.getPostalAddress() == null ? null : contact.getPostalAddress().toSingleLine(),
                            contact.getNote(),
                            contact.getWebsite(),
                            contact.getSource(),
                            contact.isFavorite(),
                            StringUtils.isNotBlank(contact.getProfileUrl()),
                            contact.getProfileUrl(),
                            contact.getLastSeenDate());
  }

  /**
   * Clamps a search page to the tool's hard cap, so a broad query can never be
   * turned into a store dump — the same discipline the compose suggest limit
   * applies.
   *
   * @param limit the requested page size, null or non-positive for the default
   * @return a size between 1 and {@value #MAX_SEARCH_LIMIT}
   */
  private int sanitizeSearchLimit(Integer limit) {
    return limit == null || limit <= 0 ? DEFAULT_SEARCH_LIMIT : Math.min(limit, MAX_SEARCH_LIMIT);
  }
}
