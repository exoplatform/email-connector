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
package org.exoplatform.emailConnector.carddav;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.springframework.stereotype.Component;

import ezvcard.VCard;
import ezvcard.VCardVersion;
import ezvcard.io.text.VCardReader;
import ezvcard.io.text.VCardWriter;
import ezvcard.parameter.EmailType;
import ezvcard.parameter.ImageType;
import ezvcard.parameter.TelephoneType;
import ezvcard.property.Address;
import ezvcard.property.Birthday;
import ezvcard.property.Email;
import ezvcard.property.FormattedName;
import ezvcard.property.Note;
import ezvcard.property.Organization;
import ezvcard.property.Photo;
import ezvcard.property.RawProperty;
import ezvcard.property.StructuredName;
import ezvcard.property.Telephone;
import ezvcard.property.Uid;
import ezvcard.property.Url;
import ezvcard.util.PartialDate;

import org.exoplatform.emailConnector.model.PostalAddress;
import org.exoplatform.emailConnector.utils.EmailContactUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The vCard reader, on top of ez-vcard.
 * <p>
 * A library rather than our own parsing because vCard in the wild is hostile:
 * folded lines, vCard 2.1 quoted-printable from old Outlook exports, charset
 * parameters, three photo encodings across 2.1/3.0/4.0, escaped separators. A
 * hand-written parser mangles real Gmail and iCloud exports, and those failures
 * would look like sync bugs rather than parsing ones.
 * <p>
 * Everything ez-vcard knows stays behind {@link VCardParser}: no other class in
 * the add-on imports the library, so replacing it stays a one-file change.
 */
@Component
public class EzVCardParser implements VCardParser {

  private static final Log LOG = ExoLogger.getLogger(EzVCardParser.class);

  /** A birthday the card gave no year for, as the store writes it: {@code --MM-DD}. */
  private static final String YEARLESS_BIRTHDAY_FORMAT = "--%02d-%02d";

  @Override
  public ParsedVCard parse(String vcardText) {
    if (StringUtils.isBlank(vcardText)) {
      return null;
    }
    try {
      VCard vcard = ezvcard.Ezvcard.parse(vcardText).first();
      if (vcard == null) {
        return null;
      }
      return toParsed(vcard);
    } catch (Exception e) {
      // A vCard we cannot read is one contact skipped, never a failed sync: an
      // address book of a thousand entries must not be held hostage by one of them.
      LOG.debug("A vCard could not be parsed and was skipped", e);
      return null;
    }
  }

  @Override
  public void parseAll(Reader vcards, VCardSink sink) throws IOException {
    // The library's own streaming reader: one card in memory at a time, however
    // large the file, which is what makes the import's size cap a real bound
    // rather than a hope.
    VCardReader reader = new VCardReader(vcards);
    VCard vcard;
    while ((vcard = reader.readNext()) != null) {
      boolean more;
      try {
        more = sink.accept(toParsed(vcard));
      } catch (Exception e) {
        // The card, not the file: whatever this card carried that the mapping
        // choked on, the next card deserves its chance.
        LOG.debug("A vCard could not be read and was reported unreadable", e);
        more = sink.acceptUnreadable();
      }
      if (!more) {
        return;
      }
    }
  }

  @Override
  public String format(ParsedVCard card) {
    VCard vcard = new VCard();
    if (StringUtils.isNotBlank(card.uid())) {
      vcard.setUid(new Uid(card.uid()));
    }
    // FN and N both, always: 3.0 requires them and every importer leans on one
    // or the other. A card with no name at all still gets its address as FN,
    // because an FN-less card makes Outlook invent "Untitled contact".
    String fallbackName = StringUtils.defaultIfBlank(card.formattedName(),
                                                     StringUtils.trimToNull(StringUtils.trimToEmpty(card.givenName()) + " "
                                                         + StringUtils.trimToEmpty(card.familyName())));
    vcard.setFormattedName(StringUtils.defaultIfBlank(fallbackName,
                                                      card.emails().isEmpty() ? "" : card.emails().get(0)));
    StructuredName structuredName = new StructuredName();
    structuredName.setGiven(card.givenName());
    structuredName.setFamily(card.familyName());
    vcard.setStructuredName(structuredName);
    boolean first = true;
    for (String address : card.emails()) {
      // The first address is the one the contact is keyed on, so the export
      // says so: TYPE=PREF is the 3.0 spelling, and it is exactly what
      // emailsOf() reads back — an exported store re-imports keyed the same.
      vcard.addEmail(address, first ? new EmailType[] { EmailType.INTERNET, EmailType.PREF }
                                    : new EmailType[] { EmailType.INTERNET });
      first = false;
    }
    for (String phone : card.phones()) {
      // A typed entry writes its type back as the TEL TYPE parameter -- the
      // very vocabulary phonesOf() reads -- so a card that arrived as somebody's
      // work number leaves as one instead of a bare string.
      String value = EmailContactUtils.phoneValueOf(phone);
      String type = EmailContactUtils.phoneTypeOf(phone);
      if (value == null) {
        continue;
      }
      if (type == null) {
        vcard.addTelephoneNumber(value);
      } else {
        vcard.addTelephoneNumber(value, TelephoneType.get(type));
      }
    }
    if (StringUtils.isNotBlank(card.organization())) {
      vcard.setOrganization(card.organization());
    }
    if (StringUtils.isNotBlank(card.title())) {
      vcard.addTitle(card.title());
    }
    writeBirthday(vcard, card.birthday());
    if (card.address() != null) {
      Address address = new Address();
      address.setStreetAddress(card.address().street());
      address.setLocality(card.address().city());
      address.setRegion(card.address().region());
      address.setPostalCode(card.address().postalCode());
      address.setCountry(card.address().country());
      vcard.addAddress(address);
    }
    if (StringUtils.isNotBlank(card.note())) {
      vcard.addNote(plainText(card.note()));
    }
    if (StringUtils.isNotBlank(card.website())) {
      vcard.addUrl(card.website());
    }
    if (card.photo() != null && card.photo().length > 0) {
      vcard.addPhoto(new Photo(card.photo(), ImageType.get(null, StringUtils.defaultIfBlank(card.photoMimeType(), "image/jpeg"), null)));
    }
    return write(vcard);
  }

  @Override
  public String merge(String rawVCard, ParsedVCard edited) {
    if (StringUtils.isBlank(rawVCard) || edited == null) {
      return null;
    }
    try {
      VCard vcard = ezvcard.Ezvcard.parse(rawVCard).first();
      if (vcard == null) {
        return null;
      }
      // The delta baseline: each patch below compares the edit against what the
      // card CURRENTLY reads as through the very same reduction the form was
      // filled from, and an equal field is not touched at all -- parameters,
      // group and formatting quirks included.
      ParsedVCard current = toParsed(vcard);
      patchName(vcard, current, edited);
      patchEmails(vcard, current, edited);
      patchPhones(vcard, current, edited);
      patchOrganization(vcard, current, edited);
      patchBirthday(vcard, current, edited);
      patchAddress(vcard, current, edited);
      patchNote(vcard, current, edited);
      patchWebsite(vcard, current, edited);
      return writePreserving(vcard);
    } catch (Exception e) {
      // Refusing to answer is the merge failing SAFE: a card this code cannot
      // read is one it cannot promise to patch without loss, and the caller's
      // contract is to not write at all rather than to write a guess.
      LOG.warn("A server vCard could not be merged with an edit, so the write was refused", e);
      return null;
    }
  }

  /**
   * Patches the name — N's given and family halves in place, so a card's
   * middle names, prefixes and suffixes survive an edit to the two halves the
   * form shows; and FN only when the service resolved one to say, because a
   * form silent about the name must leave the name alone.
   *
   * @param vcard the card being patched
   * @param current what the card reads as today
   * @param edited what the user saved
   */
  private void patchName(VCard vcard, ParsedVCard current, ParsedVCard edited) {
    if (!StringUtils.equals(current.givenName(), edited.givenName())
        || !StringUtils.equals(current.familyName(), edited.familyName())) {
      StructuredName name = vcard.getStructuredName();
      if (name == null) {
        name = new StructuredName();
        vcard.setStructuredName(name);
      }
      name.setGiven(edited.givenName());
      name.setFamily(edited.familyName());
    }
    String editedFn = StringUtils.trimToNull(edited.formattedName());
    if (editedFn != null && !StringUtils.equals(current.formattedName(), editedFn)) {
      FormattedName fn = vcard.getFormattedName();
      if (fn == null) {
        vcard.setFormattedName(editedFn);
      } else {
        fn.setValue(editedFn);
      }
    }
  }

  /**
   * Patches the addresses as a delta, never as a rewrite: a line whose value
   * the user kept stays byte-for-byte theirs (its TYPE, its group, its label),
   * a value they removed loses its line, a value they added gets a plain new
   * one. A line whose value the model could not read was never shown on the
   * form, so it is not the user's removal to make and is left alone.
   * <p>
   * The preference marker moves only when the primary actually moved — and
   * then it must: the row is keyed on the first address and the inbound sync
   * orders by preference, so leaving the old marker would have the very next
   * sync swap the user's choice back.
   *
   * @param vcard the card being patched
   * @param current what the card reads as today
   * @param edited what the user saved, primary first
   */
  private void patchEmails(VCard vcard, ParsedVCard current, ParsedVCard edited) {
    Set<String> editedSet = new LinkedHashSet<>();
    if (edited.emails() != null) {
      edited.emails().forEach(address -> {
        String normalized = EmailContactUtils.normalizeAddress(address);
        if (normalized != null) {
          editedSet.add(normalized);
        }
      });
    }
    List<String> currentVisible = current.emails()
                                         .stream()
                                         .map(EmailContactUtils::normalizeAddress)
                                         .filter(Objects::nonNull)
                                         .distinct()
                                         .toList();
    if (currentVisible.equals(new ArrayList<>(editedSet))) {
      return;
    }
    Set<String> present = new HashSet<>();
    for (Email email : new ArrayList<>(vcard.getEmails())) {
      String normalized = EmailContactUtils.normalizeAddress(email.getValue());
      if (normalized == null) {
        continue;
      }
      if (editedSet.contains(normalized)) {
        present.add(normalized);
      } else {
        vcard.removeProperty(email);
      }
    }
    for (String address : editedSet) {
      if (!present.contains(address)) {
        Email added = new Email(address);
        if (vcard.getVersion() != VCardVersion.V4_0) {
          added.getTypes().add(EmailType.INTERNET);
        }
        vcard.addEmail(added);
      }
    }
    String editedPrimary = editedSet.isEmpty() ? null : editedSet.iterator().next();
    String currentPrimary = currentVisible.isEmpty() ? null : currentVisible.get(0);
    if (editedPrimary != null && !editedPrimary.equals(currentPrimary)) {
      for (Email email : vcard.getEmails()) {
        boolean primary = editedPrimary.equals(EmailContactUtils.normalizeAddress(email.getValue()));
        // Both spellings of preference are (re)written, because both are read:
        // 4.0's numeric PREF parameter, 3.0's TYPE=PREF flag.
        email.setPref(primary && vcard.getVersion() == VCardVersion.V4_0 ? 1 : null);
        email.getTypes().removeIf(type -> "pref".equalsIgnoreCase(type.getValue()));
        if (primary && vcard.getVersion() != VCardVersion.V4_0) {
          email.getTypes().add(EmailType.PREF);
        }
      }
    }
  }

  /**
   * Patches the phone numbers with the same delta rule as the addresses: a
   * kept entry keeps its line untouched, a removed entry loses its line, an
   * added entry gets a new one typed the way the store names it. Equality is
   * on the store's own {@code type,value} entry, so retyping a number's kind
   * counts as the edit it is.
   *
   * @param vcard the card being patched
   * @param current what the card reads as today
   * @param edited what the user saved
   */
  private void patchPhones(VCard vcard, ParsedVCard current, ParsedVCard edited) {
    Set<String> editedSet = new LinkedHashSet<>();
    if (edited.phones() != null) {
      edited.phones().forEach(entry -> {
        String canonical = EmailContactUtils.phoneEntryOf(EmailContactUtils.phoneTypeOf(entry),
                                                          EmailContactUtils.phoneValueOf(entry));
        if (canonical != null) {
          editedSet.add(canonical);
        }
      });
    }
    if (new HashSet<>(current.phones()).equals(editedSet)) {
      return;
    }
    Set<String> present = new HashSet<>();
    for (Telephone phone : new ArrayList<>(vcard.getTelephoneNumbers())) {
      String entry = entryOf(phone);
      if (entry == null) {
        continue;
      }
      if (editedSet.contains(entry)) {
        present.add(entry);
      } else {
        vcard.removeProperty(phone);
      }
    }
    for (String entry : editedSet) {
      if (present.contains(entry)) {
        continue;
      }
      String value = EmailContactUtils.phoneValueOf(entry);
      String type = EmailContactUtils.phoneTypeOf(entry);
      if (value == null) {
        continue;
      }
      if (type == null) {
        vcard.addTelephoneNumber(value);
      } else {
        vcard.addTelephoneNumber(value, TelephoneType.get(type));
      }
    }
  }

  /**
   * Patches the company name into ORG's first slot only, so a card that also
   * files a department in the slots behind it keeps that department.
   *
   * @param vcard the card being patched
   * @param current what the card reads as today
   * @param edited what the user saved
   */
  private void patchOrganization(VCard vcard, ParsedVCard current, ParsedVCard edited) {
    if (StringUtils.equals(current.organization(), edited.organization())) {
      return;
    }
    Organization organization = vcard.getOrganization();
    if (StringUtils.isBlank(edited.organization())) {
      if (organization != null) {
        vcard.removeProperty(organization);
      }
    } else if (organization == null) {
      vcard.setOrganization(edited.organization());
    } else if (organization.getValues().isEmpty()) {
      organization.getValues().add(edited.organization());
    } else {
      organization.getValues().set(0, edited.organization());
    }
  }

  /**
   * Patches the birthday by replacement, not in place: BDAY may live in the
   * card as a typed property or as the raw year-less line Apple writes, and
   * whichever it was, the edit leaves exactly one spelling behind — written
   * through the same rules the export uses.
   *
   * @param vcard the card being patched
   * @param current what the card reads as today
   * @param edited what the user saved, already canonical
   */
  private void patchBirthday(VCard vcard, ParsedVCard current, ParsedVCard edited) {
    if (StringUtils.equals(current.birthday(), edited.birthday())) {
      return;
    }
    vcard.removeProperties(Birthday.class);
    for (RawProperty raw : new ArrayList<>(vcard.getExtendedProperties("BDAY"))) {
      vcard.removeProperty(raw);
    }
    writeBirthday(vcard, edited.birthday());
  }

  /**
   * Patches the FIRST postal address in place and leaves every other ADR
   * alone — the model holds one address, so one is all an edit can speak for.
   * The read folds PO box and extended address into the one street line the
   * form shows, so the edited street goes back whole into the street slot and
   * the two slots it absorbed are cleared rather than repeated.
   *
   * @param vcard the card being patched
   * @param current what the card reads as today
   * @param edited what the user saved
   */
  private void patchAddress(VCard vcard, ParsedVCard current, ParsedVCard edited) {
    if (Objects.equals(current.address(), edited.address())) {
      return;
    }
    Address first = vcard.getAddresses().isEmpty() ? null : vcard.getAddresses().get(0);
    if (edited.address() == null) {
      if (first != null) {
        vcard.removeProperty(first);
      }
      return;
    }
    if (first == null) {
      first = new Address();
      vcard.addAddress(first);
    }
    first.setPoBox(null);
    first.setExtendedAddress(null);
    first.setStreetAddress(edited.address().street());
    first.setLocality(edited.address().city());
    first.setRegion(edited.address().region());
    first.setPostalCode(edited.address().postalCode());
    first.setCountry(edited.address().country());
  }

  /**
   * Patches the note the form showed — the first non-blank one, the very one
   * {@link #noteOf} reads — in place, leaving any further NOTE lines alone.
   *
   * @param vcard the card being patched
   * @param current what the card reads as today
   * @param edited what the user saved, already capped
   */
  private void patchNote(VCard vcard, ParsedVCard current, ParsedVCard edited) {
    // Compared as plain text, because that is what the card holds: the editor
    // hands us HTML, so a note nobody touched still differs from the stored one
    // by its markup alone, and every save would rewrite the card for nothing.
    String value = plainText(edited.note());
    if (StringUtils.equals(plainText(current.note()), value)) {
      return;
    }
    Note target = vcard.getNotes().stream().filter(note -> StringUtils.isNotBlank(note.getValue())).findFirst().orElse(null);
    if (StringUtils.isBlank(value)) {
      if (target != null) {
        vcard.removeProperty(target);
      }
    } else if (target == null) {
      vcard.addNote(value);
    } else {
      target.setValue(value);
    }
  }

  /**
   * Flattens the rich text of the contact form into the plain text a vCard
   * property holds.
   * <p>
   * The note is typed in an HTML editor, but {@code NOTE} is defined as plain
   * text: pushing the markup verbatim showed raw {@code <div>} tags in Google
   * Contacts, Apple Contacts and on the phone, and the next sync read them back
   * as content, so every round trip nested them one level deeper.
   * <p>
   * Block boundaries become line breaks before the tags are dropped, so the
   * text keeps the shape it was written in. Only what really looks like a tag is
   * removed — {@code a < b} is prose and survives.
   *
   * @param html the value as the form stores it, possibly blank or already plain
   * @return the same text without markup, or {@code null} when there is nothing left
   */
  private static String plainText(String html) {
    if (StringUtils.isBlank(html)) {
      return html;
    }
    String text = html.replaceAll("(?i)<br\\s*/?>", "\n")
                      .replaceAll("(?i)</(p|div|li|tr|h[1-6])\\s*>", "\n")
                      .replaceAll("(?i)<li\\s*[^>]*>", "- ")
                      .replaceAll("</?[a-zA-Z][^>]*>", "");
    text = StringEscapeUtils.unescapeHtml4(text).replace('\u00A0', ' ');
    // Collapse the runs of blank lines that closing tags leave behind, and drop
    // the trailing break a final </div> always produces.
    return StringUtils.trimToNull(text.replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n"));
  }

  /**
   * Patches the website the form showed — the first non-blank URL, the very
   * one {@link #websiteOf} reads — in place, leaving any further URL lines
   * alone.
   *
   * @param vcard the card being patched
   * @param current what the card reads as today
   * @param edited what the user saved
   */
  private void patchWebsite(VCard vcard, ParsedVCard current, ParsedVCard edited) {
    if (StringUtils.equals(current.website(), edited.website())) {
      return;
    }
    Url target = vcard.getUrls().stream().filter(url -> StringUtils.isNotBlank(url.getValue())).findFirst().orElse(null);
    if (StringUtils.isBlank(edited.website())) {
      if (target != null) {
        vcard.removeProperty(target);
      }
    } else if (target == null) {
      vcard.addUrl(edited.website());
    } else {
      target.setValue(edited.website());
    }
  }

  /**
   * Serializes a merged card in ITS OWN version, adding and dropping nothing
   * beyond the patch itself: no PRODID stamped into somebody's card, and no
   * property discarded for being unknown to the card's declared version —
   * this writer's whole reason to exist apart from {@link #write(VCard)},
   * whose 3.0-normalizing behavior is exactly right for exports and exactly
   * wrong here.
   *
   * @param vcard the merged card
   * @return the vCard text to PUT
   */
  private String writePreserving(VCard vcard) {
    StringWriter out = new StringWriter();
    VCardVersion version = vcard.getVersion() == null ? VCardVersion.V3_0 : vcard.getVersion();
    try (VCardWriter writer = new VCardWriter(out, version)) {
      writer.setAddProdId(false);
      writer.setVersionStrict(false);
      writer.write(vcard);
    } catch (IOException e) {
      throw new IllegalStateException("A vCard could not be written", e);
    }
    return out.toString();
  }

  /**
   * Serializes one built card as 3.0 text — the writer half of the library,
   * behind the same seam as the reader.
   *
   * @param vcard the built card
   * @return the vCard text
   */
  private String write(VCard vcard) {
    StringWriter out = new StringWriter();
    try (VCardWriter writer = new VCardWriter(out, VCardVersion.V3_0)) {
      writer.write(vcard);
    } catch (IOException e) {
      // A StringWriter cannot actually fail; the writer's contract says it may.
      throw new IllegalStateException("A vCard could not be written", e);
    }
    return out.toString();
  }

  /**
   * The library's card reduced to the fields a contact row keeps — the one
   * mapping both the single-card and the streaming reads go through.
   *
   * @param vcard the parsed card
   * @return the reduced fields
   */
  private ParsedVCard toParsed(VCard vcard) {
    StructuredName structuredName = vcard.getStructuredName();
    Photo photo = firstPhoto(vcard);
    return new ParsedVCard(vcard.getUid() == null ? null : StringUtils.trimToNull(vcard.getUid().getValue()),
                           vcard.getFormattedName() == null ? null
                                                            : StringUtils.trimToNull(vcard.getFormattedName().getValue()),
                           structuredName == null ? null : StringUtils.trimToNull(structuredName.getGiven()),
                           structuredName == null ? null : StringUtils.trimToNull(structuredName.getFamily()),
                           emailsOf(vcard),
                           phonesOf(vcard),
                           vcard.getOrganization() == null || vcard.getOrganization().getValues().isEmpty() ? null
                                                                                                           : StringUtils.trimToNull(vcard.getOrganization()
                                                                                                                                         .getValues()
                                                                                                                                         .get(0)),
                           vcard.getTitles().isEmpty() ? null : StringUtils.trimToNull(vcard.getTitles().get(0).getValue()),
                           birthdayOf(vcard),
                           addressOf(vcard),
                           noteOf(vcard),
                           websiteOf(vcard),
                           photo == null ? null : photo.getData(),
                           mimeTypeOf(photo));
  }

  /**
   * The birthday, in the store's canonical text — YYYY-MM-DD, or --MM-DD when
   * the card states no year.
   * <p>
   * The library answers a BDAY three different ways depending on how it was
   * written: a full calendar date as a {@code Temporal}, a reduced-accuracy one
   * (vCard 4.0's {@code --0412}, which Apple also writes into 3.0 cards) as a
   * {@code PartialDate}, and anything it could not read as raw text. All three
   * are folded into the same canonical form, and Apple's other year-less
   * spelling — the placeholder year 1604 — is treated as the absence it means.
   *
   * @param vcard the parsed card
   * @return the canonical birthday, or null when the card has none it can say
   */
  private String birthdayOf(VCard vcard) {
    Birthday birthday = vcard.getBirthday();
    if (birthday == null) {
      return rawBirthdayOf(vcard);
    }
    PartialDate partial = birthday.getPartialDate();
    if (partial != null && partial.getMonth() != null && partial.getDate() != null) {
      return partial.getYear() != null
                                       ? EmailContactUtils.normalizeBirthday(String.format("%04d-%02d-%02d",
                                                                                           partial.getYear(),
                                                                                           partial.getMonth(),
                                                                                           partial.getDate()))
                                       : String.format(YEARLESS_BIRTHDAY_FORMAT, partial.getMonth(), partial.getDate());
    }
    java.time.temporal.Temporal date = birthday.getDate();
    if (date != null && date.isSupported(java.time.temporal.ChronoField.MONTH_OF_YEAR)
        && date.isSupported(java.time.temporal.ChronoField.DAY_OF_MONTH)) {
      int month = date.get(java.time.temporal.ChronoField.MONTH_OF_YEAR);
      int day = date.get(java.time.temporal.ChronoField.DAY_OF_MONTH);
      if (date.isSupported(java.time.temporal.ChronoField.YEAR)) {
        int year = date.get(java.time.temporal.ChronoField.YEAR);
        // 1604 is the year Apple stamps on "no year": Gregorian-cycle-neutral for
        // their code, meaningless for a person. Showing it would be showing a bug.
        return year == 1604 ? String.format(YEARLESS_BIRTHDAY_FORMAT, month, day)
                            : EmailContactUtils.normalizeBirthday(String.format("%04d-%02d-%02d", year, month, day));
      }
      return String.format(YEARLESS_BIRTHDAY_FORMAT, month, day);
    }
    // Whatever text the card carried, given one chance at the known spellings.
    return EmailContactUtils.normalizeBirthday(birthday.getText());
  }

  /**
   * The year-less birthday a 3.0 card carries as a line the library refuses to
   * type: {@code BDAY:--0412} is not legal vCard 3.0, so ez-vcard files it as a
   * raw property instead of a {@code Birthday} — while Apple exports it into
   * 3.0 cards anyway. Consulted only when no typed birthday parsed.
   *
   * @param vcard the parsed card
   * @return the canonical birthday, or null
   */
  private String rawBirthdayOf(VCard vcard) {
    ezvcard.property.RawProperty raw = vcard.getExtendedProperty("BDAY");
    return raw == null ? null : EmailContactUtils.normalizeBirthday(raw.getValue());
  }

  /**
   * The first postal address of the card, its PO box and extended-address
   * components folded in front of the street — one visual line is all the store
   * keeps for what vCard splits over three slots.
   *
   * @param vcard the parsed card
   * @return the structured address, or null when the card has none
   */
  private PostalAddress addressOf(VCard vcard) {
    if (vcard.getAddresses().isEmpty()) {
      return null;
    }
    Address address = vcard.getAddresses().get(0);
    String street = java.util.stream.Stream.of(address.getPoBox(), address.getExtendedAddress(), address.getStreetAddress())
                                           .filter(StringUtils::isNotBlank)
                                           .map(String::trim)
                                           .reduce((a, b) -> a + ", " + b)
                                           .orElse(null);
    return PostalAddress.orNull(street,
                                address.getLocality(),
                                address.getRegion(),
                                address.getPostalCode(),
                                address.getCountry());
  }

  /**
   * The first non-blank note, capped at the store's note length — the cap is
   * the store's, applied here so no caller can forget it.
   *
   * @param vcard the parsed card
   * @return the note, or null when the card carries none
   */
  private String noteOf(VCard vcard) {
    for (Note note : vcard.getNotes()) {
      String value = EmailContactUtils.truncateNote(note.getValue());
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  /**
   * The first non-blank URL of the card.
   *
   * @param vcard the parsed card
   * @return the website, or null when the card carries none
   */
  private String websiteOf(VCard vcard) {
    for (Url url : vcard.getUrls()) {
      String value = StringUtils.trimToNull(url.getValue());
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  /**
   * Writes the canonical birthday back as BDAY: a full date as a calendar date,
   * a year-less one as a raw {@code BDAY:--MM-DD} line — never with an invented
   * year. Raw because the library's 3.0 writer refuses a partial date (it emits
   * an empty BDAY), while the year-less line itself is what Apple ships in 3.0
   * cards and what this parser reads back through {@link #rawBirthdayOf}.
   *
   * @param vcard the card being built
   * @param birthday the canonical birthday, ignored when blank or unreadable
   */
  private void writeBirthday(VCard vcard, String birthday) {
    String canonical = EmailContactUtils.normalizeBirthday(birthday);
    if (canonical == null) {
      return;
    }
    if (canonical.startsWith("--")) {
      vcard.addExtendedProperty("BDAY", canonical);
    } else {
      vcard.setBirthday(new Birthday(java.time.LocalDate.parse(canonical)));
    }
  }

  /**
   * Every address in the vCard, the preferred one first.
   * <p>
   * Order matters beyond presentation: the first address becomes the contact's
   * key, so a vCard that marks a preference must not be keyed on whichever
   * address happened to be written first.
   *
   * @param vcard the parsed vCard
   * @return the addresses, never null
   */
  private List<String> emailsOf(VCard vcard) {
    List<Email> emails = new ArrayList<>(vcard.getEmails());
    emails.sort(Comparator.comparing(this::preferenceOf));
    List<String> values = new ArrayList<>();
    for (Email email : emails) {
      String value = StringUtils.trimToNull(email.getValue());
      if (value != null && !values.contains(value)) {
        values.add(value);
      }
    }
    return values;
  }

  /**
   * How strongly a vCard prefers an address, as a sort key where lower wins.
   * <p>
   * Two spellings exist and both are common: vCard 4.0's numeric PREF parameter,
   * and 3.0's TYPE=PREF flag. A vCard saying neither sorts after both.
   *
   * @param email the address property
   * @return the sort key
   */
  private int preferenceOf(Email email) {
    Integer pref = email.getPref();
    if (pref != null) {
      return pref;
    }
    return email.getTypes().stream().anyMatch(type -> "pref".equalsIgnoreCase(type.getValue())) ? 1 : Integer.MAX_VALUE;
  }

  /**
   * Every phone number, in the order the vCard lists them, each as the store's
   * {@code type,value} entry when the TEL carries a type this add-on names.
   * <p>
   * The type used to be dropped here, which flattened a number imported as
   * "work" to a bare string the moment it was stored. Only the vocabulary of
   * {@link EmailContactUtils#PHONE_TYPES} is kept — a TEL typed several ways
   * (CELL;VOICE is common) resolves to the highest-priority known one, and a
   * TEL typed only in ways the store cannot say stays a bare number rather
   * than inventing a vocabulary the exporter could not write back.
   *
   * @param vcard the parsed vCard
   * @return the phone entries, never null
   */
  private List<String> phonesOf(VCard vcard) {
    List<String> values = new ArrayList<>();
    for (Telephone phone : vcard.getTelephoneNumbers()) {
      String entry = entryOf(phone);
      if (entry != null && !values.contains(entry)) {
        values.add(entry);
      }
    }
    return values;
  }

  /**
   * One TEL as the store's {@code type,value} entry — the single mapping both
   * the read and the merge's kept-or-removed decision go through, so a line
   * always counts as the same entry it was shown as.
   *
   * @param phone the TEL property
   * @return the entry, or null when the line holds no number
   */
  private String entryOf(Telephone phone) {
    String value = StringUtils.trimToNull(phone.getText());
    if (value == null) {
      return null;
    }
    String type = EmailContactUtils.PHONE_TYPES.stream()
                                               .filter(candidate -> phone.getTypes()
                                                                         .stream()
                                                                         .anyMatch(t -> candidate.equalsIgnoreCase(t.getValue())))
                                               .findFirst()
                                               .orElse(null);
    return EmailContactUtils.phoneEntryOf(type, value);
  }

  /**
   * The first picture carried inside the vCard.
   * <p>
   * Only inline bytes count. A vCard may instead point at a URL, and fetching
   * that would mean an unbounded number of extra requests to wherever a contact's
   * vCard says — a sync must not become a crawler.
   *
   * @param vcard the parsed vCard
   * @return the photo, or null when there is none embedded
   */
  private Photo firstPhoto(VCard vcard) {
    return vcard.getPhotos().stream().filter(photo -> photo.getData() != null && photo.getData().length > 0).findFirst().orElse(null);
  }

  /**
   * The declared type of a picture, defaulting to JPEG.
   * <p>
   * A vCard photo without a declared type is overwhelmingly a JPEG, and every
   * browser sniffs the bytes anyway — but something has to be stored, and
   * guessing wrong here costs only a header.
   *
   * @param photo the photo property, may be null
   * @return the mime type, or null when there is no photo
   */
  private String mimeTypeOf(Photo photo) {
    if (photo == null) {
      return null;
    }
    ImageType type = photo.getContentType();
    return type == null || StringUtils.isBlank(type.getMediaType()) ? "image/jpeg" : type.getMediaType();
  }
}
